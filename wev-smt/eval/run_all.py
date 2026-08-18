#!/usr/bin/env python
"""
run_all.py — one-command reproduction driver for the WEV-SMT evaluation (C4-2).

Runs every experiment the paper cites, collects the raw numbers into
``eval/results.json``, and regenerates ``eval/numbers.tex`` (\\newcommand macros for
every inline number) and ``eval/tables.tex`` (bodies for Tables 1-4).

    python eval/run_all.py                # full run
    python eval/run_all.py --quick        # skip the long scalability sweep
    python eval/run_all.py --only jmm20 published34
    python eval/run_all.py --skip scalability corpus_symbolic

Requires ``mvn -q test-compile`` at least once (compiles classes and copies the Z3
natives into ``target/native``); this script regenerates the cached dependency
classpath if it is missing. Invoke with ``python`` — on Git-Bash for Windows the
``python3`` name resolves to the non-functional Microsoft-Store stub.

Every number written to results.json carries a ``source`` field: ``run`` (produced by a
Java process this script launched) or ``artifact`` (derived from a committed CSV that
this run did not regenerate). Nothing is hand-typed.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

# ── Layout ────────────────────────────────────────────────────────────────────

EVAL = Path(__file__).resolve().parent
ROOT = EVAL.parent
TARGET = ROOT / "target"
CP_FILE = TARGET / "cp.txt"
NATIVE_DIR = TARGET / "native"
CLASSES = TARGET / "classes"

MANIFEST_V2 = EVAL / "corpus-weakest-manifest-v2.csv"
CORPUS_ROOT = EVAL / "corpus"
EXAMPLES = EVAL / "examples"

RESULTS_JSON = EVAL / "results.json"
NUMBERS_TEX = EVAL / "numbers.tex"
TABLES_TEX = EVAL / "tables.tex"

SEP = ";" if os.name == "nt" else ":"

# Corpus provenance — fixed inputs, not measurements.
CORPUS_FILES_TOTAL = 12324

# ── Process plumbing ──────────────────────────────────────────────────────────


def log(msg: str) -> None:
    print(msg, flush=True)


def ensure_build() -> None:
    """Make sure target/classes, target/native and target/cp.txt exist."""
    if not CLASSES.is_dir() or not NATIVE_DIR.is_dir():
        log("[build] target/classes or target/native missing — running 'mvn -q test-compile'")
        mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
        if not mvn:
            sys.exit("mvn not on PATH; run 'mvn -q test-compile' manually first")
        subprocess.run([mvn, "-q", "test-compile"], cwd=ROOT, check=True)
    if not CP_FILE.is_file():
        log(f"[build] regenerating dependency classpath ({CP_FILE})")
        mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
        if not mvn:
            sys.exit("mvn not on PATH; cannot build the dependency classpath")
        subprocess.run(
            [mvn, "-q", "dependency:build-classpath", f"-Dmdep.outputFile={CP_FILE}"],
            cwd=ROOT,
            check=True,
        )


def classpath() -> str:
    return f"{CLASSES}{SEP}{CP_FILE.read_text(encoding='utf-8').strip()}"


def run_java(main_class: str, *args: str, timeout: int = 7200) -> tuple[int, str, float]:
    """Run a CLI main class with the Z3 native path set. Returns (rc, output, seconds)."""
    cmd = [
        "java",
        f"-Djava.library.path={NATIVE_DIR}",
        "-cp",
        classpath(),
        main_class,
        *[str(a) for a in args],
    ]
    log(f"    $ java ... {main_class} {' '.join(str(a) for a in args)}")
    t0 = time.time()
    try:
        proc = subprocess.run(
            cmd,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return 124, f"TIMEOUT after {timeout}s", time.time() - t0
    return proc.returncode, (proc.stdout or "") + (proc.stderr or ""), time.time() - t0


def read_csv_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def read_manifest(path: Path) -> list[str]:
    """Manifest lines are ``<relative-path>[,<verdict>]``; return the paths."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s:
            out.append(s.split(",", 1)[0])
    return out


def frac(num: int, den: int) -> str:
    return f"{num}/{den}"


# ── Experiments ───────────────────────────────────────────────────────────────
#
# Each step function takes the accumulated results dict and returns its own result
# dict. A step that raises is recorded as failed and does not abort the run.


def step_corpus_symbolic(res: dict) -> dict:
    """SymbolicRfRun over the full corrected 2,972-test manifest (wired vs symbolic)."""
    out_csv = EVAL / "symbolic-rf-results.csv"
    rc, out, secs = run_java(
        "wev.smt.cli.SymbolicRfRun", "corpus", MANIFEST_V2, CORPUS_ROOT, out_csv
    )
    if rc != 0:
        raise RuntimeError(f"SymbolicRfRun corpus exited {rc}\n{out[-3000:]}")

    rows = read_csv_rows(out_csv)
    changes = [r for r in rows if r["change"] == "true"]
    a2f = [r for r in changes if r["symbolic_verdict"] == "FORBIDDEN"]
    f2a = [r for r in changes if r["symbolic_verdict"] == "ALLOWED"]
    errors = [r for r in rows if r["symbolic_verdict"] == "ERROR"]
    skipped = [r for r in rows if r["symbolic_verdict"] == "SKIP"]
    times = sorted(int(r["runtime_ms"]) for r in rows if r["runtime_ms"].isdigit())
    solved = [t for t in times if t > 0] or times

    return {
        "source": "run",
        "csv": str(out_csv.relative_to(ROOT)),
        "wall_seconds": round(secs, 1),
        "tests": len(rows),
        "verdict_changes": len(changes),
        "allowed_to_forbidden": len(a2f),
        "forbidden_to_allowed": len(f2a),
        "errors": len(errors),
        "skipped": len(skipped),
        "symbolic_ms_median": int(statistics.median(solved)) if solved else 0,
        "symbolic_ms_p95": solved[int(len(solved) * 0.95)] if solved else 0,
        "symbolic_ms_max": max(solved) if solved else 0,
        "symbolic_ms_mean": round(statistics.mean(solved), 1) if solved else 0.0,
    }


def step_ladder(res: dict) -> dict:
    """The 9-step C1 symbolic validation ladder."""
    rc, out, secs = run_java("wev.smt.cli.SymbolicRfRun", "ladder")
    if rc != 0:
        raise RuntimeError(f"SymbolicRfRun ladder exited {rc}\n{out[-3000:]}")
    passed = failed = 0
    for line in out.splitlines():
        if line.strip().startswith("ladder:"):
            parts = line.replace("ladder:", "").replace("PASS", "").replace("FAIL", "")
            nums = [int(p) for p in parts.replace("/", " ").split() if p.isdigit()]
            if len(nums) >= 2:
                passed, failed = nums[0], nums[1]
    return {
        "source": "run",
        "wall_seconds": round(secs, 1),
        "passed": passed,
        "failed": failed,
        "total": passed + failed,
    }


def _run_examples_symbolic(rel_paths: list[str], tag: str) -> dict[str, dict[str, str]]:
    """Run SymbolicRfRun over an ad-hoc manifest of example files.

    Returns ``{test-key: {"wired": V, "symbolic": V, "ms": n}}`` keyed by the
    ``<suite>/<name>`` form used by the comparison CSVs.
    """
    tmp_manifest = EVAL / f".{tag}-manifest.tmp.csv"
    tmp_out = EVAL / f".{tag}-symbolic.tmp.csv"
    tmp_manifest.write_text("\n".join(rel_paths) + "\n", encoding="utf-8")
    try:
        rc, out, _ = run_java(
            "wev.smt.cli.SymbolicRfRun", "corpus", tmp_manifest, ROOT, tmp_out
        )
        if rc != 0:
            raise RuntimeError(f"SymbolicRfRun corpus ({tag}) exited {rc}\n{out[-3000:]}")
        verdicts = {}
        for r in read_csv_rows(tmp_out):
            # eval/examples/jmm/TC01.litmus -> jmm/TC01
            key = r["test"].replace("\\", "/")
            key = key[len("eval/examples/"):] if key.startswith("eval/examples/") else key
            key = key[: -len(".litmus")] if key.endswith(".litmus") else key
            verdicts[key] = {
                "wired": r["wired_verdict"],
                "symbolic": r["symbolic_verdict"],
                "ms": r["runtime_ms"],
            }
        return verdicts
    finally:
        for p in (tmp_manifest, tmp_out):
            p.unlink(missing_ok=True)


def step_examples(res: dict) -> dict:
    """Run all 41 published/Dat3M example programs once; later steps join against this."""
    ref = read_csv_rows(EVAL / "dat3m-comparison-v2.csv")
    keys = [r["test"] for r in ref]
    paths = [f"eval/examples/{k}.litmus" for k in keys]
    missing = [p for p in paths if not (ROOT / p).is_file()]
    if missing:
        raise RuntimeError(f"missing example files: {missing}")
    verdicts = _run_examples_symbolic(paths, "examples41")
    return {"source": "run", "count": len(verdicts), "verdicts": verdicts}


def step_dat3m41(res: dict) -> dict:
    """41-test Dat3M cross-check: WEV (wired and symbolic) vs the Dat3M/LKMM reference."""
    verdicts = res["examples"]["verdicts"]
    ref = read_csv_rows(EVAL / "dat3m-comparison-v2.csv")

    per_suite: dict[str, dict[str, int]] = {}
    wired_agree = symbolic_agree = 0
    flips = []
    for r in ref:
        key, expected = r["test"], r["dat3m_verdict"]
        v = verdicts[key]
        suite = r["suite"]
        bucket = per_suite.setdefault(suite, {"total": 0, "wired": 0, "symbolic": 0})
        bucket["total"] += 1
        if v["wired"] == expected:
            wired_agree += 1
            bucket["wired"] += 1
        if v["symbolic"] == expected:
            symbolic_agree += 1
            bucket["symbolic"] += 1
        if v["wired"] != v["symbolic"]:
            flips.append(
                {
                    "test": key,
                    "wired": v["wired"],
                    "symbolic": v["symbolic"],
                    "reference": expected,
                    "toward_reference": v["symbolic"] == expected,
                }
            )

    return {
        "source": "run",
        "total": len(ref),
        "wired_agreement": wired_agree,
        "symbolic_agreement": symbolic_agree,
        "divergences": len(ref) - symbolic_agree,
        "flips": flips,
        "per_suite": per_suite,
    }


def step_jmm20(res: dict) -> dict:
    """JMM causality suite TC01-TC20 against the Pugh classifications."""
    verdicts = res["examples"]["verdicts"]
    ref = read_csv_rows(EVAL / "jmm-results.csv")

    wired_agree = symbolic_agree = 0
    divergences = []
    for r in ref:
        key = f"jmm/{r['test']}"
        expected = r["jmm_verdict"]
        v = verdicts[key]
        if v["wired"] == expected:
            wired_agree += 1
        if v["symbolic"] == expected:
            symbolic_agree += 1
        else:
            divergences.append(
                {
                    "test": r["test"],
                    "jmm": expected,
                    "wev_symbolic": v["symbolic"],
                    "cause": r.get("divergence_cause", "") or r.get("wev_mechanism", ""),
                }
            )

    return {
        "source": "run",
        "total": len(ref),
        "wired_agreement": wired_agree,
        "symbolic_agreement": symbolic_agree,
        "divergences": divergences,
    }


def step_published34(res: dict) -> dict:
    """Published-example validation: Weakestmo (8) + PwT (6) + JMM (20) = 34.

    The Weakestmo and PwT classifications live in source-paper-results.csv; the JMM
    labels live in jmm-results.csv (the file the TC18 provenance correction landed
    in), so the 34 examples are assembled from both.
    """
    verdicts = res["examples"]["verdicts"]

    entries: list[tuple[str, str, str]] = []  # (source, key, expected)
    for r in read_csv_rows(EVAL / "source-paper-results.csv"):
        entries.append((r["source"], f"{r['source']}/{r['test']}", r["source_verdict"]))
    for r in read_csv_rows(EVAL / "jmm-results.csv"):
        entries.append(("jmm", f"jmm/{r['test']}", r["jmm_verdict"]))

    per_source: dict[str, dict] = {}
    divergences = []

    for src, key, expected in entries:
        v = verdicts[key]
        bucket = per_source.setdefault(src, {"total": 0, "wired": 0, "symbolic": 0})
        bucket["total"] += 1
        if v["wired"] == expected:
            bucket["wired"] += 1
        if v["symbolic"] == expected:
            bucket["symbolic"] += 1
        else:
            divergences.append(
                {"test": key, "source": src, "expected": expected, "got": v["symbolic"]}
            )

    total = sum(b["total"] for b in per_source.values())
    wired = sum(b["wired"] for b in per_source.values())
    symbolic = sum(b["symbolic"] for b in per_source.values())
    return {
        "source": "run",
        "total": total,
        "wired_agreement": wired,
        "symbolic_agreement": symbolic,
        "per_source": per_source,
        "divergences": divergences,
    }


def step_ra_atlas(res: dict) -> dict:
    """40-test RA formal atlas: RA_FORMAL vs the encoded RA, plus the SC->WEAKEST chain."""
    rc, out, secs = run_java("wev.smt.cli.RaFormalAtlasRun", EVAL)
    if rc != 0:
        raise RuntimeError(f"RaFormalAtlasRun exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(EVAL / "ra-formal-atlas.csv")
    matches = sum(1 for r in rows if r["raFormalMatchesRA"] == "true")

    def implies(weaker: str, stronger: str) -> bool:
        return weaker != "FORBIDDEN" or stronger == "FORBIDDEN"

    # The full chainOK column includes the RA >= WEAKEST rung, which Prop. ra-weakest
    # says does NOT hold by construction: real-dependency LB is RA-ALLOWED and
    # WEAKEST-FORBIDDEN. Those rows are the proposition's witnesses, not encoder bugs,
    # so they are counted separately from the sub-chain that is claimed to hold.
    subchain_violations = [
        r["litmus"]
        for r in rows
        if not (implies(r["RA_FORMAL"], r["TSO"]) and implies(r["TSO"], r["SC"]))
    ]
    ra_weakest_witnesses = [
        r["litmus"] for r in rows if r["RA_FORMAL"] == "ALLOWED" and r["WEAKEST"] == "FORBIDDEN"
    ]
    return {
        "source": "run",
        "csv": "eval/ra-formal-atlas.csv",
        "wall_seconds": round(secs, 1),
        "tests": len(rows),
        "ra_formal_matches_ra": matches,
        "ra_formal_divergences": len(rows) - matches,
        "subchain_violations": len(subchain_violations),
        "subchain_violation_tests": subchain_violations,
        "ra_weakest_witnesses": len(ra_weakest_witnesses),
        "ra_weakest_witness_tests": ra_weakest_witnesses,
    }


def step_rc11_direction(res: dict) -> dict:
    """RC11 direction check — the Corollary 'RC11 subsumes jfCoherence' evidence."""
    out_csv = EVAL / "rc11-direction-check.csv"
    rc, out, secs = run_java("wev.smt.cli.Rc11DirectionCheck", out_csv)
    if rc != 0:
        raise RuntimeError(f"Rc11DirectionCheck exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(out_csv)
    agree = sum(1 for r in rows if r["direction"] == "agree")
    rc11_forbids = [
        r["test"] for r in rows if r["direction"] == "rc11_forbids_weakest_allows"
    ]
    reverse = [
        r
        for r in rows
        if r["rc11_verdict"] == "ALLOWED" and r["weakest_verdict"] == "FORBIDDEN"
    ]
    return {
        "source": "run",
        "csv": "eval/rc11-direction-check.csv",
        "wall_seconds": round(secs, 1),
        "tests": len(rows),
        "agree": agree,
        "rc11_forbids_weakest_allows": len(rc11_forbids),
        "rc11_forbids_weakest_allows_tests": rc11_forbids,
        "weakest_forbids_rc11_allows": len(reverse),
    }


def step_nthread(res: dict) -> dict:
    """n-thread grounded LB admission, n = 2..8 (Proposition: grounded LB admission)."""
    rc, out, secs = run_java("wev.smt.cli.NThreadGroundedAdmission", EVAL, "2", "8")
    if rc != 0:
        raise RuntimeError(f"NThreadGroundedAdmission exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(EVAL / "nthread-grounded-admission.csv")
    allowed = sum(1 for r in rows if r["verdict"] == "ALLOWED")
    ns = [int(r["n"]) for r in rows]
    return {
        "source": "run",
        "csv": "eval/nthread-grounded-admission.csv",
        "wall_seconds": round(secs, 1),
        "n_min": min(ns),
        "n_max": max(ns),
        "rows": len(rows),
        "allowed": allowed,
        "all_allowed": allowed == len(rows),
        "max_events": max(int(r["events"]) for r in rows),
    }


def step_pwt(res: dict) -> dict:
    """PwT equivalence sweep: free-valuation sdep vs PwT's predicate transformer."""
    out_csv = EVAL / "pwt-equivalence-sweep.csv"
    rc, out, secs = run_java(
        "wev.smt.cli.PwtEquivalenceSweep", MANIFEST_V2, CORPUS_ROOT, out_csv
    )
    if rc != 0:
        raise RuntimeError(f"PwtEquivalenceSweep exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(out_csv)
    agree = sum(1 for r in rows if r["agree"] == "true")
    return {
        "source": "run",
        "csv": "eval/pwt-equivalence-sweep.csv",
        "wall_seconds": round(secs, 1),
        "pairs": len(rows),
        "agree": agree,
        "disagree": len(rows) - agree,
    }


def step_pso_canonical(res: dict) -> dict:
    """PSO' (encoder) vs canonical SPARC PSO over the atlas."""
    out_csv = EVAL / "pso-canonical-comparison.csv"
    rc, out, secs = run_java("wev.smt.cli.PsoCanonicalComparison", out_csv)
    if rc != 0:
        raise RuntimeError(f"PsoCanonicalComparison exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(out_csv)
    differs = [r["test"] for r in rows if r["differs"] == "true"]
    return {
        "source": "run",
        "csv": "eval/pso-canonical-comparison.csv",
        "wall_seconds": round(secs, 1),
        "tests": len(rows),
        "divergences": len(differs),
        "divergence_tests": differs,
    }


def step_qfbv_detector(res: dict) -> dict:
    """QF_BV constancy oracle vs the solver-free detector over the whole fragment."""
    out_csv = EVAL / "qfbv-detector-results.csv"
    rc, out, secs = run_java(
        "wev.smt.cli.QfbvDetectorRun",
        MANIFEST_V2,
        CORPUS_ROOT,
        out_csv,
        EVAL / "qfbv-summary.md",
        timeout=3600,
    )
    if rc != 0:
        raise RuntimeError(f"QfbvDetectorRun exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(out_csv)
    changed = [r["test"] for r in rows if r["changed"] == "true"]
    return {
        "source": "run",
        "csv": "eval/qfbv-detector-results.csv",
        "wall_seconds": round(secs, 1),
        "tests": len(rows),
        "changed": len(changed),
        "changed_tests": changed[:20],
    }


def step_detector_oracle_audit(res: dict) -> dict:
    """Differential detector audit — the permanent regression gate.

    This audit checks that the shipped detector and the exact SMT oracle agree on every
    audited expression. Any divergence fails the build.

    Two divergence directions are distinguished, because they are not equivalent:

      UNSOUND      detector says FAKE, oracle proves the expression VARIES. A genuinely
                   value-carrying dependency has been dropped from sdep; removing an edge
                   can break a jf-union-sdep cycle and turn FORBIDDEN into ALLOWED.
                   Non-zero => this step raises and the run fails.
      CONSERVATIVE detector says REAL, oracle proves the expression is CONSTANT. The
                   documented behaviour of the solver-free fallback, which bails to REAL on
                   anything the linear pass cannot normalise. Sound, so it does not fail the
                   run; the count is recorded so a precision regression is still visible.

    The audited population is (a) a deterministic enumeration of expressions over the
    modelled operators, plus the pinned witnesses of the 2026-07-28 folding defect, and
    (b) every (expr, reg) pair the parser actually hands to classify() while parsing the
    supported-syntax fragment.

    A faster subset of the same audit runs on every `mvn test` as
    wev.smt.parse.DetectorOracleAuditTest.
    """
    out_csv = EVAL / "detector-oracle-audit.csv"
    rc, out, secs = run_java(
        "wev.smt.cli.DetectorOracleAudit",
        MANIFEST_V2,
        CORPUS_ROOT,
        out_csv,
        "--limit",
        "12000",
        timeout=3600,
    )
    rows = read_csv_rows(out_csv) if out_csv.is_file() else []
    unsound = [r for r in rows if r["kind"] == "UNSOUND"]
    conservative = [r for r in rows if r["kind"] == "CONSERVATIVE"]

    def grab(label: str) -> int:
        for line in out.splitlines():
            if label in line:
                try:
                    return int(line.rsplit(":", 1)[1].strip())
                except (ValueError, IndexError):
                    return -1
        return -1

    if rc != 0:
        raise RuntimeError(
            f"DetectorOracleAudit FAILED (exit {rc}): {len(unsound)} unsound divergence(s).\n"
            "The detector classified a genuinely value-carrying dependency as fake.\n"
            + "\n".join(f"  {r['expr']}  wrt {r['reg']}  [{r['origin']}]" for r in unsound[:20])
            + f"\n{out[-3000:]}"
        )
    return {
        "source": "run",
        "csv": "eval/detector-oracle-audit.csv",
        "wall_seconds": round(secs, 1),
        "enumerated_checked": grab("enumerated expressions checked"),
        "corpus_pairs_checked": grab("corpus (expr,reg) pairs checked"),
        "total_audited": grab("total audited"),
        "outside_grammar": grab("outside the oracle grammar"),
        "strict_equality_mismatches": len(rows),
        "conservative": len(conservative),
        "unsound": len(unsound),
    }


def step_detector_audit(res: dict) -> dict:
    """Detector quality sweep: every realized dependency edge, classified."""
    rc, out, secs = run_java(
        "wev.smt.cli.DetectorQualitySweep", MANIFEST_V2, CORPUS_ROOT, EVAL, timeout=3600
    )
    if rc != 0:
        raise RuntimeError(f"DetectorQualitySweep exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(EVAL / "detector-quality-report.csv")
    fake = sum(1 for r in rows if r["classification"] == "FAKE")
    semantic = sum(1 for r in rows if r["classification"] == "SEMANTIC")
    relevant = sum(1 for r in rows if r["verdict_relevant"] == "true")
    return {
        "source": "run",
        "csv": "eval/detector-quality-report.csv",
        "wall_seconds": round(secs, 1),
        "edges": len(rows),
        "fake_edges": fake,
        "semantic_edges": semantic,
        "verdict_relevant_edges": relevant,
    }


def step_scalability(res: dict) -> dict:
    """Four parametric families, n = 2..16, all five models."""
    rc, out, secs = run_java(
        "wev.smt.cli.ScalabilitySweep", EVAL, "30", "60", "day9", timeout=3600
    )
    if rc != 0:
        raise RuntimeError(f"ScalabilitySweep exited {rc}\n{out[-3000:]}")
    return _scalability_from_csv(secs, "run")


def _scalability_from_csv(secs: float | None, source: str) -> dict:
    rows = read_csv_rows(EVAL / "scalability-consistency.csv")
    ns = sorted({int(r["n"]) for r in rows})
    families: dict[str, dict] = {}
    for r in rows:
        fam = families.setdefault(
            r["family"], {"by_n": {}, "verdict": None, "events_max": 0}
        )
        n = int(r["n"])
        fam["by_n"].setdefault(n, {})[r["model"]] = {
            "verdict": r["verdict"],
            "ms": int(r["fullexec_ms"]),
            "events": int(r["events"]),
        }
        if int(r["events"]) > fam["events_max"]:
            fam["events_max"] = int(r["events"])
        if r["model"] == "WEAKEST" and n == max(ns):
            fam["verdict"] = r["verdict"]

    n_max = max(ns)
    at_max = {}
    for name, fam in families.items():
        cells = fam["by_n"].get(n_max, {})
        weak = cells.get("WEAKEST", {})
        pso_ra = [cells[m]["ms"] for m in ("PSO", "RA") if m in cells]
        at_max[name] = {
            "events": weak.get("events", fam["events_max"]),
            "verdict": weak.get("verdict"),
            "SC": cells.get("SC", {}).get("ms"),
            "TSO": cells.get("TSO", {}).get("ms"),
            "WEAKEST": weak.get("ms"),
            "PSO_RA_max": max(pso_ra) if pso_ra else None,
        }

    weakest_ms = [
        v["ms"]
        for fam in families.values()
        for cells in fam["by_n"].values()
        for m, v in cells.items()
        if m == "WEAKEST"
    ]
    out = {
        "source": source,
        "csv": "eval/scalability-consistency.csv",
        "families": sorted(families),
        "n_values": ns,
        "n_max": n_max,
        "max_events": max(f["events_max"] for f in families.values()),
        "at_n_max": at_max,
        "weakest_ms_max": max(weakest_ms) if weakest_ms else None,
    }

    # The punchline ratio: LBChain (FORBIDDEN) vs LBFakeChain (ALLOWED) WEAKEST time
    # at the largest n. Identical event structure, so any gap is UNSAT-proof overhead.
    real = at_max.get("LBChain", {}).get("WEAKEST")
    fake = at_max.get("LBFakeChain", {}).get("WEAKEST")
    if real and fake:
        out["lb_real_vs_fake_ratio"] = round(real / fake, 2)

    # Slowest PSO/RA cell anywhere in the sweep — the "cost drivers" claim.
    pso_ra = [
        v["ms"]
        for fam in families.values()
        for cells in fam["by_n"].values()
        for m, v in cells.items()
        if m in ("PSO", "RA")
    ]
    out["pso_ra_ms_max"] = max(pso_ra) if pso_ra else None
    if secs is not None:
        out["wall_seconds"] = round(secs, 1)
    return out


def step_hierarchy(res: dict) -> dict:
    """Hierarchy preservation over the corrected fragment.

    Derived from the committed per-model corpus-validation CSVs (5 rows per test)
    intersected with the v2 manifest. Re-running the full five-model corpus
    validation is a multi-hour job and is not part of this driver; the source is
    recorded as ``artifact`` accordingly.
    """
    manifest = set(read_manifest(MANIFEST_V2))
    per_test: dict[str, dict[str, str]] = {}
    for csv_name, prefix in (
        ("corpus-validation-dat3m.csv", "Dat3M/"),
        ("corpus-validation-herd7.csv", "herdtools7/"),
    ):
        path = EVAL / csv_name
        if not path.is_file():
            continue
        for r in read_csv_rows(path):
            if r.get("status") != "OK":
                continue
            key = prefix + r["file"]
            if key in manifest:
                per_test.setdefault(key, {})[r["model"]] = r["actual"]

    edges = [("SC", "TSO"), ("SC", "PSO"), ("SC", "RA"), ("SC", "WEAKEST")]
    intermediate = [("TSO", "PSO"), ("RA", "WEAKEST")]

    def count_violations(pairs):
        n = 0
        witnesses = []
        for test, models in per_test.items():
            for strong, weak in pairs:
                if strong in models and weak in models:
                    if models[strong] == "ALLOWED" and models[weak] == "FORBIDDEN":
                        n += 1
                        if len(witnesses) < 10:
                            witnesses.append(f"{test} ({strong}->{weak})")
        return n, witnesses

    v_main, w_main = count_violations(edges)
    v_int, w_int = count_violations(intermediate)

    weakest_only = [
        t
        for t, m in per_test.items()
        if m.get("WEAKEST") == "ALLOWED"
        and all(m.get(x) == "FORBIDDEN" for x in ("SC", "TSO", "PSO", "RA") if x in m)
        and all(x in m for x in ("SC", "TSO", "PSO", "RA"))
    ]

    return {
        "source": "artifact",
        "note": "derived from corpus-validation-{dat3m,herd7}.csv ∩ manifest v2",
        "tests_with_all_models": len(per_test),
        "edges_checked": [f"{a}>={b}" for a, b in edges],
        "violations": v_main,
        "violation_witnesses": w_main,
        "intermediate_violations": v_int,
        "intermediate_witnesses": w_int,
        "weakest_only_allowed": len(weakest_only),
        "weakest_only_allowed_tests": weakest_only,
    }


def step_herd7(res: dict) -> dict:
    """herd7 cross-check: WEV vs herd7 SC/TSO verdicts on the shared subset.

    Derived from the two committed CSVs (herd7's own verdicts and the WEV verdicts
    for the same files); re-running herd7 itself needs the herd7 distribution and is
    outside this driver.
    """
    herd = {
        r["relpath"]: r
        for r in read_csv_rows(EVAL / "herd7-results.csv")
    }
    wev = read_csv_rows(EVAL / "herd7-wev-verdicts.csv")

    buckets: dict[tuple[str, str], list[int]] = {}
    for w in wev:
        h = herd.get(w["file"])
        if not h:
            continue
        group = "ARM" if h["arch"] == "ARM" else "non-ARM"
        for model in ("SC", "TSO"):
            hv, wv = h["herd_" + model.lower()], w["wev_" + model]
            if hv not in ("ALLOWED", "FORBIDDEN") or wv not in ("ALLOWED", "FORBIDDEN"):
                continue
            b = buckets.setdefault((group, model), [0, 0])
            b[1] += 1
            if hv == wv:
                b[0] += 1

    out = {"source": "artifact", "note": "derived from herd7-results.csv + herd7-wev-verdicts.csv"}
    for (group, model), (agree, total) in buckets.items():
        out[f"{group}_{model}"] = {
            "agree": agree,
            "compared": total,
            "percent": round(100.0 * agree / total, 1) if total else None,
        }
    return out


def step_coverage(res: dict) -> dict:
    """Corpus coverage: how much of the 12,324-file corpus is in the fragment."""
    manifest = read_manifest(MANIFEST_V2)
    excluded_path = EVAL / "layer2-excluded-tests.csv"
    excluded = len(read_csv_rows(excluded_path)) if excluded_path.is_file() else 0

    skipped = parse_errors = 0
    for csv_name in ("corpus-validation-dat3m.csv", "corpus-validation-herd7.csv"):
        path = EVAL / csv_name
        if not path.is_file():
            continue
        seen = set()
        for r in read_csv_rows(path):
            f = r["file"]
            if f in seen:  # one row per (file, model); count each file once
                continue
            seen.add(f)
            status = r.get("status", "")
            if status == "SKIP":
                skipped += 1
            elif "PARSE" in status.upper():
                parse_errors += 1

    size = len(manifest)
    # The four buckets must partition the corpus: validated (v2) + audit-excluded
    # + skipped + parse errors = every file.
    parts = size + excluded + skipped + parse_errors
    return {
        "source": "artifact",
        "corpus_files_total": CORPUS_FILES_TOTAL,
        "supported_fragment": size,
        "supported_fragment_v1": size + excluded,
        "excluded_unmodeled_instructions": excluded,
        "coverage_percent": round(100.0 * size / CORPUS_FILES_TOTAL, 1),
        "coverage_percent_v1": round(100.0 * (size + excluded) / CORPUS_FILES_TOTAL, 1),
        "unsupported": CORPUS_FILES_TOTAL - size,
        "skipped_unsupported_instructions": skipped,
        "parse_errors": parse_errors,
        "parts_sum": parts,
        "parts_partition_corpus": parts == CORPUS_FILES_TOTAL,
    }


def step_layer2(res: dict) -> dict:
    """Layer-2 detector audit over the corrected manifest (expect zero sites)."""
    out_csv = EVAL / "layer2-audit.csv"
    rc, out, secs = run_java(
        "wev.smt.cli.Layer2AuditRun", MANIFEST_V2, CORPUS_ROOT, out_csv
    )
    if rc != 0:
        raise RuntimeError(f"Layer2AuditRun exited {rc}\n{out[-3000:]}")
    rows = read_csv_rows(out_csv) if out_csv.is_file() else []
    tests = {r.get("test") for r in rows}
    exprs = {r.get("expression") for r in rows}
    return {
        "source": "run",
        "csv": "eval/layer2-audit.csv",
        "wall_seconds": round(secs, 1),
        "layer2_sites": len(rows),
        "layer2_tests": len(tests),
        "layer2_expressions": len(exprs),
    }


# Step registry: name -> (function, description, is_long)
STEPS = [
    ("coverage", step_coverage, "corpus coverage / fragment size", False),
    ("layer2", step_layer2, "Layer-2 detector audit (expect 0 sites)", False),
    ("ladder", step_ladder, "C1 symbolic validation ladder (9 steps)", False),
    ("corpus_symbolic", step_corpus_symbolic, "SymbolicRfRun over the full corpus", True),
    ("examples", step_examples, "run the 41 published/Dat3M examples", False),
    ("dat3m41", step_dat3m41, "41-test Dat3M cross-check", False),
    ("jmm20", step_jmm20, "JMM causality suite TC01-TC20", False),
    ("published34", step_published34, "published-example validation (34)", False),
    ("ra_atlas", step_ra_atlas, "40-test RA formal atlas", False),
    ("rc11_direction", step_rc11_direction, "RC11 direction check", False),
    ("nthread", step_nthread, "n-thread grounded admission (n=2..8)", False),
    ("pwt", step_pwt, "PwT equivalence sweep", False),
    ("pso_canonical", step_pso_canonical, "PSO' vs canonical SPARC PSO", False),
    ("qfbv_detector", step_qfbv_detector, "QF_BV oracle vs solver-free detector", True),
    ("detector_oracle_audit", step_detector_oracle_audit,
     "differential detector audit (regression gate; fails on unsound divergence)", True),
    ("detector_audit", step_detector_audit, "detector quality sweep (edge census)", False),
    ("scalability", step_scalability, "scalability sweep (n=2..16)", True),
    ("hierarchy", step_hierarchy, "hierarchy preservation", False),
    ("herd7", step_herd7, "herd7 SC/TSO cross-check", False),
]

# Steps that other steps read from res[...]; must not be skipped independently.
DEPENDENCIES = {
    "dat3m41": ["examples"],
    "jmm20": ["examples"],
    "published34": ["examples"],
}


# ── numbers.tex ───────────────────────────────────────────────────────────────


def build_macros(res: dict) -> list[tuple[str, str, str]]:
    """Return (macro, value, comment) for every inline number the paper cites."""
    m: list[tuple[str, str, str]] = []

    def add(name, value, comment=""):
        m.append((name, str(value), comment))

    cov = res.get("coverage", {})
    if cov:
        add("CorpusSize", cov["supported_fragment"], "supported-syntax fragment (v2)")
        add("CorpusSizeVOne", cov["supported_fragment_v1"],
            "fragment before the Layer-2 move-out")
        add("CorpusFilesTotal", f"{cov['corpus_files_total']:,}",
            "total litmus files (herd7 + Dat3M)")
        add("CoveragePercent", cov["coverage_percent"], "percent of corpus in the fragment")
        add("UnsupportedFiles", cov["unsupported"], "files outside the fragment")
        add("SkippedUnsupported", cov["skipped_unsupported_instructions"],
            "skipped: unsupported instructions")
        add("ParseErrors", cov["parse_errors"], "parse errors")
        add("ExcludedUnmodeled", cov["excluded_unmodeled_instructions"],
            "Category-B unmodeled-instruction tests moved out")

    h = res.get("hierarchy", {})
    if h:
        add("HierarchyViolations", h["violations"], "SC-to-weak edge violations")
        add("HierarchyIntermediateViolations", h["intermediate_violations"],
            "TSO>=PSO and RA>=WEAKEST violations")
        add("HierarchyTests", h["tests_with_all_models"], "tests with all five model verdicts")
        add("WeakestOnlyAllowed", h["weakest_only_allowed"],
            "tests WEAKEST allows and all four stronger models forbid")

    l2 = res.get("layer2", {})
    if l2:
        add("LayerTwoSites", l2["layer2_sites"], "Layer-2 (conservative) sites remaining")
        add("LayerTwoTests", l2["layer2_tests"], "tests routed to Layer 2")

    c = res.get("corpus_symbolic", {})
    if c:
        add("SymbolicCorpusTests", c["tests"], "tests run in both modes")
        add("SymbolicVerdictChanges", c["verdict_changes"], "wired -> symbolic changes")
        add("SymbolicAllowedToForbidden", c["allowed_to_forbidden"], "value-unreachable exists")
        add("SymbolicForbiddenToAllowed", c["forbidden_to_allowed"], "free co / free reads")
        add("SymbolicErrors", c["errors"], "encoder errors")
        add("SymbolicSkipped", c["skipped"], "over the symbolic event cap")
        add("MedianRuntimeMs", c["symbolic_ms_median"], "median symbolic solve time")
        add("PNinetyFiveRuntimeMs", c["symbolic_ms_p95"], "p95 symbolic solve time")
        add("MaxRuntimeMs", c["symbolic_ms_max"], "max symbolic solve time")
        add("MeanRuntimeMs", c["symbolic_ms_mean"], "mean symbolic solve time")
        add("CorpusWallSeconds", c["wall_seconds"], "both modes, one JVM")

    d = res.get("dat3m41", {})
    if d:
        add("DatThreeMTotal", d["total"], "Dat3M cross-check set size")
        add("DatThreeMagreement", frac(d["symbolic_agreement"], d["total"]),
            "symbolic-mode agreement")
        add("DatThreeMagreementWired", frac(d["wired_agreement"], d["total"]),
            "wired-mode agreement")
        add("DatThreeMdivergences", d["divergences"], "remaining divergences")

    j = res.get("jmm20", {})
    if j:
        add("JmmTotal", j["total"], "JMM causality tests")
        add("JmmAgreement", frac(j["symbolic_agreement"], j["total"]), "symbolic-mode agreement")
        add("JmmAgreementWired", frac(j["wired_agreement"], j["total"]), "wired-mode agreement")
        add("JmmDivergences", len(j["divergences"]), "remaining JMM divergences")

    p = res.get("published34", {})
    if p:
        add("PublishedExampleTotal", p["total"], "published examples encoded")
        add("PublishedExampleAgreement", frac(p["symbolic_agreement"], p["total"]),
            "agreement with source-paper classifications")
        add("PublishedExampleDivergences", p["total"] - p["symbolic_agreement"], "divergences")
        for src, key in (
            ("weakestmo", "Weakestmo"),
            ("leaky-semicolon", "PwTExamples"),
            ("jmm", "JmmExamples"),
        ):
            b = p["per_source"].get(src)
            if b:
                add(f"{key}Total", b["total"], f"{src} examples")
                add(f"{key}Agreement", frac(b["symbolic"], b["total"]), f"{src} agreement")

    pw = res.get("pwt", {})
    if pw:
        add("PwtPairs", pw["pairs"], "PwT equivalence pairs")
        add("PwtAgreement", frac(pw["agree"], pw["pairs"]), "PwT agreement")
        add("PwtDisagreements", pw["disagree"], "PwT disagreements")

    r = res.get("rc11_direction", {})
    if r:
        add("RcElevenTests", r["tests"], "RC11 direction-check tests")
        add("RcElevenAgree", r["agree"], "verdicts that agree")
        add("RcElevenDivergences", r["rc11_forbids_weakest_allows"],
            "RC11 forbids, WEAKEST allows")
        add("RcElevenReverseDivergences", r["weakest_forbids_rc11_allows"],
            "WEAKEST forbids, RC11 allows (containment direction)")

    a = res.get("ra_atlas", {})
    if a:
        add("AtlasTests", a["tests"], "RA formal atlas size")
        add("AtlasRaFormalAgreement", frac(a["ra_formal_matches_ra"], a["tests"]),
            "RA_FORMAL vs encoded RA")
        add("AtlasRaFormalDivergences", a["ra_formal_divergences"], "atlas divergences")
        add("AtlasSubchainViolations", a["subchain_violations"],
            "SC >= TSO >= RA_FORMAL violations")
        add("AtlasRaWeakestWitnesses", a["ra_weakest_witnesses"],
            "RA allows / WEAKEST forbids (Prop. RA not-above WEAKEST)")

    nt = res.get("nthread", {})
    if nt:
        add("NThreadMin", nt["n_min"], "grounded-admission n range low")
        add("NThreadMax", nt["n_max"], "grounded-admission n range high")
        add("NThreadAllowed", frac(nt["allowed"], nt["rows"]), "LBFakeChain(n) ALLOWED")

    ps = res.get("pso_canonical", {})
    if ps:
        add("PsoAtlasTests", ps["tests"], "PSO comparison atlas size")
        add("PsoCanonicalDivergences", frac(ps["divergences"], ps["tests"]),
            "PSO' vs canonical SPARC PSO")

    q = res.get("qfbv_detector", {})
    if q:
        add("QfbvTests", q["tests"], "tests re-classified under the QF_BV oracle")
        add("QfbvChanged", q["changed"], "classification changes vs the solver-free detector")

    da = res.get("detector_audit", {})
    if da:
        add("DetectorEdges", da["edges"], "realized dependency edges in the fragment")
        add("DetectorFakeEdges", da["fake_edges"], "classified fake")
        add("DetectorSemanticEdges", da["semantic_edges"], "classified semantic")
        add("DetectorRelevantEdges", da["verdict_relevant_edges"],
            "edges on a candidate (dep union rf) cycle")

    lad = res.get("ladder", {})
    if lad:
        add("LadderAgreement", frac(lad["passed"], lad["total"]), "C1 validation ladder")
        add("LadderFailures", lad["failed"], "ladder failures")

    hd = res.get("herd7", {})
    if hd:
        for group, key in (("non-ARM", "NonArm"), ("ARM", "Arm")):
            for model in ("SC", "TSO"):
                b = hd.get(f"{group}_{model}")
                if b:
                    add(f"Herd{key}{model}Percent", b["percent"],
                        f"herd7 {model} agreement, {group}")
                    add(f"Herd{key}{model}Compared", b["compared"],
                        f"herd7 {model} tests compared, {group}")

    s = res.get("scalability", {})
    if s:
        add("ScalabilityNMax", s["n_max"], "largest n swept")
        add("ScalabilityNMin", min(s["n_values"]), "smallest n swept")
        add("ScalabilityMaxEvents", s["max_events"], "largest candidate encoded")
        add("ScalabilityFamilies", len(s["families"]), "parametric families")
        if s.get("weakest_ms_max") is not None:
            add("WeakestMaxMs", s["weakest_ms_max"], "slowest WEAKEST query in the sweep")
        if s.get("pso_ra_ms_max") is not None:
            add("PsoRaMaxMs", s["pso_ra_ms_max"], "slowest PSO/RA query in the sweep")
        if s.get("lb_real_vs_fake_ratio") is not None:
            add("LbRealFakeRatio", s["lb_real_vs_fake_ratio"],
                "LBChain/LBFakeChain WEAKEST time ratio at n=n_max")
        for fam, cell in s["at_n_max"].items():
            for model in ("SC", "TSO", "WEAKEST"):
                if cell.get(model) is not None:
                    add(f"Scal{fam}{model}", cell[model], f"{fam} {model} ms at n=n_max")
            if cell.get("PSO_RA_max") is not None:
                add(f"Scal{fam}PSORA", cell["PSO_RA_max"], f"{fam} max(PSO,RA) ms at n=n_max")
            if cell.get("events") is not None:
                add(f"Scal{fam}Events", cell["events"], f"{fam} events at n=n_max")

    return m


def write_numbers_tex(res: dict) -> list[tuple[str, str, str]]:
    macros = build_macros(res)
    lines = [
        "% eval/numbers.tex — GENERATED by eval/run_all.py. Do not edit by hand.",
        f"% Generated: {res['meta']['generated']}",
        "% Every value below comes from eval/results.json, which records for each",
        "% experiment whether it was produced by a run or derived from a committed CSV.",
        "",
    ]
    width = max((len(n) for n, _, _ in macros), default=0)
    for name, value, comment in macros:
        pad = " " * (width - len(name))
        line = f"\\newcommand{{\\{name}}}{{{value}}}{pad}"
        if comment:
            line += f"  % {comment}"
        lines.append(line)
    lines.append("")
    NUMBERS_TEX.write_text("\n".join(lines), encoding="utf-8")
    return macros


# ── tables.tex ────────────────────────────────────────────────────────────────

# Literature classifications for Table 1 (fidelity). These are reference data from
# the cited papers, not measurements; the verdict column is measured.
FIDELITY_PROGRAMS = [
    ("paper/LB-real", "LB-real", "FORBIDDEN"),
    ("leaky-semicolon/LBd", "LBd", "FORBIDDEN"),
    ("leaky-semicolon/LBfd", "LBfd", "ALLOWED"),
    ("leaky-semicolon/LBxor", "LBxor", "ALLOWED"),
    ("paper/LB+ctrldata+ctrl-single", "LB+ctrldata+ctrl-single", "FORBIDDEN"),
    ("paper/LB+ctrldata+ctrl-double", "LB+ctrldata+ctrl-double", "ALLOWED"),
]

# Table 4 (RC11 baseline) — dependency-type prose is reference data.
RC11_ROWS = [
    ("LB-real", "Real dep ($r{+}1$): genuine thin-air"),
    ("LB-fake-xor-cycle", "Fake XOR dep ($r{\\oplus}r{=}1$): grounded LB"),
    ("LBfd", "Fake lin-arith dep ($r{+}1{-}r{=}1$): grounded LB"),
]


def _tex_escape(s: str) -> str:
    return s.replace("_", "\\_").replace("&", "\\&").replace("%", "\\%")


def write_tables_tex(res: dict) -> None:
    out = [
        "% eval/tables.tex — GENERATED by eval/run_all.py. Do not edit by hand.",
        f"% Generated: {res['meta']['generated']}",
        "% Each macro below expands to a tabular BODY (rows only); the surrounding",
        "% table/tabular/caption stays in the paper.",
        "",
    ]

    # ── Table 1: Definition fidelity on canonical examples ──
    out.append("% Table 1 — Def. thin-air fidelity on canonical examples")
    out.append("\\newcommand{\\FidelityTableBody}{%")
    verdicts = res.get("examples", {}).get("verdicts", {})
    agree_names, diverge_rows = [], []
    for key, label, literature in FIDELITY_PROGRAMS:
        got = verdicts.get(key, {}).get("symbolic")
        if got is None:
            continue
        if got == literature:
            agree_names.append(f"\\texttt{{{_tex_escape(label)}}}")
        else:
            diverge_rows.append((label, got, literature))
    if agree_names:
        out.append(f"  {', '.join(agree_names)} & as expected & agree \\\\")
    for label, got, literature in diverge_rows:
        short = "FORB" if got == "FORBIDDEN" else "ALLOW"
        lit = "FORB" if literature == "FORBIDDEN" else "ALLOW"
        out.append(
            f"  \\texttt{{{_tex_escape(label)}}} & {short} & {lit}$^\\dagger$ \\\\"
        )
    out.append("}")
    out.append(
        "\\newcommand{\\FidelityAgreement}{"
        f"{len(agree_names)}/{len(agree_names) + len(diverge_rows)}}}"
    )
    out.append("")

    # ── Table 2: Scalability at n = n_max ──
    out.append("% Table 2 — scalability summary at the largest n")
    out.append("\\newcommand{\\ScalabilityTableBody}{%")
    s = res.get("scalability")
    if s:
        n_max = s["n_max"]
        for fam in ("LBChain", "LBFakeChain", "SBNThread", "IRIWFan"):
            cell = s["at_n_max"].get(fam)
            if not cell:
                continue
            pso_ra = cell.get("PSO_RA_max")
            pso_txt = f"$\\sim${pso_ra}" if pso_ra is not None else "---"
            out.append(
                f"  $\\mathsf{{{fam}}}$ ($n{{=}}{n_max}$, {cell['events']} events) & "
                f"{cell['verdict']} & {cell['SC']} & {cell['TSO']} & "
                f"{cell['WEAKEST']} & {pso_txt} \\\\"
            )
    else:
        out.append("  \\multicolumn{6}{c}{scalability sweep not run} \\\\")
    out.append("}")
    out.append("")

    # ── Table 3: Published-example validation ──
    out.append("% Table 3 — published-example validation against three sources")
    out.append("\\newcommand{\\SourcePaperTableBody}{%")
    p = res.get("published34")
    if p:
        labels = {
            "weakestmo": "Weakestmo~\\cite{chakraborty2019grounding}",
            "leaky-semicolon": "Leaky Semicolon / PwT~\\cite{jeffrey2022leaky}",
            "jmm": "JMM causality suite~\\cite{manson2005java}",
        }
        for src in ("weakestmo", "leaky-semicolon", "jmm"):
            b = p["per_source"].get(src)
            if not b:
                continue
            out.append(
                f"  {labels[src]} & {b['total']} & {b['symbolic']}/{b['total']} & "
                f"{b['total'] - b['symbolic']} \\\\"
            )
        out.append("  \\midrule")
        out.append(
            f"  Total & {p['total']} & {p['symbolic_agreement']}/{p['total']} & "
            f"{p['total'] - p['symbolic_agreement']} \\\\"
        )
    else:
        out.append("  \\multicolumn{4}{c}{published-example validation not run} \\\\")
    out.append("}")
    out.append("")

    # ── Table 4: RC11 thin-air baseline ──
    out.append("% Table 4 — RC11 thin-air baseline vs WEAKEST on the wired LB family")
    out.append("\\newcommand{\\RcElevenTableBody}{%")
    r = res.get("rc11_direction")
    if r:
        rc11_map = {}
        for row in read_csv_rows(EVAL / "rc11-direction-check.csv"):
            rc11_map[row["test"]] = (row["rc11_verdict"], row["weakest_verdict"])
        for test, dep in RC11_ROWS:
            if test not in rc11_map:
                continue
            rc11_v, weak_v = rc11_map[test]
            mark = "\\checkmark" if rc11_v == weak_v else "$\\times$"
            out.append(
                f"  \\texttt{{{_tex_escape(test)}}} & {rc11_v} & {weak_v} & {mark} & {dep} \\\\"
            )
    else:
        out.append("  \\multicolumn{5}{c}{RC11 direction check not run} \\\\")
    out.append("}")
    out.append("")

    TABLES_TEX.write_text("\n".join(out), encoding="utf-8")


# ── Summary ───────────────────────────────────────────────────────────────────


def print_summary(res: dict, macros: list[tuple[str, str, str]]) -> None:
    bar = "=" * 78
    print(f"\n{bar}\nWEV-SMT evaluation — summary\n{bar}")

    meta = res["meta"]
    print(f"generated : {meta['generated']}")
    print(f"platform  : {meta['platform']}  java={meta['java']}  python={meta['python']}")
    print(f"wall clock: {meta['wall_seconds']:.1f}s over {len(meta['steps_run'])} steps")
    if meta.get("steps_carried_forward"):
        print(f"carried    : {len(meta['steps_carried_forward'])} block(s) reused from an "
              "earlier results.json (marked CARRIED below)")

    print("\n-- steps " + "-" * 69)
    carried = set(meta.get("steps_carried_forward", []))
    for name, _, desc, _ in STEPS:
        st = res.get("_status", {}).get(name)
        if st is None and name in carried:
            when = res.get(name, {}).get("ran_at", "?")
            print(f"  {name:<18} CARRIED   {desc}  [from earlier run {when}]")
        elif st is None:
            print(f"  {name:<18} SKIPPED   {desc}")
        elif st["ok"]:
            src = res.get(name, {}).get("source", "?")
            print(f"  {name:<18} ok        {desc}  [{src}, {st['seconds']:.1f}s]")
        else:
            print(f"  {name:<18} FAILED    {desc}")
            print(f"  {'':<18}   {st['error'][:200]}")

    print("\n-- headline numbers " + "-" * 58)

    def row(label, value):
        print(f"  {label:<44} {value}")

    cov = res.get("coverage")
    if cov:
        row("supported-syntax fragment",
            f"{cov['supported_fragment']} of {cov['corpus_files_total']} "
            f"({cov['coverage_percent']}%)")
    l2 = res.get("layer2")
    if l2:
        row("Layer-2 sites remaining", l2["layer2_sites"])
    h = res.get("hierarchy")
    if h:
        row("hierarchy violations (SC-to-weak)",
            f"{h['violations']} over {h['tests_with_all_models']} tests")
    lad = res.get("ladder")
    if lad:
        row("C1 validation ladder", f"{lad['passed']}/{lad['total']} PASS")
    c = res.get("corpus_symbolic")
    if c:
        row("symbolic corpus run", f"{c['tests']} tests, {c['wall_seconds']}s")
        row("  verdict changes (wired -> symbolic)",
            f"{c['verdict_changes']} "
            f"({c['allowed_to_forbidden']} A->F, {c['forbidden_to_allowed']} F->A)")
        row("  symbolic solve ms",
            f"median {c['symbolic_ms_median']}, p95 {c['symbolic_ms_p95']}, "
            f"max {c['symbolic_ms_max']}")
    d = res.get("dat3m41")
    if d:
        row("Dat3M agreement (symbolic)",
            f"{d['symbolic_agreement']}/{d['total']}  (wired {d['wired_agreement']}/{d['total']})")
    j = res.get("jmm20")
    if j:
        row("JMM agreement (symbolic)",
            f"{j['symbolic_agreement']}/{j['total']}  (wired {j['wired_agreement']}/{j['total']})")
        if j["divergences"]:
            row("  JMM divergences",
                ", ".join(x["test"] for x in j["divergences"]))
    p = res.get("published34")
    if p:
        row("published-example agreement",
            f"{p['symbolic_agreement']}/{p['total']}")
        for src, b in p["per_source"].items():
            row(f"  {src}", f"{b['symbolic']}/{b['total']}")
    pw = res.get("pwt")
    if pw:
        row("PwT equivalence", f"{pw['agree']}/{pw['pairs']}")
    ps = res.get("pso_canonical")
    if ps:
        row("PSO' vs canonical PSO", f"{ps['divergences']}/{ps['tests']} divergences")
    q = res.get("qfbv_detector")
    if q:
        row("QF_BV oracle vs solver-free detector",
            f"{q['changed']}/{q['tests']} classification changes")
    doa = res.get("detector_oracle_audit")
    if doa:
        row("differential detector audit",
            f"{doa['unsound']} UNSOUND / {doa['total_audited']} audited "
            f"({doa['enumerated_checked']} enumerated + {doa['corpus_pairs_checked']} corpus)")
        row("  of which conservative (sound)",
            f"{doa['conservative']} (detector REAL, oracle CONSTANT)")
    da = res.get("detector_audit")
    if da:
        row("detector edge census",
            f"{da['edges']} edges ({da['fake_edges']} fake, "
            f"{da['semantic_edges']} semantic, {da['verdict_relevant_edges']} verdict-relevant)")
    r = res.get("rc11_direction")
    if r:
        row("RC11 direction check",
            f"{r['rc11_forbids_weakest_allows']} RC11-forbids/WEAKEST-allows, "
            f"{r['weakest_forbids_rc11_allows']} reverse, over {r['tests']} tests")
    a = res.get("ra_atlas")
    if a:
        row("RA formal atlas",
            f"{a['ra_formal_matches_ra']}/{a['tests']} RA_FORMAL=RA, "
            f"{a['subchain_violations']} subchain violations")
        row("  RA-allows/WEAKEST-forbids witnesses",
            f"{a['ra_weakest_witnesses']} ({', '.join(a['ra_weakest_witness_tests'])})")
    nt = res.get("nthread")
    if nt:
        row("grounded admission",
            f"{nt['allowed']}/{nt['rows']} ALLOWED for n={nt['n_min']}..{nt['n_max']}")
    hd = res.get("herd7")
    if hd:
        for k, v in hd.items():
            if isinstance(v, dict):
                row(f"herd7 {k}", f"{v['agree']}/{v['compared']} ({v['percent']}%)")
    s = res.get("scalability")
    if s:
        row("scalability sweep",
            f"{len(s['families'])} families, n={min(s['n_values'])}..{s['n_max']}, "
            f"max {s['max_events']} events")

    print("\n-- generated files " + "-" * 59)
    print(f"  {RESULTS_JSON.relative_to(ROOT)}  ({len(res) - 2} experiment blocks)")
    print(f"  {NUMBERS_TEX.relative_to(ROOT)}   ({len(macros)} \\newcommand macros)")
    print(f"  {TABLES_TEX.relative_to(ROOT)}    (Tables 1-4 bodies)")

    failures = [n for n, st in res.get("_status", {}).items() if not st["ok"]]
    if failures:
        print(f"\n!! {len(failures)} step(s) FAILED: {', '.join(failures)}")
    print(bar)


# ── Driver ────────────────────────────────────────────────────────────────────


def java_version() -> str:
    try:
        p = subprocess.run(
            ["java", "-version"], capture_output=True, text=True, timeout=30
        )
        return ((p.stdout or "") + (p.stderr or "")).splitlines()[0].strip()
    except Exception:
        return "unknown"


def main() -> int:
    names = [s[0] for s in STEPS]
    ap = argparse.ArgumentParser(
        description="Run the full WEV-SMT evaluation and regenerate numbers.tex/tables.tex.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="steps: " + ", ".join(names),
    )
    ap.add_argument("--only", nargs="+", metavar="STEP", help="run only these steps")
    ap.add_argument("--skip", nargs="+", metavar="STEP", default=[], help="skip these steps")
    ap.add_argument(
        "--quick", action="store_true", help="skip long steps (scalability, full corpus)"
    )
    ap.add_argument(
        "--no-build", action="store_true", help="do not invoke maven even if target/ is stale"
    )
    args = ap.parse_args()

    for n in (args.only or []) + args.skip:
        if n not in names:
            sys.exit(f"unknown step '{n}'; known steps: {', '.join(names)}")

    selected = set(args.only) if args.only else set(names)
    if args.only:
        for n in list(selected):
            for dep in DEPENDENCIES.get(n, []):
                selected.add(dep)
    selected -= set(args.skip)
    if args.quick:
        selected -= {s[0] for s in STEPS if s[3]}

    if not args.no_build:
        ensure_build()

    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")

    # Seed from the previous results.json so a partial run (--only/--skip/--quick)
    # regenerates a COMPLETE numbers.tex instead of silently dropping macros. Every
    # block keeps the timestamp of the run that produced it, and the summary marks
    # blocks carried over from an earlier invocation.
    res: dict = {}
    if RESULTS_JSON.is_file() and selected != set(names):
        try:
            prev = json.loads(RESULTS_JSON.read_text(encoding="utf-8"))
            for k, v in prev.items():
                if k not in ("meta", "_status") and isinstance(v, dict):
                    res[k] = v
            if res:
                log(f"[seed] carried {len(res)} block(s) forward from {RESULTS_JSON.name}")
        except Exception as exc:
            log(f"[seed] could not read previous results.json: {exc}")

    carried = set(res)
    res.update({
        "meta": {
            "generated": now,
            "platform": f"{platform.system()} {platform.release()}",
            "java": java_version(),
            "python": sys.version.split()[0],
            # Placeholder, not the real path: the absolute repository root is
            # machine-specific and would leak a local username into the shipped
            # artifact. Nothing reads this field; it is provenance only.
            "repo_root": "<ARTIFACT_ROOT>",
            "manifest": str(MANIFEST_V2.relative_to(ROOT)),
            "steps_run": [],
            "steps_carried_forward": sorted(carried),
        },
        "_status": {},
    })

    log("=" * 78)
    log("WEV-SMT evaluation driver — python eval/run_all.py")
    log("=" * 78)

    t_all = time.time()
    for name, fn, desc, is_long in STEPS:
        if name not in selected:
            log(f"[skip] {name}: {desc}")
            continue
        log(f"\n[{name}] {desc}{' (long)' if is_long else ''}")
        t0 = time.time()
        try:
            out = fn(res)
            out["ran_at"] = now
            res[name] = out
            carried.discard(name)
            res["_status"][name] = {"ok": True, "seconds": time.time() - t0}
            res["meta"]["steps_run"].append(name)
            log(f"[{name}] done in {time.time() - t0:.1f}s")
        except Exception as exc:  # a failing experiment must not abort the rest
            res["_status"][name] = {
                "ok": False,
                "seconds": time.time() - t0,
                "error": f"{type(exc).__name__}: {exc}",
            }
            log(f"[{name}] FAILED: {type(exc).__name__}: {exc}")

    # A scalability CSV may exist from an earlier run even when the sweep is skipped;
    # tables need it, so fall back to the committed artifact and label it as such.
    if "scalability" not in res and (EVAL / "scalability-consistency.csv").is_file():
        try:
            res["scalability"] = _scalability_from_csv(None, "artifact")
            log("[scalability] using committed CSV (sweep not run this session)")
        except Exception as exc:
            log(f"[scalability] could not read committed CSV: {exc}")

    res["meta"]["wall_seconds"] = time.time() - t_all
    res["meta"]["steps_carried_forward"] = sorted(carried)

    RESULTS_JSON.write_text(json.dumps(res, indent=2, sort_keys=False), encoding="utf-8")
    macros = write_numbers_tex(res)
    write_tables_tex(res)
    print_summary(res, macros)

    return 1 if any(not st["ok"] for st in res["_status"].values()) else 0


if __name__ == "__main__":
    sys.exit(main())
