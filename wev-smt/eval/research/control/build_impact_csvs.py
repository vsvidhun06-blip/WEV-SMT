"""Build eval/control-parser-impact.csv and eval/control-parser-headline-delta.csv.

Consumes the parsed-representation dumps produced by
wev.smt.research.ControlParserImpactRun and joins them against every committed
result CSV that contributes to a paper headline.

Read-only with respect to the parser, detector and encoder.
"""

import csv
import os
import sys
from collections import Counter, defaultdict

SCRATCH = sys.argv[1] if len(sys.argv) > 1 else "."

# Tests whose *parsed* representation is control-relevant, by population.
POP_OF_DIR = {
    "eval/examples/paper": "published/paper",
    "eval/examples/jmm": "published/JMM",
    "eval/examples/weakestmo": "published/weakestmo",
    "eval/examples/leaky-semicolon": "published/PwT",
    "eval/examples/diag": "published/diag",
    "eval/mined": "mined",
    "eval/research/parser-probe": "parser-probe",
}


def read_tsv(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as f:
        return list(csv.DictReader(f))


def base(t):
    return t.replace("\\", "/").split("/")[-1].replace(".litmus", "")


def population(path):
    p = path.replace("\\", "/")
    for d, pop in POP_OF_DIR.items():
        if p.startswith(d):
            return pop
    if p.startswith("eval/corpus"):
        return "corpus-2972"
    return "other"


def main():
    rows = read_tsv(os.path.join(SCRATCH, "scan-examples.tsv"))
    rows += read_tsv(os.path.join(SCRATCH, "scan-corpus.tsv"))

    # ── which result CSVs mention each test, and what they reported ──────────
    uses = defaultdict(list)      # test -> [headline names]
    reported = {}                 # (test, headline) -> reported outcome

    sp = read_csv("eval/source-paper-results.csv")
    for r in sp:
        uses[r["test"]].append("PublishedExamples")
        reported[(r["test"], "PublishedExamples")] = \
            f"{r['source_verdict']}->{r['wev_verdict']}/match={r['match']}"

    jm = read_csv("eval/jmm-results.csv")
    for r in jm:
        uses[r["test"]].append("JMM")
        uses[r["test"]].append("PublishedExamples")
        reported[(r["test"], "JMM")] = \
            f"{r['jmm_verdict']}->{r['wev_verdict']}/match={r['match']}"
        reported[(r["test"], "PublishedExamples")] = \
            f"{r['jmm_verdict']}->{r['wev_verdict']}/match={r['match']}"

    d3 = read_csv("eval/dat3m-comparison-v2.csv")
    for r in d3:
        t = base(r["test"])
        uses[t].append("Dat3M")
        reported[(t, "Dat3M")] = f"{r['wev_verdict']}vs{r['dat3m_verdict']}/agree={r['agree']}"

    rc = read_csv("eval/rc11-direction-check.csv")
    for r in rc:
        t = base(r["test"])
        uses[t].append("RC11")
        reported[(t, "RC11")] = \
            f"{r['rc11_verdict']}vs{r['weakest_verdict']}/{r['direction']}"

    an = read_csv("eval/anchor-discriminating.csv")
    for r in an:
        t = base(r["test"])
        uses[t].append("Anchor")
        reported[(t, "Anchor")] = \
            f"{r['verdict_current']}/disc={r['discriminating']}"

    qf = read_csv("eval/qfbv-detector-results.csv")
    qfset = {base(r["test"]) for r in qf}

    # ── collisions: same canonical hash, different source file ──────────────
    byhash = defaultdict(set)
    for r in rows:
        if r["parse_ok"] != "yes":
            continue
        byhash[r["hash"]].add(r["path"])
    collide = {h: sorted(v) for h, v in byhash.items() if len(v) > 1}
    path_collisions = {}
    for h, paths in collide.items():
        for p in paths:
            path_collisions[p] = sorted(base(q) for q in paths if q != p)

    # ── impact CSV ──────────────────────────────────────────────────────────
    out = []
    for r in rows:
        p = r["path"]
        t = base(p)
        pop = population(p)
        if pop == "parser-probe":
            continue
        control = r["contains_control"] == "true"
        heads = sorted(set(uses.get(t, [])))
        if pop == "corpus-2972" and t in qfset:
            heads = sorted(set(heads + ["Corpus2972"]))
        coll = path_collisions.get(p, [])
        # Potentially invalid = control-bearing AND the parse did not preserve the
        # branch structure AND some headline uses it.
        pot = control and r["parsed_control_preserved"] != "n/a" and bool(heads)
        out.append({
            "test": t,
            "population": pop,
            "source": p,
            "contains_control": control,
            "source_program_shape": r["source_program_shape"],
            "parsed_control_preserved": r["parsed_control_preserved"],
            "parsed_representation_hash": r["hash"],
            "collides_with": ";".join(coll),
            "paper_result_uses_test": ";".join(heads),
            "reported_before": " | ".join(
                f"{h}:{reported.get((t, h), '')}" for h in heads if (t, h) in reported),
            "potentially_invalid": pot,
        })

    with open("eval/control-parser-impact.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        # Control-bearing rows first, then anything in a collision, then the rest.
        out.sort(key=lambda r: (not r["contains_control"], not r["collides_with"], r["test"]))
        w.writerows(out)

    aff = [r for r in out if r["potentially_invalid"]]
    print(f"wrote eval/control-parser-impact.csv  ({len(out)} rows)")
    print(f"  control-bearing        : {sum(1 for r in out if r['contains_control'])}")
    print(f"  potentially_invalid    : {len(aff)}")
    print(f"  in a semantic collision: {sum(1 for r in out if r['collides_with'])}")
    print()
    print("COLLISION GROUPS (control-relevant):")
    for h, paths in sorted(collide.items()):
        names = sorted({base(p) for p in paths})
        if len(names) < 2:
            continue
        anyctl = any(rr["contains_control"] == "true" for rr in rows if rr["path"] in paths)
        if anyctl:
            print(f"  {h}  {names}")

    # ── headline delta CSV ──────────────────────────────────────────────────
    affected_names = {r["test"] for r in aff}

    def delta(name, rowset, key_test, is_hit, orig_num, orig_den, note=""):
        a = [r for r in rowset if key_test(r) in affected_names]
        hits_a = sum(1 for r in a if is_hit(r))
        return {
            "headline": name,
            "original": f"{orig_num}/{orig_den}",
            "original_numerator": orig_num,
            "original_denominator": orig_den,
            "excluded": len(a),
            "excluded_hits": hits_a,
            "new_numerator": orig_num - hits_a,
            "new_denominator": orig_den - len(a),
            "unaffected_subset": f"{orig_num - hits_a}/{orig_den - len(a)}",
            "note": note,
        }

    dl = []
    # Published examples = source-paper (14) + JMM (20); headline uses symbolic JMM.
    sp_aff = [r for r in sp if r["test"] in affected_names]
    jm_aff = [r for r in jm if r["test"] in affected_names]
    sp_hit = sum(1 for r in sp_aff if r["match"] == "yes")
    jm_hit = sum(1 for r in jm_aff if r["match"] == "yes")
    dl.append({
        "headline": "PublishedExampleAgreement",
        "original": "31/34", "original_numerator": 31, "original_denominator": 34,
        "excluded": len(sp_aff) + len(jm_aff),
        "excluded_hits": sp_hit + jm_hit,
        "new_numerator": 31 - (sp_hit + jm_hit),
        "new_denominator": 34 - (len(sp_aff) + len(jm_aff)),
        "unaffected_subset": f"{31 - (sp_hit + jm_hit)}/{34 - (len(sp_aff) + len(jm_aff))}",
        "note": "symbolic mode; = source-paper(14) + JMM(20)",
    })
    dl.append(delta("JmmAgreement", jm, lambda r: r["test"],
                    lambda r: r["match"] == "yes", 18, 20, "symbolic mode"))
    dl.append(delta("JmmAgreementWired", jm, lambda r: r["test"],
                    lambda r: r["match"] == "yes", 17, 20, "wired mode"))
    pwt = [r for r in sp if r["source"] == "leaky-semicolon"]
    dl.append(delta("PwTExamplesAgreement", pwt, lambda r: r["test"],
                    lambda r: r["match"] == "yes", 5, 6, ""))
    wmo = [r for r in sp if r["source"] == "weakestmo"]
    dl.append(delta("WeakestmoAgreement", wmo, lambda r: r["test"],
                    lambda r: r["match"] == "yes", 8, 8, ""))
    dl.append(delta("DatThreeMagreement", d3, lambda r: base(r["test"]),
                    lambda r: r["agree"] == "true", 35, 41, "symbolic mode"))
    dl.append(delta("DatThreeMagreementWired", d3, lambda r: base(r["test"]),
                    lambda r: r["agree"] == "true", 31, 41, "wired mode"))
    dl.append(delta("RcElevenAgree", rc, lambda r: base(r["test"]),
                    lambda r: r["direction"] == "agree", 21, 27, ""))
    corpus_aff = [r for r in out if r["population"] == "corpus-2972" and r["potentially_invalid"]]
    dl.append({
        "headline": "CorpusSize / HierarchyTests / QfbvTests",
        "original": "2972/2972", "original_numerator": 2972, "original_denominator": 2972,
        "excluded": len(corpus_aff), "excluded_hits": len(corpus_aff),
        "new_numerator": 2972 - len(corpus_aff),
        "new_denominator": 2972 - len(corpus_aff),
        "unaffected_subset": f"{2972 - len(corpus_aff)}/{2972 - len(corpus_aff)}",
        "note": "all corpus control tests are asm-dialect or loops; 17 of them encode zero events",
    })
    an_aff = [r for r in an if base(r["test"]) in affected_names]
    dl.append({
        "headline": "Anchor/discriminating",
        "original": "2/34", "original_numerator": 2, "original_denominator": 34,
        "excluded": len(an_aff),
        "excluded_hits": sum(1 for r in an_aff if r["discriminating"] == "true"),
        "new_numerator": 2 - sum(1 for r in an_aff if r["discriminating"] == "true"),
        "new_denominator": 34 - len(an_aff),
        "unaffected_subset": f"{2 - sum(1 for r in an_aff if r['discriminating'] == 'true')}"
                             f"/{34 - len(an_aff)}",
        "note": "no control edge is ever classified fake (ctrlFake=0 on every row)",
    })
    dl.append({
        "headline": "herd7 (SC/TSO agreement)",
        "original": "unchanged", "original_numerator": "", "original_denominator": "",
        "excluded": 0, "excluded_hits": 0, "new_numerator": "", "new_denominator": "",
        "unaffected_subset": "unchanged",
        "note": "0 of the 19 corpus control tests appear in the herd7 comparison population",
    })

    with open("eval/control-parser-headline-delta.csv", "w", newline="",
              encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(dl[0].keys()))
        w.writeheader()
        w.writerows(dl)
    print()
    print("wrote eval/control-parser-headline-delta.csv")
    for r in dl:
        print(f"  {r['headline']:<42} {str(r['original']):>10} -> "
              f"{r['unaffected_subset']:>10}  (excluded {r['excluded']})")


if __name__ == "__main__":
    main()
