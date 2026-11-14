"""Solver-free semantic validation of composed-expression verdicts (FSEN 2027 #41 add-on).

This module shares no code with the prototype (Java), the reference (refcheck.py) or Z3. It has
its own tokenizer, parser and evaluator for the composed expressions that mode C records in
edges.csv, and it never calls an SMT solver.

Semantics (matching the paper's expression language at width w, default 32):
  * values are w-bit unsigned words; + - * and unary - wrap modulo 2^w;
  * & | ^ ~ are bitwise; ~v = 2^w - 1 - v;
  * < <= > >= are SIGNED (two's complement) and == != compare words; each yields 0 or 1;
  * literals are reduced modulo 2^w; symbols R<n> are load values, all other inputs absent.
Precedence (C): unary - ~ ; * ; + - ; < <= > >= ; == != ; & ; ^ ; |  (binary operators left-assoc).

Verdicts checked, for the edge's own load symbol `target`, all other symbols free:
  VARIES  must be backed by a concrete 32-bit witness found here by evaluation:
          two values of target (and one valuation of the others) giving different results.
  CONST   is checked three ways, none of which is a proof except (a):
          (a) normalisation proof: sound algebraic rewriting (ring normal form for + - * with
              literals, x^x=0, x&x=x|x=x, constant-mask folding, ~e=-e-1) removes target;
              this is an exact proof at every width, recorded as 'proved-by-normalisation';
          (b) refutation search at 32 bits: deterministic structured + pseudo-random inputs;
          (c) exhaustive enumeration at width 8 of target (and of at most one other symbol),
              for expressions without comparisons. Every operator except comparisons is a
              T-function (output bit k depends only on input bits 0..k), so a variation at
              width 8 lifts to a 32-bit variation with zero-extended inputs; we re-evaluate the
              lifted witness at 32 bits before reporting it.
          A CONST record fails if (b) or (c) finds a variation. If (a) does not apply, the CONST
          verdict is only corroborated (reduced-width + sampled), never proved, by this module.
Deterministic: every search uses random.Random(seed) with a fixed seed.
"""
import random
import re

TOKEN = re.compile(r'\s*(R\d+|0[xX][0-9a-fA-F]+|\d+|<=|>=|==|!=|[-+*&|^~()<>])')
LEVELS = [('|',), ('^',), ('&',), ('==', '!='), ('<', '<=', '>', '>='), ('+', '-'), ('*',)]


class ParseError(ValueError):
    pass


def tokens(s):
    out, i = [], 0
    s = s.rstrip()
    while i < len(s):
        m = TOKEN.match(s, i)
        if not m or m.end() == i:
            raise ParseError(f'bad token at {i} in {s!r}')
        out.append(m.group(1)); i = m.end()
    return out


def parse(s):
    t = tokens(s); pos = [0]

    def peek():
        return t[pos[0]] if pos[0] < len(t) else None

    def take(x=None):
        tok = peek()
        if tok is None or (x is not None and tok != x):
            raise ParseError(f'expected {x!r}, got {tok!r} in {s!r}')
        pos[0] += 1
        return tok

    def level(i):
        if i == len(LEVELS):
            return unary()
        e = level(i + 1)
        while peek() in LEVELS[i]:
            op = take(); e = ('bin', op, e, level(i + 1))
        return e

    def unary():
        tok = peek()
        if tok in ('-', '~'):
            take(); return ('un', tok, unary())
        if tok == '(':
            take(); e = level(0); take(')'); return e
        if tok is not None and tok.startswith('R'):
            take(); return ('sym', tok)
        if tok is not None and tok[0].isdigit():
            take(); return ('lit', int(tok, 0))
        raise ParseError(f'unexpected {tok!r} in {s!r}')

    e = level(0)
    if peek() is not None:
        raise ParseError(f'trailing {peek()!r} in {s!r}')
    return e


def symbols(e, acc=None):
    acc = set() if acc is None else acc
    if e[0] == 'sym': acc.add(e[1])
    elif e[0] == 'un': symbols(e[2], acc)
    elif e[0] == 'bin': symbols(e[2], acc); symbols(e[3], acc)
    return acc


def has_cmp(e):
    if e[0] == 'bin':
        return e[1] in ('<', '<=', '>', '>=', '==', '!=') or has_cmp(e[2]) or has_cmp(e[3])
    return e[0] == 'un' and has_cmp(e[2])


def evaluate(e, env, w=32):
    m = (1 << w) - 1
    k = e[0]
    if k == 'lit': return e[1] & m
    if k == 'sym': return env[e[1]] & m
    if k == 'un':
        v = evaluate(e[2], env, w)
        return (-v) & m if e[1] == '-' else m ^ v
    a, b, op = evaluate(e[2], env, w), evaluate(e[3], env, w), e[1]
    if op == '+': return (a + b) & m
    if op == '-': return (a - b) & m
    if op == '*': return (a * b) & m
    if op == '&': return a & b
    if op == '|': return a | b
    if op == '^': return a ^ b
    if op in ('==', '!='): return int((a == b) == (op == '=='))
    sa = a - (1 << w) if a >> (w - 1) else a
    sb = b - (1 << w) if b >> (w - 1) else b
    return int({'<': sa < sb, '<=': sa <= sb, '>': sa > sb, '>=': sa >= sb}[op])


# ---------------------------------------------------------------- normalisation (exact, solver-free)
# A normal form is either ('poly', {monomial: coeff}) with monomial a sorted tuple of atom keys,
# or an opaque atom. Atoms are hashable canonical tuples. Arithmetic is modulo 2^32.
M32 = (1 << 32) - 1


def _pmul(p, q):
    r = {}
    for m1, c1 in p.items():
        for m2, c2 in q.items():
            m = tuple(sorted(m1 + m2)); r[m] = (r.get(m, 0) + c1 * c2) & M32
    return {m: c for m, c in r.items() if c}


def _padd(p, q, s=1):
    r = dict(p)
    for m, c in q.items():
        r[m] = (r.get(m, 0) + s * c) & M32
    return {m: c for m, c in r.items() if c}


def _const(p):
    if not p: return 0
    if set(p) == {()}: return p[()]
    return None


def _atom_poly(atom):
    return {(atom,): 1}


def _key(p):
    return tuple(sorted(p.items()))


def norm(e):
    """Polynomial normal form of e (dict monomial->coeff); non-ring operators become atoms."""
    k = e[0]
    if k == 'lit': return {(): e[1] & M32} if e[1] & M32 else {}
    if k == 'sym': return _atom_poly(('sym', e[1]))
    if k == 'un':
        p = norm(e[2])
        neg = {m: (-c) & M32 for m, c in p.items()}
        return neg if e[1] == '-' else _padd(neg, {(): M32})          # ~v = -v - 1
    op, a, b = e[1], norm(e[2]), norm(e[3])
    if op == '+': return _padd(a, b)
    if op == '-': return _padd(a, b, -1)
    if op == '*': return _pmul(a, b)
    ca, cb = _const(a), _const(b)
    if op in ('&', '|', '^'):
        if ca is not None and cb is not None:
            v = {'&': ca & cb, '|': ca | cb, '^': ca ^ cb}[op]
            return {(): v} if v else {}
        if _key(a) == _key(b):
            return {} if op == '^' else a
        # flatten associative-commutative bitwise operator, folding constants
        items, cst = [], {'&': M32, '|': 0, '^': 0}[op]
        for p in (a, b):
            c = _const(p)
            sub = None
            if c is None and len(p) == 1 and next(iter(p.values())) == 1:
                (mono,) = p.keys()
                if len(mono) == 1 and mono[0][0] == op:
                    sub = mono[0]
            if c is not None:
                cst = {'&': cst & c, '|': cst | c, '^': cst ^ c}[op]
            elif sub is not None:
                for it in sub[2]:
                    items.append(it)
                cst = {'&': cst & sub[1], '|': cst | sub[1], '^': cst ^ sub[1]}[op]
            else:
                items.append(_key(p))
        if op == '^':
            cnt = {}
            for it in items: cnt[it] = cnt.get(it, 0) + 1
            items = [it for it, n in cnt.items() if n % 2]
        else:
            items = sorted(set(items))
        if op == '&' and cst == 0: return {}
        if op == '|' and cst == M32: return {(): M32}
        if not items: return {(): cst} if cst else {}
        if len(items) == 1 and cst == {'&': M32, '|': 0, '^': 0}[op]:
            return dict(items[0])
        return _atom_poly((op, cst, tuple(sorted(items))))
    # comparisons: opaque unless both sides are constant
    if ca is not None and cb is not None:
        return {(): 1} if evaluate(('bin', op, ('lit', ca), ('lit', cb)), {}) else {}
    if _key(a) == _key(b):
        return {(): 1} if op in ('==', '<=', '>=') else {}
    return _atom_poly(('cmp', op, _key(a), _key(b)))


def _mentions(obj, target):
    if isinstance(obj, tuple):
        if obj == ('sym', target): return True
        return any(_mentions(x, target) for x in obj)
    if isinstance(obj, dict):
        return any(_mentions(k, target) for k in obj)
    return False


def proved_const(e, target):
    """True only if exact rewriting eliminates every occurrence of target (sound at width 32)."""
    return not _mentions(norm(e), target)


# ---------------------------------------------------------------- searches
POOL = [0, 1, 2, 3, 5, 6, 7, 0xFF, 0x100, 0xFFFF, 0x10000, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFE, 0xFFFFFFFF]


def find_variation(e, target, seed=0x5EED, trials=6000):
    """Concrete 32-bit witness (others, a, b, va, vb) with va != vb, or None."""
    rng = random.Random(seed)
    others = sorted(symbols(e) - {target})
    for i in range(trials):
        o = {s: (POOL[(i + j) % len(POOL)] if i % 2 else rng.getrandbits(32)) for j, s in enumerate(others)}
        a = POOL[i % len(POOL)] if i < 4 * len(POOL) else rng.getrandbits(32)
        b = POOL[(i * 7 + 3) % len(POOL)] if i < 4 * len(POOL) else rng.getrandbits(32)
        va, vb = evaluate(e, dict(o, **{target: a})), evaluate(e, dict(o, **{target: b}))
        if va != vb:
            return o, a, b, va, vb
    return None


def exhaustive_w8(e, target):
    """For comparison-free expressions with at most one other symbol: a 32-bit witness lifted from an
    exhaustive width-8 enumeration, re-evaluated at 32 bits; None if none exists at width 8.
    Returns 'skipped' if the expression has comparisons or more than one other symbol."""
    others = sorted(symbols(e) - {target})
    if has_cmp(e) or len(others) > 1:
        return 'skipped'
    grid = [{}] if not others else [{others[0]: v} for v in range(256)]
    for o in grid:
        base = evaluate(e, dict(o, **{target: 0}), 8)
        for a in range(1, 256):
            if evaluate(e, dict(o, **{target: a}), 8) != base:
                va, vb = evaluate(e, dict(o, **{target: 0})), evaluate(e, dict(o, **{target: a}))
                assert va != vb, 'T-function lifting failed'   # cannot happen for comparison-free e
                return o, 0, a, va, vb
    return None


def check_edge(expr, target, verdict):
    """Returns (ok, evidence). verdict is 'CONST' or 'VARIES'."""
    e = parse(expr)
    if verdict == 'VARIES':
        wv = find_variation(e, target)
        if wv is None:
            return False, 'no 32-bit witness found for VARIES'
        return True, 'witness32 {}={}|{} others={} -> {}|{}'.format(target, wv[1], wv[2], wv[0], wv[3], wv[4])
    if verdict != 'CONST':
        return False, f'unknown verdict {verdict!r}'
    wv = find_variation(e, target)
    if wv is not None:
        return False, 'CONST contradicted at 32 bits: {}={}|{} others={} -> {}|{}'.format(target, wv[1], wv[2], wv[0], wv[3], wv[4])
    ex = exhaustive_w8(e, target)
    if ex not in (None, 'skipped'):
        return False, 'CONST contradicted (width-8 witness lifted to 32 bits): {}={}|{} others={}'.format(target, ex[1], ex[2], ex[0])
    if proved_const(e, target):
        return True, 'proved-by-normalisation'
    return True, 'corroborated-only:' + ('w8-exhaustive' if ex is None else 'w8-skipped') + '+w32-search'


if __name__ == '__main__':
    import sys
    print(check_edge(sys.argv[1], sys.argv[2], sys.argv[3]))
