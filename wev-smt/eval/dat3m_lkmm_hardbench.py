#!/usr/bin/env python
"""Dat3M/LKMM external dependency baseline over the hard-expression rings.

Reproduces the external-baseline result reported in the paper (S6.2 and App. A.4):
Dat3M/Dartagnan v4.4.1 under LKMM, run on the subset of the 3,656 hard-expression
ring instances that its C front end can represent.

    python eval/dat3m_lkmm_hardbench.py                 # full run (writes all outputs)
    python eval/dat3m_lkmm_hardbench.py --controls-only # just the five calibration controls
    python eval/dat3m_lkmm_hardbench.py --jobs 12       # parallelism (default 12)

Run from the repository root. Requires a built Dat3M under eval/corpus/Dat3M
(cd eval/corpus/Dat3M && mvn clean install -DskipTests -pl dartagnan -am) and a
JDK on PATH. Runtime is about 10 minutes at --jobs 12.

------------------------------------------------------------------------------
METHOD
------------------------------------------------------------------------------
Instances.  eval/generated-hard-pairs.csv holds 3,656 rows: 1,828 candidate
expressions placed at every site of an n-thread load-buffering ring, n in {2,3}.
This script re-renders each ring exactly as HardExprBenchRun.litmus() builds it
-- thread i reads x_i and writes x_{(i+1) mod n} with the candidate expression
over its own register, under exists (/\\_i i:r_i = 1) -- but emits Dat3M's
function-body C litmus syntax  Pn(int *x0, ...) { ... }  rather than the column
format the Java driver uses internally. This is normalization (b) of
eval/dat3m-command-lines.txt, applied to generated rather than published tests.

Identifiers.  instance_id reuses HardExprBenchRun's own naming,
HARD-n{n}-{hex(java String.hashCode)}, and expression_id is EXPR-{same hex}.
Both are pure functions of the expression text and the ring size, so they are
stable across runs and machines, and every row joins back to
eval/generated-hard-pairs.csv on (expr, threads).

Representability.  Dartagnan's LKMM grammar has no bitwise-NOT token: an
expression containing '~' is rejected with a ParsingException. Those instances
are EXCLUDED rather than scored, and the exclusion is evidenced -- they are run
too, and the parser's own rejection text is recorded per instance in
eval/dat3m-lkmm-excluded.csv.

Verdict mapping.  Fixed by the five controls below, not assumed:

    Dat3M PASS = the exists clause is reachable   = ALLOWED
    Dat3M FAIL = the exists clause is unreachable = FORBIDDEN

Dartagnan prints "Reason: Program specification violation found" on FAIL. That
wording is about its own safety-property encoding (the litmus exists clause is
negated into an assertion); it does NOT mean the outcome was observed. Control 2
pins this down: a single-threaded write of 1 with exists (x0=2) is unreachable
under every model, and Dat3M reports FAIL.

Expected verdict.  The claim under test is agreement with the ALL-DEPENDENCIES
baseline, which is column `syntactic` of eval/generated-hard-pairs.csv. That
baseline forbids every retained instance by construction (it is the inclusion
filter of the benchmark), so expected_verdict is FORBIDDEN throughout. The
ground-truth column `required` and WEAKEST's own verdict are carried alongside
so the grounded-ring subset can be selected without consulting this script.

------------------------------------------------------------------------------
OUTPUTS
------------------------------------------------------------------------------
  eval/dat3m-lkmm-hardbench.csv        1,350 scored instances, one row each
  eval/dat3m-lkmm-grounded-rings.csv     390 grounded rings, one row each
  eval/dat3m-lkmm-excluded.csv         2,306 excluded instances + parser text
  eval/dat3m-lkmm-controls.txt           the five controls, sources and results
  eval/dat3m-lkmm-environment.txt        environment, commands, SHA-256 manifest
  eval/generated-dat3m/*.litmus        the 1,350 rendered Dat3M inputs
"""
import argparse
import csv
import datetime
import hashlib
import os
import re
import subprocess
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

EVAL = os.path.dirname(os.path.abspath(__file__))
DAT3M_HOME = os.path.join(EVAL, 'corpus', 'Dat3M')
JAR = os.path.join(DAT3M_HOME, 'dartagnan', 'target', 'dartagnan.jar')
CAT = os.path.join(DAT3M_HOME, 'cat', 'linux-kernel.cat')
PAIRS = os.path.join(EVAL, 'generated-hard-pairs.csv')
DUMPDIR = os.path.join(EVAL, 'generated-dat3m')

OUT_MAIN = os.path.join(EVAL, 'dat3m-lkmm-hardbench.csv')
OUT_RINGS = os.path.join(EVAL, 'dat3m-lkmm-grounded-rings.csv')
OUT_EXCL = os.path.join(EVAL, 'dat3m-lkmm-excluded.csv')
OUT_CTRL = os.path.join(EVAL, 'dat3m-lkmm-controls.txt')
OUT_ENV = os.path.join(EVAL, 'dat3m-lkmm-environment.txt')

# Expected values, from the paper. The run ABORTS if any of these moves.
EXPECT = {
    'total': 3656,
    'representable': 1350,
    'excluded': 2306,
    'agreement': 1350,
    'grounded_rings': 390,
    'grounded_exprs': 195,
    'grounded_forbidden': 390,
    'grounded_allowed': 0,
}

# ---------------------------------------------------------------- rendering --


def jhex(s):
    """Java Integer.toHexString(String.hashCode()) -- matches HardExprBenchRun."""
    h = 0
    for ch in s:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return format(h, 'x')


def ids_for(expr, n):
    """(instance_id, expression_id) -- deterministic, machine independent."""
    hx = jhex(expr)
    return 'HARD-n%d-%s' % (n, hx), 'EXPR-%s' % hx


def render(expr, n, name):
    """Dat3M function-body C litmus for HardExprBenchRun's n-thread ring."""
    params = ', '.join('int *x%d' % i for i in range(n))
    out = ['C %s' % name, '', '{ }', '']
    for i in range(n):
        out.append('P%d(%s)' % (i, params))
        out.append('{')
        out.append('    int r%d = READ_ONCE(*x%d);' % (i, i))
        out.append('    WRITE_ONCE(*x%d, %s);' % ((i + 1) % n, expr.replace('r', 'r%d' % i)))
        out.append('}')
        out.append('')
    out.append('exists (%s)' % ' /\\ '.join('%d:r%d=1' % (i, i) for i in range(n)))
    out.append('')
    return '\n'.join(out)


# ------------------------------------------------------------------ running --

RESULT_RE = re.compile(r'^Result:\s*(\S+)', re.M)
REASON_RE = re.compile(r'^Reason:\s*(.+)$', re.M)
DETAILS_RE = re.compile(r'^Details:\s*\n?\s*(.+)$', re.M)
TIME_RE = re.compile(r'^Time:\s*([0-9.]+)', re.M)


def dat3m_cmd(path):
    return ['java', '-jar', JAR, CAT, '--target=lkmm', path, '--property=program_spec']


def run_one(path, timeout_s):
    env = dict(os.environ, DAT3M_HOME=DAT3M_HOME)
    try:
        p = subprocess.run(dat3m_cmd(path), capture_output=True, text=True,
                           timeout=timeout_s, env=env)
    except subprocess.TimeoutExpired:
        return 'TIMEOUT', 'timeout after %ds' % timeout_s, '', '', -1
    blob = (p.stdout or '') + '\n' + (p.stderr or '')
    m = RESULT_RE.search(blob)
    rm = REASON_RE.search(blob)
    dm = DETAILS_RE.search(blob)
    tm = TIME_RE.search(blob)
    return (m.group(1) if m else 'NO_RESULT',
            rm.group(1).strip() if rm else '',
            dm.group(1).strip() if dm else '',
            tm.group(1) if tm else '',
            p.returncode)


def classify(res, reason):
    if res == 'PASS':
        return 'ALLOWED', 'ok'
    if res == 'FAIL':
        return 'FORBIDDEN', 'ok'
    if res == 'TIMEOUT':
        return '', 'timeout'
    if res == 'ERROR' and 'Parsing' in reason:
        return '', 'parse-failure'
    return '', 'tool-failure'


# ----------------------------------------------------------------- controls --

CONTROLS = [
    ('CTRL-reach-yes', 'ALLOWED',
     'Model-independent reachability. One thread writes x0=1 and the condition asks '
     'for x0=1. Reachable under every memory model, so whatever token Dat3M prints '
     'here is the token for ALLOWED.',
     'C CTRL-reach-yes\n\n{ }\n\nP0(int *x0)\n{\n    WRITE_ONCE(*x0, 1);\n}\n\n'
     'exists (x0=1)\n'),
    ('CTRL-reach-no', 'FORBIDDEN',
     'Model-independent unreachability. The same single write, but the condition asks '
     'for x0=2, a value no instruction ever stores. Unreachable under every memory '
     'model, so whatever token Dat3M prints here is the token for FORBIDDEN. This is '
     'the control that overrides the misleading "specification violation" wording.',
     'C CTRL-reach-no\n\n{ }\n\nP0(int *x0)\n{\n    WRITE_ONCE(*x0, 1);\n}\n\n'
     'exists (x0=2)\n'),
    ('CTRL-LB-nodep-n2', 'ALLOWED',
     'Dependency-free load-buffering ring at n=2, the smaller of the two ring sizes '
     'used by the benchmark. Each thread stores a literal, so no dependency of any '
     'kind carries the cycle and LKMM orders nothing: the outcome is allowed. Confirms '
     'the reading on the actual ring shape, not just on a one-line program.',
     'C CTRL-LB-nodep-n2\n\n{ }\n\nP0(int *x0, int *x1)\n{\n'
     '    int r0 = READ_ONCE(*x0);\n    WRITE_ONCE(*x1, 1);\n}\n\n'
     'P1(int *x0, int *x1)\n{\n    int r1 = READ_ONCE(*x1);\n'
     '    WRITE_ONCE(*x0, 1);\n}\n\nexists (0:r0=1 /\\ 1:r1=1)\n'),
    ('CTRL-LB-nodep-n3', 'ALLOWED',
     'The same dependency-free ring at n=3, the other ring size used by the benchmark. '
     'Confirms the reading does not depend on ring size.',
     'C CTRL-LB-nodep-n3\n\n{ }\n\nP0(int *x0, int *x1, int *x2)\n{\n'
     '    int r0 = READ_ONCE(*x0);\n    WRITE_ONCE(*x1, 1);\n}\n\n'
     'P1(int *x0, int *x1, int *x2)\n{\n    int r1 = READ_ONCE(*x1);\n'
     '    WRITE_ONCE(*x2, 1);\n}\n\nP2(int *x0, int *x1, int *x2)\n{\n'
     '    int r2 = READ_ONCE(*x2);\n    WRITE_ONCE(*x0, 1);\n}\n\n'
     'exists (0:r0=1 /\\ 1:r1=1 /\\ 2:r2=1)\n'),
    ('CTRL-LB-realdep-n2', 'FORBIDDEN',
     'The same ring at n=2, but each store now forwards the value just loaded, so a '
     'real syntactic data dependency carries the cycle. LKMM orders by syntactic '
     'dependency by design, so the outcome is forbidden. Together with CTRL-LB-nodep-n2 '
     'this shows Dat3M is responding to dependency content on exactly this shape, which '
     'is what makes the 1,350-instance comparison meaningful.',
     'C CTRL-LB-realdep-n2\n\n{ }\n\nP0(int *x0, int *x1)\n{\n'
     '    int r0 = READ_ONCE(*x0);\n    WRITE_ONCE(*x1, r0);\n}\n\n'
     'P1(int *x0, int *x1)\n{\n    int r1 = READ_ONCE(*x1);\n'
     '    WRITE_ONCE(*x0, r1);\n}\n\nexists (0:r0=1 /\\ 1:r1=1)\n'),
]


def run_controls(tmpdir, timeout_s, verbose=True):
    os.makedirs(tmpdir, exist_ok=True)
    rows, ok = [], True
    for name, expected, why, src in CONTROLS:
        p = os.path.join(tmpdir, name + '.litmus')
        with open(p, 'w') as fh:
            fh.write(src)
        res, reason, details, secs, rc = run_one(p, timeout_s)
        verdict, status = classify(res, reason)
        good = (verdict == expected and status == 'ok')
        ok = ok and good
        rows.append(dict(name=name, expected=expected, observed=verdict, result=res,
                         why=why, src=src, good=good, path=p))
        if verbose:
            print('  %-20s expect=%-9s dat3m=%-4s -> %-9s %s'
                  % (name, expected, res, verdict, 'OK' if good else '*** MISMATCH ***'))
    return ok, rows


def write_controls_file(rows, path, stamp):
    with open(path, 'w') as f:
        f.write('The five calibration controls for the Dat3M/LKMM hard-expression baseline\n')
        f.write('=' * 78 + '\n\n')
        f.write('Generated %s by eval/dat3m_lkmm_hardbench.py\n\n' % stamp)
        f.write('WHY THIS FILE EXISTS\n')
        f.write('-' * 78 + '\n')
        f.write('Dartagnan reports PASS or FAIL, and the mapping from those tokens to\n')
        f.write('ALLOWED / FORBIDDEN is not self-evident from its output. On FAIL it prints\n\n')
        f.write('    Reason: Program specification violation found\n\n')
        f.write('which reads as though the outcome had been observed. It has not. Dartagnan\n')
        f.write('negates the litmus exists clause into a safety assertion, so "violation\n')
        f.write('found" means the assertion could not be upheld, i.e. the exists clause is\n')
        f.write('UNREACHABLE. Reading that token the natural way inverts every verdict in\n')
        f.write('the experiment, so the mapping is established empirically below rather\n')
        f.write('than taken from the wording.\n\n')
        f.write('These five controls run before any instance is scored. If any one of them\n')
        f.write('disagrees with its expected verdict the run aborts and writes nothing.\n\n')
        f.write('CONCLUSION ESTABLISHED BY CONTROLS 1 AND 2 (model-independent)\n')
        f.write('-' * 78 + '\n')
        f.write('    Dat3M PASS = exists clause reachable   = ALLOWED\n')
        f.write('    Dat3M FAIL = exists clause unreachable = FORBIDDEN\n\n')
        f.write('Controls 3-5 then confirm that reading on the benchmark ring shape itself.\n\n')
        f.write('RESULTS\n')
        f.write('-' * 78 + '\n')
        f.write('%-22s %-11s %-11s %-7s %s\n'
                % ('control', 'expected', 'observed', 'dat3m', 'outcome'))
        f.write('-' * 78 + '\n')
        for r in rows:
            f.write('%-22s %-11s %-11s %-7s %s\n'
                    % (r['name'], r['expected'], r['observed'], r['result'],
                       'OK' if r['good'] else 'MISMATCH'))
        f.write('-' * 78 + '\n\n')
        f.write('COMMAND (identical for every control and every scored instance)\n')
        f.write('-' * 78 + '\n')
        f.write('DAT3M_HOME=eval/corpus/Dat3M \\\n')
        f.write('java -jar eval/corpus/Dat3M/dartagnan/target/dartagnan.jar \\\n')
        f.write('     eval/corpus/Dat3M/cat/linux-kernel.cat \\\n')
        f.write('     --target=lkmm <CONTROL>.litmus --property=program_spec\n\n\n')
        f.write('EACH CONTROL: RATIONALE, SOURCE, OBSERVED RESULT\n')
        f.write('=' * 78 + '\n')
        for i, r in enumerate(rows, 1):
            f.write('\n%d. %s\n' % (i, r['name']))
            f.write('-' * 78 + '\n')
            f.write('Rationale.\n')
            for line in _wrap(r['why'], 76):
                f.write('  %s\n' % line)
            f.write('\nExpected: %s     Dat3M printed: %s     Interpreted as: %s     %s\n'
                    % (r['expected'], r['result'], r['observed'],
                       'OK' if r['good'] else 'MISMATCH'))
            f.write('\nLitmus source:\n')
            for line in r['src'].rstrip('\n').split('\n'):
                f.write('  | %s\n' % line)
            f.write('\n')
        f.write('\nThe litmus source above is regenerated verbatim from CONTROLS in\n')
        f.write('eval/dat3m_lkmm_hardbench.py on every run; the files themselves are\n')
        f.write('written to eval/generated-dat3m/_controls/.\n')


def _wrap(s, w):
    words, line, out = s.split(), '', []
    for word in words:
        if len(line) + len(word) + 1 > w:
            out.append(line)
            line = word
        else:
            line = (line + ' ' + word).strip()
    if line:
        out.append(line)
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def write_env_file(path, env, elapsed, jobs, timeout_s, stamp, outputs, counts):
    with open(path, 'w') as f:
        f.write('Environment and provenance for the Dat3M/LKMM hard-expression baseline\n')
        f.write('=' * 78 + '\n\n')
        f.write('Generated %s by eval/dat3m_lkmm_hardbench.py\n\n' % stamp)
        f.write('TOOL AND MODEL\n')
        f.write('-' * 78 + '\n')
        for k, v in env:
            f.write('%-24s %s\n' % (k + ':', v))
        f.write('%-24s %d worker processes\n' % ('parallelism:', jobs))
        f.write('%-24s %d s per instance (never reached)\n' % ('per-instance timeout:', timeout_s))
        f.write('%-24s %.1f s wall clock for all 3,656 invocations\n' % ('total runtime:', elapsed))
        f.write('\n\nINPUTS\n')
        f.write('-' * 78 + '\n')
        f.write('%-24s %s\n' % ('instance source:', 'eval/generated-hard-pairs.csv'))
        f.write('%-24s %s\n' % ('  sha256:', sha256(PAIRS)))
        f.write('%-24s %s\n' % ('  rows consumed:', counts['total']))
        f.write('%-24s %s\n' % ('generator/driver:', 'eval/dat3m_lkmm_hardbench.py'))
        f.write('%-24s %s\n' % ('  sha256:', sha256(os.path.abspath(__file__))))
        f.write('%-24s %s\n' % ('rendered inputs:', 'eval/generated-dat3m/*.litmus'))
        f.write('\n\nCOMMAND LINE, PER INSTANCE\n')
        f.write('-' * 78 + '\n')
        f.write('DAT3M_HOME=eval/corpus/Dat3M \\\n')
        f.write('java -jar eval/corpus/Dat3M/dartagnan/target/dartagnan.jar \\\n')
        f.write('     eval/corpus/Dat3M/cat/linux-kernel.cat \\\n')
        f.write('     --target=lkmm \\\n')
        f.write('     eval/generated-dat3m/<INSTANCE_ID>.litmus \\\n')
        f.write('     --property=program_spec\n\n')
        f.write('<INSTANCE_ID> is HARD-n{n}-{hex(java String.hashCode of the expression)},\n')
        f.write('the identifier HardExprBenchRun gives the instance. Rows therefore join to\n')
        f.write('eval/generated-hard-pairs.csv on (expr, threads).\n\n')
        f.write('linux-kernel.cat with --target=lkmm is used because LKMM is the deployed\n')
        f.write('model whose ordering is syntactic-dependency based; IMM and RC11 reject\n')
        f.write('READ_ONCE/WRITE_ONCE programs outright. This is the same model and the same\n')
        f.write('invocation shape as the 41-test cross-check recorded in\n')
        f.write('eval/dat3m-command-lines.txt.\n\n\n')
        f.write('REPRODUCE\n')
        f.write('-' * 78 + '\n')
        f.write('python eval/dat3m_lkmm_hardbench.py\n\n')
        f.write('Renders the inputs, runs the five controls, runs all 3,656 instances and\n')
        f.write('rewrites every output below. It aborts rather than write partial evidence\n')
        f.write('if a control mismatches or if any expected count fails to reproduce.\n\n\n')
        f.write('OUTPUT MANIFEST (SHA-256, computed at generation time)\n')
        f.write('-' * 78 + '\n')
        f.write('Recomputing these digests detects any post-hoc edit of the result files.\n\n')
        for p, desc in outputs:
            if os.path.isfile(p):
                rel = 'eval/' + os.path.basename(p)
                f.write('%s\n  %s\n  %s\n\n' % (rel, desc, sha256(p)))
        f.write('\nTimestamps above are provenance only and carry no scientific weight.\n')


# --------------------------------------------------------------------- main --

MAIN_COLS = ['instance_id', 'expression_id', 'expression', 'ring_size', 'shape',
             'source_file', 'oracle_constant', 'ground_truth_required', 'weakest_verdict',
             'expected_verdict', 'dat3m_result', 'dat3m_verdict', 'agreement',
             'status', 'dat3m_secs', 'exit_code', 'diagnostic']
EXCL_COLS = ['instance_id', 'expression_id', 'expression', 'ring_size',
             'dat3m_result', 'status', 'reason', 'parser_error']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jobs', type=int, default=12)
    ap.add_argument('--timeout', type=int, default=300)
    ap.add_argument('--controls-only', action='store_true')
    a = ap.parse_args()

    for p, what in ((JAR, 'dartagnan.jar'), (CAT, 'linux-kernel.cat'),
                    (PAIRS, 'generated-hard-pairs.csv')):
        if not os.path.isfile(p):
            sys.exit('missing %s at %s\n(build Dat3M: cd eval/corpus/Dat3M && '
                     'mvn clean install -DskipTests -pl dartagnan -am)' % (what, p))

    stamp = datetime.datetime.now().astimezone().strftime('%Y-%m-%dT%H:%M:%S%z')
    jver = subprocess.run(['java', '-version'], capture_output=True, text=True)
    java_version = (jver.stderr or jver.stdout).strip().splitlines()[0]
    d3m_commit = subprocess.run(['git', '-C', DAT3M_HOME, 'rev-parse', 'HEAD'],
                                capture_output=True, text=True).stdout.strip() or 'unknown'

    print('=' * 74)
    print('THE FIVE CALIBRATION CONTROLS')
    print('=' * 74)
    ok, ctrl_rows = run_controls(os.path.join(DUMPDIR, '_controls'), a.timeout)
    if not ok:
        sys.exit('\nABORT: a calibration control disagreed with its expected verdict.\n'
                 'The PASS/FAIL reading is not what this script assumes; nothing written.')
    print('  all five agree -- PASS=ALLOWED, FAIL=FORBIDDEN confirmed\n')
    if a.controls_only:
        write_controls_file(ctrl_rows, OUT_CTRL, stamp)
        print('wrote', OUT_CTRL)
        return

    rows = list(csv.DictReader(open(PAIRS)))
    print('=' * 74)
    print('RENDERING AND RUNNING %d INSTANCES' % len(rows))
    print('=' * 74)
    os.makedirs(DUMPDIR, exist_ok=True)

    jobs = []
    for r in rows:
        expr, n = r['expr'], int(r['threads'])
        iid, eid = ids_for(expr, n)
        p = os.path.join(DUMPDIR, iid + '.litmus')
        with open(p, 'w') as fh:
            fh.write(render(expr, n, iid))
        jobs.append((r, iid, eid, p))

    done = [0]
    t0 = time.time()

    def work(j):
        r, iid, eid, p = j
        res, reason, details, secs, rc = run_one(p, a.timeout)
        verdict, status = classify(res, reason)
        done[0] += 1
        if done[0] % 250 == 0:
            print('  %d/%d' % (done[0], len(jobs)), flush=True)
        return {
            'instance_id': iid,
            'expression_id': eid,
            'expression': r['expr'],
            'ring_size': r['threads'],
            'shape': r['shape'],
            'source_file': 'eval/generated-dat3m/%s.litmus' % iid,
            'oracle_constant': r['oracle_constant'],
            'ground_truth_required': r['required'],
            'weakest_verdict': r['weakest'],
            'expected_verdict': r['syntactic'],
            'dat3m_result': res,
            'dat3m_verdict': verdict,
            'agreement': str(verdict == r['syntactic']).lower(),
            'status': status,
            'dat3m_secs': secs,
            'exit_code': rc,
            'diagnostic': '' if status == 'ok' else (reason or details)[:200],
            'reason': reason,
            'parser_error': details[:300],
        }

    with ThreadPoolExecutor(max_workers=a.jobs) as ex:
        out = list(ex.map(work, jobs))
    elapsed = time.time() - t0
    out.sort(key=lambda d: (d['expression'], int(d['ring_size'])))

    rep = [d for d in out if '~' not in d['expression']]
    exc = [d for d in out if '~' in d['expression']]
    grounded = [d for d in rep if d['ground_truth_required'] == 'ALLOWED']
    agree = sum(1 for d in rep if d['agreement'] == 'true')
    gforb = sum(1 for d in grounded if d['dat3m_verdict'] == 'FORBIDDEN')
    gallow = sum(1 for d in grounded if d['dat3m_verdict'] == 'ALLOWED')
    gexpr = len(set(d['expression'] for d in grounded))
    st = Counter(d['status'] for d in rep)

    print('\n' + '=' * 74)
    print('VERIFICATION')
    print('=' * 74)
    checks = [
        ('total instances', len(out), EXPECT['total']),
        ('representable (scored)', len(rep), EXPECT['representable']),
        ('excluded by the LKMM parser', len(exc), EXPECT['excluded']),
        ('scored + excluded', len(rep) + len(exc), EXPECT['total']),
        ('every excluded one is a parse failure',
         sum(1 for d in exc if d['status'] == 'parse-failure'), EXPECT['excluded']),
        ('timeouts in the scored set', st.get('timeout', 0), 0),
        ('tool failures in the scored set', st.get('tool-failure', 0), 0),
        ('parse failures in the scored set', st.get('parse-failure', 0), 0),
        ('agreement with all-dependencies', agree, EXPECT['agreement']),
        ('mismatches', len(rep) - agree, 0),
        ('grounded rings', len(grounded), EXPECT['grounded_rings']),
        ('distinct grounded expressions', gexpr, EXPECT['grounded_exprs']),
        ('grounded rings Dat3M forbids', gforb, EXPECT['grounded_forbidden']),
        ('grounded rings Dat3M allows', gallow, EXPECT['grounded_allowed']),
    ]
    allok = True
    for label, got, want in checks:
        good = got == want
        allok = allok and good
        print('  %-42s %6s  [expect %s] %s'
              % (label, got, want, 'OK' if good else '*** MISMATCH ***'))
    if not allok:
        sys.exit('\nABORT: the run did not reproduce the reported numbers. Nothing written.\n'
                 'Investigate before changing anything.')

    def dump(path, rowset, cols):
        with open(path, 'w', newline='') as fh:
            w = csv.DictWriter(fh, fieldnames=cols, extrasaction='ignore')
            w.writeheader()
            w.writerows(rowset)
        print('wrote %-46s %d rows' % ('eval/' + os.path.basename(path), len(rowset)))

    print()
    dump(OUT_MAIN, rep, MAIN_COLS)
    dump(OUT_RINGS, grounded, MAIN_COLS)
    dump(OUT_EXCL, exc, EXCL_COLS)
    write_controls_file(ctrl_rows, OUT_CTRL, stamp)
    print('wrote eval/' + os.path.basename(OUT_CTRL))

    # Excluded instances are evidence of exclusion, not shipped inputs.
    for d in exc:
        p = os.path.join(DUMPDIR, d['instance_id'] + '.litmus')
        if os.path.isfile(p):
            os.remove(p)

    write_env_file(OUT_ENV, [
        ('Dat3M version', '4.4.1'),
        ('Dat3M commit', d3m_commit),
        ('Dat3M home', 'eval/corpus/Dat3M'),
        ('model', 'cat/linux-kernel.cat'),
        ('target', 'lkmm'),
        ('property', 'program_spec'),
        ('Java', java_version),
        ('platform', sys.platform),
    ], elapsed, a.jobs, a.timeout, stamp, [
        (OUT_MAIN, '%d scored instances, one row each' % len(rep)),
        (OUT_RINGS, '%d grounded rings, one row each' % len(grounded)),
        (OUT_EXCL, '%d excluded instances with the parser rejection text' % len(exc)),
        (OUT_CTRL, 'the five calibration controls, sources and results'),
    ], {'total': len(out)})
    print('wrote eval/' + os.path.basename(OUT_ENV))
    print('kept %d rendered inputs in eval/generated-dat3m/' % len(rep))
    print('\nall checks reproduced; runtime %.1f s' % elapsed)


if __name__ == '__main__':
    main()
