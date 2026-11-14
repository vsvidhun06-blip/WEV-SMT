"""Independent reference for the composed-label (mode C) dependency decision.

Shares NO code with the Java prototype: own C-litmus reader, own expression parser (C
precedence), own symbolic executor, own pure-Python bit-vector evaluator. Per data pair
(load L, store S) of one thread it decides whether S's composed value expression is
non-constant in L's symbol (other loads free, initial registers = litmus init or 0).

Decision procedure, kept separate by direction:
  VARIES : a concrete witness (a, b, others) with E(a,others) != E(b,others), found by
           pure-Python random/structured search and RE-EVALUATED in pure Python. No solver.
  CONST  : unsat of E(a,o) != E(b,o) by Z3 over a term built by THIS script (the solver is
           the same product the prototype uses -> not solver-independent), corroborated by
           exhaustive pure-Python evaluation at width 8 (all a, b; up to 2 other symbols
           exhaustive, more sampled) and 20k random 32-bit trials.
Widths: decided at 32 (prototype semantics) and 64 (sensitivity).

Unsupported constructs are REJECTED with a reason (never decided).

Usage: python -I refcheck.py <java_out_dir> <baseDir> <listFile> <outDir>
"""
import csv, os, random, re, sys
import z3

SUP_BIN = {'+', '-', '*', '&', '^', '|', '==', '!=', '<', '<=', '>', '>='}
PREC = [('|',), ('^',), ('&',), ('==', '!='), ('<', '<=', '>', '>='), ('<<', '>>'), ('+', '-'), ('*', '/', '%')]


class Reject(Exception):
    pass


# ---------------------------------------------------------------- expression parsing
TOK = re.compile(r'\s*(0[xX][0-9a-fA-F]+|\d+|[A-Za-z_][A-Za-z0-9_]*|<<|>>|<=|>=|==|!=|&&|\|\||->|[-+*/%&|^~!()<>?:,\[\].])')


def tokenize(s):
    pos, out = 0, []
    s = s.strip()
    while pos < len(s):
        m = TOK.match(s, pos)
        if not m:
            raise Reject('LEX:' + s[pos:pos + 8])
        out.append(m.group(1)); pos = m.end()
        while pos < len(s) and s[pos].isspace():
            pos += 1
    return out


class P:
    def __init__(self, toks):
        self.t, self.i = toks, 0

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self):
        x = self.peek(); self.i += 1; return x

    def parse(self):
        e = self.binary(0)
        if self.peek() is not None:
            raise Reject('TRAILING:' + str(self.peek()))
        return e

    def binary(self, lvl):
        if lvl == len(PREC):
            return self.unary()
        e = self.binary(lvl + 1)
        while self.peek() in PREC[lvl]:
            op = self.take()
            if op not in SUP_BIN:
                raise Reject('OP:' + op)
            e = ('bin', op, e, self.binary(lvl + 1))
        if self.peek() in ('&&', '||', '?', ':', '->', '[', '.', ','):
            raise Reject('OP:' + self.peek())
        return e

    def unary(self):
        x = self.peek()
        if x in ('-', '~'):
            self.take(); return ('un', x, self.unary())
        if x == '+':
            self.take(); return self.unary()
        if x == '!':
            raise Reject('OP:!')
        if x == '(':
            self.take(); e = self.binary(0)
            if self.take() != ')':
                raise Reject('PAREN')
            return e
        if x is None:
            raise Reject('EMPTY')
        self.take()
        if re.fullmatch(r'0[xX][0-9a-fA-F]+|\d+', x):
            return ('lit', int(x, 0))
        if re.fullmatch(r'[A-Za-z_]\w*', x):
            if self.peek() in ('(', '[', '.', '->'):
                raise Reject('CALL_OR_INDEX:' + x)
            return ('var', x)
        raise Reject('TOKEN:' + x)


def parse_expr(s):
    return P(tokenize(s)).parse()


# ---------------------------------------------------------------- evaluation
def ev(e, env, w):
    m = (1 << w) - 1
    k = e[0]
    if k == 'lit':
        return e[1] & m
    if k == 'sym':
        return env[e[1]] & m
    if k == 'un':
        v = ev(e[2], env, w)
        return (-v) & m if e[1] == '-' else (~v) & m
    a, b = ev(e[2], env, w), ev(e[3], env, w)
    op = e[1]
    if op == '+': return (a + b) & m
    if op == '-': return (a - b) & m
    if op == '*': return (a * b) & m
    if op == '&': return a & b
    if op == '^': return a ^ b
    if op == '|': return a | b
    sa = a - (1 << w) if a >> (w - 1) else a
    sb = b - (1 << w) if b >> (w - 1) else b
    r = {'==': a == b, '!=': a != b, '<': sa < sb, '<=': sa <= sb, '>': sa > sb, '>=': sa >= sb}[op]
    return 1 if r else 0


def syms(e, acc):
    if e[0] == 'sym': acc.add(e[1])
    elif e[0] == 'un': syms(e[2], acc)
    elif e[0] == 'bin': syms(e[2], acc); syms(e[3], acc)
    return acc


def z3term(e, env, w):
    k = e[0]
    if k == 'lit': return z3.BitVecVal(e[1], w)
    if k == 'sym': return env[e[1]]
    if k == 'un':
        v = z3term(e[2], env, w)
        return -v if e[1] == '-' else ~v
    a, b = z3term(e[2], env, w), z3term(e[3], env, w)
    op = e[1]
    if op == '+': return a + b
    if op == '-': return a - b
    if op == '*': return a * b
    if op == '&': return a & b
    if op == '^': return a ^ b
    if op == '|': return a | b
    one, zero = z3.BitVecVal(1, w), z3.BitVecVal(0, w)
    c = {'==': a == b, '!=': a != b, '<': a < b, '<=': a <= b, '>': a > b, '>=': a >= b}[op]  # signed
    return z3.If(c, one, zero)


SPECIAL = [0, 1, 2, 3, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFFFE, 0x10000, 0xFFFF, 5, 6]


def decide(e, target, w, rng):
    """Returns (verdict, evidence)."""
    others = sorted(syms(e, set()) - {target})
    m = (1 << w) - 1
    if target not in syms(e, set()):
        return 'CONST', 'symbol-absent'
    pool = [v & m for v in SPECIAL] + [m, m - 1, 1 << (w - 1), (1 << (w - 1)) - 1]
    # witness search (pure Python)
    for trial in range(4000):
        o = {s: (rng.choice(pool) if trial % 2 else rng.getrandbits(w)) for s in others}
        a = rng.choice(pool) if trial % 3 == 0 else rng.getrandbits(w)
        b = rng.choice(pool) if trial % 5 == 0 else rng.getrandbits(w)
        ea = ev(e, dict(o, **{target: a}), w); eb = ev(e, dict(o, **{target: b}), w)
        if ea != eb:
            return 'VARIES', f'witness {target}={a}|{b} others={o} -> {ea}|{eb}'
    # Z3 (own encoding)
    zs = {s: z3.BitVec(s, w) for s in others}
    za, zb = z3.BitVec('__a', w), z3.BitVec('__b', w)
    s = z3.Solver(); s.set('timeout', 60000)
    s.add(z3term(e, dict(zs, **{target: za}), w) != z3term(e, dict(zs, **{target: zb}), w))
    r = s.check()
    if r == z3.sat:
        mdl = s.model()
        val = lambda v: mdl.eval(v, model_completion=True).as_long()
        o = {k: val(v) for k, v in zs.items()}; a, b = val(za), val(zb)
        ea = ev(e, dict(o, **{target: a}), w); eb = ev(e, dict(o, **{target: b}), w)
        if ea == eb:
            return 'ERROR', 'z3 model does not re-evaluate'
        return 'VARIES', f'z3-witness re-evaluated {target}={a}|{b} -> {ea}|{eb}'
    if r == z3.unknown:
        return 'UNKNOWN', 'z3 unknown/timeout'
    return 'CONST', 'z3-unsat'


def corroborate_const(e, target, rng):
    """Pure-Python evidence for a CONST verdict: exhaustive at width 8 + 20k random at 32."""
    others = sorted(syms(e, set()) - {target})
    w = 8
    if len(others) <= 1:
        grid = [dict()] if not others else [{others[0]: v} for v in range(256)]
    else:
        grid = [{s: rng.getrandbits(8) for s in others} for _ in range(512)]
    for o in grid:
        base = ev(e, dict(o, **{target: 0}), w)
        for a in range(256):
            if ev(e, dict(o, **{target: a}), w) != base:
                return f'w8-differs(ok-if-width-specific) {target}={a} o={o}'
    for _ in range(20000):
        o = {s: rng.getrandbits(32) for s in others}
        if ev(e, dict(o, **{target: rng.getrandbits(32)}), 32) != ev(e, dict(o, **{target: rng.getrandbits(32)}), 32):
            return 'CONTRADICTION-w32-random'
    return 'w8-exhaustive-const;w32-random-20k-const' if len(others) <= 1 else 'w8-sampled-const;w32-random-20k-const'


# ---------------------------------------------------------------- litmus reading
LOAD = [re.compile(r'^READ_ONCE\(\s*\*\s*(\w+)\s*\)$'), re.compile(r'^smp_load_acquire\(\s*(\w+)\s*\)$'),
        re.compile(r'^atomic_load_explicit\(\s*(\w+)\s*,\s*memory_order_\w+\s*\)$'),
        re.compile(r'^atomic_load\(\s*(\w+)\s*\)$')]
STORE = [re.compile(r'^WRITE_ONCE\(\s*\*\s*(\w+)\s*,(.*)\)$'), re.compile(r'^smp_store_release\(\s*(\w+)\s*,(.*)\)$'),
         re.compile(r'^atomic_store_explicit\(\s*(\w+)\s*,(.*),\s*memory_order_\w+\s*\)$'),
         re.compile(r'^atomic_store\(\s*(\w+)\s*,(.*)\)$')]
FENCE = re.compile(r'^(smp_mb|smp_rmb|smp_wmb|smp_mb__before_atomic|smp_mb__after_atomic|atomic_thread_fence\(\s*memory_order_\w+\s*\)|smp_mb\(\))\s*(\(\s*\))?$')
DECL = re.compile(r'^(int|long|unsigned|intptr_t)\s+([\w\s,]+)$')


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
    s = re.sub(r'\(\*.*?\*\)', ' ', s, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', s)


def read_litmus(src):
    src = strip_comments(src)
    lines = src.split('\n')
    if not lines[0].strip().startswith('C '):
        raise Reject('NOT_C_LITMUS')
    m = re.search(r'\{(.*?)\}', src, flags=re.S)
    init_regs = {}
    if m:
        for part in re.split(r'[;\n]', m.group(1)):
            mm = re.match(r'^\s*(\d+):(\w+)\s*=\s*(-?(?:0[xX][0-9a-fA-F]+|\d+))\s*$', part)
            if mm:
                init_regs[(int(mm.group(1)), mm.group(2))] = int(mm.group(3), 0)
    threads = []
    for mt in re.finditer(r'\bP(\d+)\s*\(([^)]*)\)\s*\{', src):
        i, depth = mt.end(), 1
        while depth and i < len(src):
            depth += {'{': 1, '}': -1}.get(src[i], 0); i += 1
        threads.append((int(mt.group(1)), src[mt.end():i - 1]))
    if not threads:
        # herd tabular form: header 'P0 | P1 ;' then rows of '|'-separated cells ending in ';'
        # (as in herd, '|' is the column separator, so bitwise-or cannot appear in a cell)
        body = src[m.end():] if m else src
        rows = [l for l in body.splitlines() if l.strip().endswith(';') and '|' in l or re.match(r'^\s*P0\s*;\s*$', l)]
        if not rows or not re.match(r'^\s*P\d+\s*(\|\s*P\d+\s*)*;\s*$', rows[0]):
            raise Reject('NO_THREADS')
        heads = [int(h.strip()[1:]) for h in rows[0].strip().rstrip(';').split('|')]
        cols = [[] for _ in heads]
        for r in rows[1:]:
            cells = r.strip().rstrip(';').split('|')
            if len(cells) != len(heads):
                raise Reject('TABULAR_ARITY')
            for i, c in enumerate(cells):
                if c.strip(): cols[i].append(c.strip())
        threads = [(h, ';'.join(c)) for h, c in zip(heads, cols)]
    return init_regs, threads


def exec_thread(tid, body, init_regs, w):
    if '{' in body or '}' in body:
        raise Reject('BLOCK')
    env, ops, nacc = {}, [], 0
    def resolve(e):
        if e[0] == 'var':
            r = e[1]
            if r in env: return env[r]
            return ('lit', init_regs.get((tid, r), 0))
        if e[0] == 'un': return ('un', e[1], resolve(e[2]))
        if e[0] == 'bin': return ('bin', e[1], resolve(e[2]), resolve(e[3]))
        return e
    for stmt in body.split(';'):
        st = ' '.join(stmt.split())
        if not st:
            continue
        if re.match(r'^(if|while|for|do|switch|return|goto)\b', st):
            raise Reject('CONTROL')
        if FENCE.match(st):
            continue
        d = DECL.match(st)
        if d and '=' not in st:
            continue
        if d:
            st = st[len(d.group(1)):].strip()
        sm = next((p.match(st) for p in STORE if p.match(st)), None)
        if sm:
            ops.append(('W', nacc, sm.group(1), resolve(parse_expr(sm.group(2))))); nacc += 1
            continue
        am = re.match(r'^(\w+)\s*=\s*(.*)$', st)
        if not am:
            raise Reject('STMT:' + st[:30])
        reg, rhs = am.group(1), am.group(2).strip()
        lm = next((p.match(rhs) for p in LOAD if p.match(rhs)), None)
        if lm:
            sym = f'T{tid}_L{nacc}'
            env[reg] = ('sym', sym); ops.append(('R', nacc, lm.group(1), sym)); nacc += 1
            continue
        if re.search(r'\b(xchg|cmpxchg|atomic_\w+|READ_ONCE|WRITE_ONCE|smp_\w+|rcu_\w+|spin_\w+)\b', rhs):
            raise Reject('MEMOP_IN_EXPR')
        env[reg] = resolve(parse_expr(rhs))
    return ops


def main():
    jout, base, listf, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)
    rng = random.Random(20261105)
    # Java edges joined to (thread, per-thread access ordinal)
    ev_rows = list(csv.DictReader(open(os.path.join(jout, 'events.csv'), encoding='utf-8')))
    pos = {}
    by = {}
    for r in ev_rows:
        if r['thread'] == '0' or r['class'] not in ('ReadEvent', 'WriteEvent'):
            continue
        by.setdefault((r['file'], int(r['thread'])), []).append(int(r['id']))
    for (f, t), ids in by.items():
        for k, i in enumerate(sorted(ids)):
            pos[(f, i)] = (t, k)
    jedges = {}
    for r in csv.DictReader(open(os.path.join(jout, 'edges.csv'), encoding='utf-8')):
        rt = pos.get((r['file'], int(r['read_id']))); wt = pos.get((r['file'], int(r['write_id'])))
        jedges[(r['file'], rt, wt)] = r
    jfiles = {r['file']: r for r in csv.DictReader(open(os.path.join(jout, 'files.csv'), encoding='utf-8'))}

    fo = open(os.path.join(out, 'ref-pairs.csv'), 'w', newline='', encoding='utf-8')
    fw = csv.writer(fo)
    fw.writerow(['file', 'thread', 'load_ord', 'store_ord', 'store_loc', 'ref32', 'ref64', 'evidence32', 'const_corroboration',
                 'java_edge', 'java_composed', 'agree'])
    fx = csv.writer(open(os.path.join(out, 'ref-files.csv'), 'w', newline='', encoding='utf-8'))
    fx.writerow(['file', 'ref_status', 'ref_reason', 'java_support', 'java_reason'])
    summary = {}
    def bump(k): summary[k] = summary.get(k, 0) + 1
    for rel in [l.strip() for l in open(listf, encoding='utf-8') if l.strip()]:
        js = jfiles.get(rel, {})
        try:
            src = open(os.path.join(base, rel), encoding='utf-8', errors='replace').read()
            init_regs, threads = read_litmus(src)
            per = [(tidx + 1, exec_thread(tnum, body, init_regs, 32)) for tidx, (tnum, body) in enumerate(threads)]
        except Reject as ex:
            fx.writerow([rel, 'REJECTED', str(ex), js.get('support', ''), js.get('reason', '')])
            bump(('file', 'REF_REJECTED', js.get('support', 'NA'))); continue
        fx.writerow([rel, 'SUPPORTED', '', js.get('support', ''), js.get('reason', '')])
        bump(('file', 'REF_SUPPORTED', js.get('support', 'NA')))
        if js.get('support') != 'SUPPORTED':
            continue
        seen = set()
        for t, ops in per:
            loads = {o[3]: o[1] for o in ops if o[0] == 'R'}
            for o in ops:
                if o[0] != 'W':
                    continue
                e = o[3]
                for sym in sorted(syms(e, set())):
                    lo = loads[sym]
                    v32, evd = decide(e, sym, 32, rng)
                    v64, _ = decide(e, sym, 64, rng)
                    cor = corroborate_const(e, sym, rng) if v32 == 'CONST' else ''
                    key = (rel, (t, lo), (t, o[1])); seen.add(key)
                    j = jedges.get(key)
                    jc = j['composed'] if j else ''
                    agree = 'NO_JAVA_EDGE' if not j else ('AGREE' if jc == v32 else 'DISAGREE')
                    bump(('pair', agree, v32));
                    if v32 != v64: bump(('width-sensitive', v32, v64))
                    fw.writerow([rel, t, lo, o[1], o[2], v32, v64, evd, cor, 'yes' if j else 'no', jc, agree])
        for key, j in jedges.items():
            if key[0] == rel and key not in seen:
                bump(('pair', 'JAVA_ONLY', j['composed']))
                fw.writerow([rel, key[1][0] if key[1] else '', key[1][1] if key[1] else '', key[2][1] if key[2] else '',
                             j['write_loc'], '', '', '', '', 'yes', j['composed'], 'JAVA_ONLY'])
    fo.close()
    with open(os.path.join(out, 'summary.txt'), 'w', encoding='utf-8') as s:
        for k in sorted(summary, key=str):
            s.write(f'{k}\t{summary[k]}\n')
    print(open(os.path.join(out, 'summary.txt'), encoding='utf-8').read())


if __name__ == '__main__':
    main()
