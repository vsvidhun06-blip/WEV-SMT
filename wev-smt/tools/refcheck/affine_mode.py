"""Mode L: the ring-weighted closure (Thm. "weighted closure", PwT_Affine.wdep).

Instruction-local and chained, like the closure of modes A/B, but every instruction is
summarised by its coefficient vector over Z/2^32 (coef in PwT_Affine) instead of one bit per
read register, and the summaries are chained with ring arithmetic (multiply along a
definition, add across paths). The chaining here is forward (each register carries the
coefficients of the load symbols in its current value); PwT_Affine.wdep chains backward.
Both compute products of the same per-instruction coefficient matrices.

An instruction outside the affine fragment (xor, and, or, comparisons, a product of two
non-literal operands) makes its target NON-AFFINE; a store whose value is non-affine is
not decided by mode L (it ABSTAINS, which the paper reports separately).

Uses the reference's own litmus reader and expression parser (refcheck.py), so it shares
no code with the Java prototype. Initial registers are literal constants (litmus init or 0),
as in mode C and the reference.

Usage: python -I affine_mode.py <inputsDir> <listFile> <ref-pairs.csv> <outDir>
"""
import csv, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refcheck as R  # noqa: E402

M = (1 << 32) - 1
NONAFF = None


def lin(e, env):
    """Affine form of e over load symbols: (dict sym->coef, const), or NONAFF."""
    k = e[0]
    if k == 'lit':
        return ({}, e[1] & M)
    if k == 'var':
        return env(e[1])
    if k == 'un':
        a = lin(e[2], env)
        if a is NONAFF:
            return NONAFF
        c, b = a
        neg = {s: (-v) & M for s, v in c.items()}
        return (neg, (-b) & M) if e[1] == '-' else (neg, (~b) & M)  # ~w = -w - 1
    if k == 'bin':
        op = e[1]
        if op in ('+', '-'):
            a, b = lin(e[2], env), lin(e[3], env)
            if a is NONAFF or b is NONAFF:
                return NONAFF
            sg = 1 if op == '+' else -1
            c = dict(a[0])
            for s, v in b[0].items():
                c[s] = (c.get(s, 0) + sg * v) & M
            return ({s: v for s, v in c.items() if v}, (a[1] + sg * b[1]) & M)
        if op == '*':
            # affine only when one operand is a literal (PwT_Affine.affine, case OMul)
            for lit, other in ((e[2], e[3]), (e[3], e[2])):
                if lit[0] == 'lit':
                    o = lin(other, env)
                    if o is NONAFF:
                        return NONAFF
                    n = lit[1] & M
                    return ({s: (n * v) & M for s, v in o[0].items() if (n * v) & M}, (n * o[1]) & M)
            return NONAFF
        return NONAFF
    return NONAFF


def walk(tid, body, init_regs):
    if '{' in body or '}' in body:
        raise R.Reject('BLOCK')
    regs, ops, nacc = {}, [], 0
    def env(r):
        if r in regs:
            return regs[r]
        return ({}, init_regs.get((tid, r), 0) & M)
    for stmt in body.split(';'):
        st = ' '.join(stmt.split())
        if not st:
            continue
        if re.match(r'^(if|while|for|do|switch|return|goto)\b', st):
            raise R.Reject('CONTROL')
        if R.FENCE.match(st):
            continue
        d = R.DECL.match(st)
        if d and '=' not in st:
            continue
        if d:
            st = st[len(d.group(1)):].strip()
        sm = next((p.match(st) for p in R.STORE if p.match(st)), None)
        if sm:
            ops.append(('W', nacc, lin(R.parse_expr(sm.group(2)), env))); nacc += 1
            continue
        am = re.match(r'^(\w+)\s*=\s*(.*)$', st)
        if not am:
            raise R.Reject('STMT:' + st[:30])
        reg, rhs = am.group(1), am.group(2).strip()
        lm = next((p.match(rhs) for p in R.LOAD if p.match(rhs)), None)
        if lm:
            sym = f'T{tid}_L{nacc}'
            regs[reg] = ({sym: 1}, 0); ops.append(('R', nacc, sym)); nacc += 1
            continue
        regs[reg] = lin(R.parse_expr(rhs), env)
    return ops


def main():
    inputs, listf, refpairs, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)
    ref = {}
    for r in csv.DictReader(open(refpairs, encoding='utf-8')):
        if r['ref32']:
            ref[(r['file'], int(r['thread']), int(r['load_ord']), int(r['store_ord']))] = r['ref32']
    rows, summ = [], {}
    def bump(k):
        summ[k] = summ.get(k, 0) + 1
    for rel in [l.strip() for l in open(listf, encoding='utf-8') if l.strip()]:
        form = 'chain' if '-chain' in rel else ('direct' if '-direct' in rel else 'check')
        try:
            init_regs, threads = R.read_litmus(open(os.path.join(inputs, rel), encoding='utf-8').read())
            per = [(i + 1, walk(tn, body, init_regs)) for i, (tn, body) in enumerate(threads)]
        except R.Reject as ex:
            bump(('file', 'REJECTED')); rows.append([rel, '', '', '', form, 'REJECTED:' + str(ex), '', '']); continue
        bump(('file', 'READ'))
        for t, ops in per:
            for o in ops:
                if o[0] != 'W':
                    continue
                for lo in [p for p in ops if p[0] == 'R' and p[1] < o[1]]:
                    key = (rel, t, lo[1], o[1])
                    rv = ref.get(key)
                    if rv is None:
                        continue  # no syntactic/composed pair in the reference
                    if o[2] is NONAFF:
                        lv = 'ABSTAIN'
                    else:
                        lv = 'VARIES' if o[2][0].get(lo[2], 0) else 'CONST'
                    ag = 'ABSTAIN' if lv == 'ABSTAIN' else ('AGREE' if lv == rv else 'DISAGREE')
                    bump(('pair', form, rv, ag))
                    rows.append([rel, t, lo[1], o[1], form, lv, rv, ag])
    with open(os.path.join(out, 'affine-pairs.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f); w.writerow(['file', 'thread', 'load_ord', 'store_ord', 'form', 'modeL', 'ref32', 'agree']); w.writerows(rows)
    with open(os.path.join(out, 'affine-summary.txt'), 'w', encoding='utf-8') as s:
        for k in sorted(summ, key=str):
            s.write(f'{k}\t{summ[k]}\n')
    print(open(os.path.join(out, 'affine-summary.txt'), encoding='utf-8').read())


if __name__ == '__main__':
    main()
