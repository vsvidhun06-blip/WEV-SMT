"""Expression-level check of the bridge assumption: is the prototype's composed store
expression EQUAL (as a 32-bit function of the load symbols) to the expression the independent
reference builds for the same store?

Usage: python -I expr_equiv.py <java OUT/set dir> <inputs dir> <list file> <out dir>

For every mode C data edge in a file that the reference also parses, the prototype's printed
composed expression (edges.csv, column composed_expr, over symbols R<event id>) is parsed with
the reference's own expression parser, its symbols are renamed to the reference's
T<thread>_L<ordinal> through the event table, and it is compared with the reference's own
symbolic value of that store:
  * Z3 must prove  java != ref  unsatisfiable over 32-bit vectors (shared solver), and
  * 2,000 solver-free random evaluations must find no difference (a difference found here
    would be a concrete counterexample, re-evaluated without Z3).
Initial registers are fixed to their litmus values (or 0) on both sides.
Statuses: EQUAL, DIFFERENT (with a counterexample), UNKNOWN (solver), UNPARSED, NO_REF_STORE.
This is a test of the two implementations against each other, not a proof that either equals
the composed label of the Isabelle development.
"""
import csv, os, random, sys
import z3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refcheck as R  # noqa: E402


def rename(e, ren):
    k = e[0]
    if k == 'var':
        if e[1] not in ren:
            raise R.Reject('UNMAPPED:' + e[1])
        return ('sym', ren[e[1]])
    if k == 'un':
        return ('un', e[1], rename(e[2], ren))
    if k == 'bin':
        return ('bin', e[1], rename(e[2], ren), rename(e[3], ren))
    return e


def main():
    jout, base, listf, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)
    rng = random.Random(20261106)
    pos, by = {}, {}
    for r in csv.DictReader(open(os.path.join(jout, 'events.csv'), encoding='utf-8')):
        if r['thread'] == '0' or r['class'] not in ('ReadEvent', 'WriteEvent'):
            continue
        by.setdefault((r['file'], int(r['thread'])), []).append(int(r['id']))
    for (f, t), ids in by.items():
        for k, i in enumerate(sorted(ids)):
            pos[(f, i)] = (t, k)
    edges = {}
    for r in csv.DictReader(open(os.path.join(jout, 'edges.csv'), encoding='utf-8')):
        edges.setdefault(r['file'], []).append(r)
    rows, summ = [], {}
    for rel in [l.strip() for l in open(listf, encoding='utf-8') if l.strip()]:
        if rel not in edges:
            continue
        try:
            src = open(os.path.join(base, rel), encoding='utf-8', errors='replace').read()
            init_regs, threads = R.read_litmus(src)
            per = {tidx + 1: R.exec_thread(tnum, body, init_regs, 32) for tidx, (tnum, body) in enumerate(threads)}
            tnum_of = {tidx + 1: tnum for tidx, (tnum, _) in enumerate(threads)}  # reference names symbols by litmus thread
        except R.Reject:
            continue  # the reference does not parse this file; its edges stay unchecked
        for e in edges[rel]:
            wt = pos.get((rel, int(e['write_id'])))
            st, ev = 'EQUAL', ''
            try:
                if wt is None:
                    raise R.Reject('NO_EVENT')
                store = next((o for o in per.get(wt[0], []) if o[0] == 'W' and o[1] == wt[1]), None)
                if store is None:
                    st = 'NO_REF_STORE'
                    raise StopIteration
                ren = {f'R{i}': f'T{tnum_of[t]}_L{k}' for (f, i), (t, k) in pos.items() if f == rel and t in tnum_of}
                je = rename(R.parse_expr(e['composed_expr']), ren)
                re_ = store[3]
                ss = sorted(R.syms(je, set()) | R.syms(re_, set()))
                for _ in range(2000):
                    env = {s: rng.getrandbits(32) for s in ss}
                    a, b = R.ev(je, env, 32), R.ev(re_, env, 32)
                    if a != b:
                        st, ev = 'DIFFERENT', f'{env} -> java {a} ref {b}'
                        raise StopIteration
                zs = {s: z3.BitVec(s, 32) for s in ss}
                sv = z3.Solver(); sv.set('timeout', 60000)
                sv.add(R.z3term(je, zs, 32) != R.z3term(re_, zs, 32))
                res = sv.check()
                if res == z3.sat:
                    m = sv.model()
                    env = {s: m.eval(v, model_completion=True).as_long() for s, v in zs.items()}
                    a, b = R.ev(je, env, 32), R.ev(re_, env, 32)
                    st, ev = ('DIFFERENT', f'{env} -> java {a} ref {b}') if a != b else ('UNKNOWN', 'model does not re-evaluate')
                elif res == z3.unknown:
                    st = 'UNKNOWN'
                else:
                    ev = 'z3-unsat; 2000 random evaluations equal'
            except StopIteration:
                pass
            except R.Reject as ex:
                st, ev = 'UNPARSED', str(ex)
            summ[st] = summ.get(st, 0) + 1
            rows.append([rel, e['read_id'], e['write_id'], st, ev])
    with open(os.path.join(out, 'expr-equiv.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f); w.writerow(['file', 'read_id', 'write_id', 'status', 'evidence']); w.writerows(rows)
    with open(os.path.join(out, 'expr-equiv-summary.txt'), 'w', encoding='utf-8') as f:
        for k in sorted(summ):
            f.write(f'{k}\t{summ[k]}\n')
    print(open(os.path.join(out, 'expr-equiv-summary.txt'), encoding='utf-8').read())


if __name__ == '__main__':
    main()
