"""Tests for semcheck.py (solver-free validator). Usage: python -I test_semcheck.py [OUT]

1. Fixed cases with known verdicts at 32 bits.
2. Soundness of the normalisation proof: for 3,000 random expressions (seed 20261106) over
   R1, R2, every expression that the normaliser claims constant in R1 shows no variation on
   200 random 32-bit input pairs, and on every pair of the 15-value structured pool.
3. Mutation sensitivity: each fixed CONST case with one operator changed must be rejected
   when its recorded verdict is kept, where the mutated expression varies.
4. If OUT is given: every recorded composed verdict in OUT/{diag,regress,chain,corpus}/edges.csv
   is accepted, and the counts of proved / corroborated-only CONST verdicts are printed.
Exits non-zero on any failure.
"""
import csv, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import semcheck as S  # noqa: E402

fails = 0


def rep(ok, msg):
    global fails
    print(('PASS  ' if ok else 'FAIL  ') + msg); fails += not ok


FIXED = [  # (expr, target, verdict at 32 bits, normaliser proves it?)
    ('(((R1)^(R1))+1)', 'R1', 'CONST', True),
    ('(((R1) + 1) - (R1))', 'R1', 'CONST', True),
    ('((R1) + (R2) - (R1))', 'R1', 'CONST', True),
    ('(((R1) & 1) & 2)', 'R1', 'CONST', True),
    ('(((R1) * 65536) * 65536)', 'R1', 'CONST', True),
    ('(~(R1) + (R1))', 'R1', 'CONST', True),
    ('((R1) | (R1)) - (R1)', 'R1', 'CONST', True),
    ('(((R1) + (R1)) + 1)', 'R1', 'VARIES', False),
    ('(((R1) * 65536) * 32768)', 'R1', 'VARIES', False),
    ('((R1) < (R2))', 'R1', 'VARIES', False),
    ('(((R1) & 1) & 3)', 'R1', 'VARIES', False),
    ('((R1) ^ (R2)) ^ (R1)', 'R1', 'CONST', True),
    ('-(R1)', 'R1', 'VARIES', False),
    ('(R2)', 'R1', 'CONST', True),
]
for expr, t, v, prov in FIXED:
    ok, ev = S.check_edge(expr, t, v)
    rep(ok, f'fixed {expr} {v}: {ev}')
    rep(S.proved_const(S.parse(expr), t) == prov, f'fixed {expr}: normaliser proves constancy = {prov}')
    other = 'VARIES' if v == 'CONST' else 'CONST'
    ok2, ev2 = S.check_edge(expr, t, other)
    rep(not ok2, f'fixed {expr}: wrong verdict {other} rejected ({ev2})')


def rand_expr(rng, d):
    if d == 0 or rng.random() < 0.25:
        c = rng.random()
        return ('sym', 'R1') if c < 0.4 else ('sym', 'R2') if c < 0.6 else ('lit', rng.choice([0, 1, 2, 3, 255, 65536, 0xFFFFFFFF, rng.getrandbits(32)]))
    if rng.random() < 0.15:
        return ('un', rng.choice('-~'), rand_expr(rng, d - 1))
    return ('bin', rng.choice(['+', '-', '*', '&', '|', '^', '+', '-', '^', '<']), rand_expr(rng, d - 1), rand_expr(rng, d - 1))


rng = random.Random(20261106)
claimed = bad = 0
for _ in range(3000):
    e = rand_expr(rng, 4)
    if 'R1' in S.symbols(e) and S.proved_const(e, 'R1'):
        claimed += 1
        pairs = [(rng.getrandbits(32), rng.getrandbits(32), rng.getrandbits(32)) for _ in range(200)]
        pairs += [(a, b, c) for a in S.POOL for b in S.POOL[:5] for c in S.POOL[:3]]
        if any(S.evaluate(e, {'R1': a, 'R2': c}) != S.evaluate(e, {'R1': b, 'R2': c}) for a, b, c in pairs):
            bad += 1
rep(bad == 0 and claimed > 50, f'normaliser soundness: {claimed} random expressions proved constant in R1 (and still mentioning it syntactically), {bad} contradicted by evaluation')

MUT = [('(((R1)^(R1))+1)', '(((R1)+(R1))+1)'), ('(((R1) + 1) - (R1))', '(((R1) + 1) + (R1))'),
       ('(((R1) & 1) & 2)', '(((R1) & 1) | 2)'), ('(((R1) * 65536) * 65536)', '(((R1) * 65536) * 32768)')]
for good, mutated in MUT:
    ok, ev = S.check_edge(mutated, 'R1', 'CONST')
    rep(not ok, f'mutation {good} -> {mutated} with CONST kept is rejected ({ev})')

if len(sys.argv) > 1:
    OUT = sys.argv[1]
    for name in ('diag', 'regress', 'chain', 'corpus'):
        n = proved = corr = bad = 0
        for r in csv.DictReader(open(os.path.join(OUT, name, 'edges.csv'), encoding='utf-8')):
            n += 1
            ok, ev = S.check_edge(r['composed_expr'], 'R' + r['read_id'], r['composed'])
            bad += not ok
            if r['composed'] == 'CONST':
                proved += ev == 'proved-by-normalisation'; corr += ev.startswith('corroborated-only')
            if not ok: print('   ', name, r['file'], r['composed_expr'], r['composed'], ev)
        rep(bad == 0, f'{name}: {n} recorded verdicts accepted by the solver-free validator '
                      f'(CONST proved by normalisation {proved}, corroborated only {corr})')
print(f'\n{fails} failure(s)')
sys.exit(1 if fails else 0)
