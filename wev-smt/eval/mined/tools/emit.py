#!/usr/bin/env python3
"""
Phase A step 3 — turn mined candidates into litmus tests + manifest.

Consumes the parsing/classification machinery of extract.py and writes:
    eval/mined/litmus/*.litmus
    eval/mined/manifest.csv
    eval/mined/candidates.csv

The emitted instruction sequence is the compiler's own, VERBATIM, except for one
mechanical transformation: SSA alpha-renaming of registers into litmus form. That
renaming keeps the loaded value live (so the `exists` clause can name it) and preserves
the dataflow graph exactly. Opcodes, operand order, immediates and instruction count are
never changed, and nothing is inserted or removed.

Run from repo root:  python eval/mined/tools/emit.py
"""

import csv
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import Block, classify, split_functions, ASM_DIR, LIT_DIR, MANIFEST, CANDIDATES  # noqa: E402

ARM_LOAD = ("ldr", "ldar", "ldapr", "ldrb", "ldrh", "ldaprb")
ARM_STORE = ("str", "stlr", "strb", "strh")
ARM_ALU3 = ("add", "sub", "eor", "and", "orr", "mul")
ARM_MOV = ("mov", "movz", "movw", "mvn")

APP_MARKERS = ("#APP", "#NO_APP", "//APP", "//NO_APP")


def ssa_rename_arm(insns, copyprop=False):
    """Rewrite a mined AArch64 block into litmus registers (SSA alpha-renaming).

    Returns (lines, store_reg) or None when the block is not the modelled
    straight-line load -> alu* -> store shape.

    With copyprop=True one further semantics-preserving rewrite is applied: a
    register-to-register `MOV Wd,Ws` is deleted and Wd is aliased to Ws's litmus
    register. This is ordinary copy propagation -- it changes no value the block
    computes. It exists to measure a counterfactual: the shipped detector matches its
    cancellation idioms (`r-r`, `r^r`) on register NAMES within a single expression, so
    the compiler's `MOV W1,W0 ; SUB W2,W0,W1` is invisible to it while the semantically
    identical `SUB W2,W0,W0` is not. Copy-propagating first tells us how many mined
    tests would discriminate if the detector did this normalisation itself.

    The copy-propagated tests are NOT part of the mined suite -- they are not what the
    compiler emitted. They are reported separately as the W2 counterfactual.
    """
    cur = {}
    out = []
    next_id = [1]
    load_seen = False
    store_reg = None

    def fresh():
        r = "W%d" % next_id[0]
        next_id[0] += 1
        return r

    # Immediates are emitted BARE (`ADD W3,W2,1`), not in AArch64 `#`-prefixed form.
    #
    # This is a dialect adaptation forced by a defect in the tool's front end, not a
    # stylistic choice. LitmusParser.lexRegisterAsm() strips `#` on the load-immediate
    # path (`MOV W3,#1` parses correctly) but NOT on the arithmetic path: `isArith`
    # builds the expression as `ops.get(1) + opSym(op) + ops.get(2)` with no stripImm(),
    # so `ADD W1,W0,#1` yields the expression `W0+#1`. The `#` then triggers comment
    # handling and the remainder of the row is swallowed -- measured effect: the
    # expression becomes `W0+STR W1`, the thread loses an event, and ALL dependency
    # edges for that thread vanish (deps=0 instead of 2). See findings-mined-suite.md.
    def rd(tok):
        t = tok.strip()
        if t in cur:
            return cur[t]
        if t in ("wzr", "xzr"):
            return "0"
        if re.match(r"^#?-?\d+$", t):
            return t.lstrip("#")
        if re.match(r"^#?0[xX][0-9a-fA-F]+$", t):
            return str(int(t.lstrip("#"), 16))
        return None

    for ins in insns:
        if ins in APP_MARKERS:
            continue
        parts = re.split(r"[\s,]+", ins.strip())
        op = parts[0].lower()
        ops = [p for p in parts[1:] if p]

        if op in ARM_LOAD and len(ops) >= 2 and not load_seen:
            if ops[0] in ("wzr", "xzr"):
                return None            # value discarded: no machine-level dependency
            load_seen = True
            cur[ops[0]] = "W0"
            out.append("LDR W0,[X1]")
            continue
        if op in ARM_STORE and len(ops) >= 2:
            src = rd(ops[0])
            if src is None or not load_seen:
                return None
            store_reg = src
            out.append("STR %s,[X2]" % src)
            break
        if op in ARM_MOV and len(ops) >= 2:
            src = rd(ops[1])
            if src is None:
                return None
            if copyprop and op != "mvn" and not src.startswith("#") and not src.isdigit():
                cur[ops[0]] = src          # copy propagation: alias, emit nothing
                continue
            d = fresh()
            out.append("%s %s,%s" % (op.upper(), d, src))
            cur[ops[0]] = d
            continue
        if op in ARM_ALU3 and len(ops) >= 3:
            a, b = rd(ops[1]), rd(ops[2])
            if a is None or b is None:
                return None
            d = fresh()
            out.append("%s %s,%s,%s" % (op.upper(), d, a, b))
            cur[ops[0]] = d
            continue
        return None

    if not load_seen or store_reg is None:
        return None
    return out, store_reg


# NOTE on comment syntax: LitmusParser.commentText() recognises a line as a comment only
# when the STRIPPED line itself starts with `(*`, `#` or `//`. A block comment whose
# continuation lines start with ` * ` is therefore NOT ignorable and aborts the parse at
# the initial-state block. Every comment line below is consequently its own `(* ... *)`.
TMPL = '''AArch64 {name}
(* MINED TEST -- generated by eval/mined/tools/emit.py; do not edit by hand.               *)
(* provenance : {src} :: {func}()                                                          *)
(* compiler   : {tool} -O2 -S -std=c11 (exact version in eval/mined/asm/TOOLCHAIN.txt)     *)
(* algorithm  : see the PROVENANCE header of eval/mined/src/{src}                          *)
(*                                                                                         *)
(* Mined instruction sequence, verbatim from eval/mined/asm/{asmfile}:                     *)
{rawblock}
(*                                                                                         *)
(* Only transformation applied: SSA alpha-renaming of registers into litmus form.          *)
(* Opcodes, operand order, immediates and instruction count are the compiler's own.        *)
(*                                                                                         *)
(* screen      : stored value {verdict} in the loaded value{constnote}                      *)
(* fake edge   : {fake_kind}                                                               *)
(* on LB cycle : yes -- both threads run this block, P0 x->y and P1 y->x                   *)
{{
x=0; y=0;
0:X1=x; 0:X2=y;
1:X1=y; 1:X2=x;
}}
 P0                        | P1                        ;
{body}
exists (0:W0={c0} /\\ 1:W0={c1})
'''


def idiom_of(insns):
    j = " ; ".join(i.lower() for i in insns)
    names = []
    if re.search(r"\beor\b", j):
        names.append("xor-cancel")
    if re.search(r"\bsub\b", j):
        names.append("sub-cancel")
    if re.search(r"\band\b", j):
        names.append("and-mask")
    if re.search(r"\bmul\b", j):
        names.append("mul")
    if re.search(r"\borr\b", j):
        names.append("or-set")
    if re.search(r"\bmov\b", j):
        names.append("copy-through")
    return "+".join(names) if names else "plain"


def main():
    # `--copyprop` emits the W2 counterfactual suite into eval/mined/litmus-cp/ instead.
    copyprop = "--copyprop" in sys.argv
    lit_dir = (LIT_DIR + "-cp") if copyprop else LIT_DIR
    os.makedirs(lit_dir, exist_ok=True)
    cand_rows, man_rows = [], []

    for fn in sorted(os.listdir(ASM_DIR)):
        if not fn.endswith(".s"):
            continue
        m = re.match(r"^(.*)\.(clang|gcc)-(x86_64|aarch64)\.s$", fn)
        if not m:
            continue
        base, comp, arch = m.groups()
        arch_key = "aarch64" if arch == "aarch64" else "x86"

        for func, insns in sorted(split_functions(os.path.join(ASM_DIR, fn)).items()):
            if not insns:
                continue
            verdict, _lreg, _used = classify(insns, arch_key)
            row = {
                "asm_file": fn, "source": base + ".c", "func": func,
                "compiler": comp, "arch": arch,
                "flow": {None: "no-load-or-store", "absent": "erased",
                         "nonlinear": "not-straightline"}.get(verdict, "load->store"),
                "screen": verdict or "",
                "emitted": "no", "reason": "",
                "n_insns": len(insns),
            }
            cand_rows.append(row)

            if verdict is None:
                row["reason"] = "block has no load, or no store"
                continue
            if verdict == "nonlinear":
                row["reason"] = ("block contains a call or branch: straight-line screen "
                                 "not sound, excluded rather than mis-screened")
                continue
            if verdict == "absent":
                row["reason"] = "loaded value never reaches the store: dependency erased by the compiler"
                continue
            if arch_key != "aarch64":
                row["reason"] = "x86: LB forbidden by TSO regardless of dependency"
                continue

            ren = ssa_rename_arm(insns, copyprop=copyprop)
            if ren is None:
                row["reason"] = "block outside the modelled straight-line load/alu/store shape"
                continue
            lines, store_reg = ren

            blk = Block(insns, arch_key)
            ev = blk.evaluate(1)
            const = ev[0] if ev else 1
            outcome = const if verdict == "fake" else 1

            name = "mined_%s_%s_%s" % (func, comp, arch)
            raw = [i for i in insns if i not in APP_MARKERS]
            body = "".join(" %-25s| %-25s;\n" % (l, l) for l in lines)
            txt = TMPL.format(
                name=name, src=base + ".c", func=func, tool=comp, asmfile=fn,
                rawblock="\n".join(
                    "(*     %-80s *)" % re.sub(r"\s+", " ", r) for r in raw),
                verdict="is CONSTANT" if verdict == "fake" else "VARIES",
                constnote=(" (= %d at every sample point)" % const) if verdict == "fake" else "",
                fake_kind="data (value cancellation)" if verdict == "fake"
                          else "none -- genuine data dependency (negative control)",
                body=body, c0=outcome, c1=outcome)
            with open(os.path.join(lit_dir, name + ".litmus"), "w") as fh:
                fh.write(txt)

            row["emitted"] = "yes"
            man_rows.append({
                "test": name,
                "source": base + ".c::" + func,
                "arch": arch,
                "compiler": comp,
                "idiom": idiom_of(insns),
                "fake_edge_kind": "data" if verdict == "fake" else "none",
                "on_lb_cycle": "y",
                "screen": verdict,
                "store_const": const if verdict == "fake" else "",
                "asm_file": fn,
            })

    if copyprop:
        print("counterfactual (copy-propagated) suite -> %s" % lit_dir)
        print("  tests emitted: %d (fake-carrying %d)" % (len(man_rows), sum(1 for r in man_rows if r["screen"]=="fake")))
        return
    with open(CANDIDATES, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(cand_rows[0].keys()))
        w.writeheader()
        w.writerows(cand_rows)
    with open(MANIFEST, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(man_rows[0].keys()))
        w.writeheader()
        w.writerows(man_rows)

    print("blocks scanned        : %d" % len(cand_rows))
    print("  with load->store    : %d" % sum(1 for r in cand_rows if r["flow"] == "load->store"))
    print("  not straight-line   : %d" % sum(1 for r in cand_rows if r["screen"] == "nonlinear"))
    print("  dep ABSENT (erased) : %d" % sum(1 for r in cand_rows if r["screen"] == "absent"))
    print("  screened FAKE       : %d" % sum(1 for r in cand_rows if r["screen"] == "fake"))
    print("  screened REAL       : %d" % sum(1 for r in cand_rows if r["screen"] == "real"))
    print("litmus tests emitted  : %d  -> %s" % (len(man_rows), lit_dir))
    print("  fake-carrying       : %d" % sum(1 for r in man_rows if r["screen"] == "fake"))
    print("  real (controls)     : %d" % sum(1 for r in man_rows if r["screen"] == "real"))


if __name__ == "__main__":
    sys.exit(main())
