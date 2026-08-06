#!/usr/bin/env python3
"""
Phase A step 2+3 — mine fake-dependency candidates out of real -O2 compiler output and
emit litmus tests mechanically.

Reads  : eval/mined/asm/*.s      (produced by tools/compile_all.sh)
Writes : eval/mined/litmus/*.litmus
         eval/mined/manifest.csv
         eval/mined/candidates.csv   (every load->store block, classified)

Method
------
For each function in each .s file:

 1. Split into instructions; keep the subset the wev-smt asm front end models
    (load / store / move / add / sub / and / or / eor / mul / cmp / branch).
 2. Build register dataflow from the FIRST memory load in the block to the value operand
    of a subsequent store. If no such flow exists, the block carries no data dependency
    and is skipped (recorded in candidates.csv with flow=none).
 3. Symbolically evaluate the stored value as a function of the loaded value over 32-bit
    two's-complement arithmetic. The evaluation is exact (a small interpreter over
    Python ints with masking), sampled at a structured + random point set. A block whose
    stored value is IDENTICAL at every sample point is a fake-dependency CANDIDATE; one
    that varies is a real dependency. This is a SCREEN, not the verdict — the verdict is
    whatever wev-smt's frozen detector says in Phase B.
 4. Every candidate (and every real-dependency negative control) is emitted as a
    two-thread load-buffering litmus test whose thread bodies are the MINED INSTRUCTIONS
    VERBATIM, with only register names and base addresses rewritten to litmus form. No
    instruction is invented, reordered, or added.

Run from repo root:  python eval/mined/tools/extract.py
"""

import csv
import os
import random
import re
import sys

ASM_DIR = os.path.join("eval", "mined", "asm")
LIT_DIR = os.path.join("eval", "mined", "litmus")
MANIFEST = os.path.join("eval", "mined", "manifest.csv")
CANDIDATES = os.path.join("eval", "mined", "candidates.csv")

M32 = 0xFFFFFFFF


def s32(v):
    v &= M32
    return v - (1 << 32) if v & 0x80000000 else v


# ── assembly parsing ─────────────────────────────────────────────────────────────

FUNC_START = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
DIRECTIVE = re.compile(r"^\s*\.")
COMMENT = re.compile(r"^\s*(//|#|;)")


def split_functions(path):
    """Yield (funcname, [instruction strings]) for each function body in a .s file."""
    funcs = {}
    cur = None
    with open(path, "r", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            # Strip trailing comments. `//` always starts a comment. `#` starts one ONLY
            # at the beginning of a line (x86 GAS style) -- on AArch64 `#` introduces an
            # immediate operand (`add w8, w8, #1`) and must never be treated as a
            # comment, or the immediate is silently truncated.
            line = line.split("//")[0].rstrip()
            if re.match(r"^\s*#", line) and not re.match(r"^\s*#(APP|NO_APP)\b", line):
                continue
            stripped = line.strip()
            if not stripped:
                continue
            m = FUNC_START.match(stripped)
            if m and not DIRECTIVE.match(line):
                cur = m.group(1)
                if cur.startswith(".L"):
                    cur = None
                    continue
                funcs[cur] = []
                continue
            if cur is None:
                continue
            if stripped.startswith(".L") and stripped.endswith(":"):
                continue  # local label: keep collecting (straight-line approximation)
            if DIRECTIVE.match(line) or COMMENT.match(line):
                continue
            funcs[cur].append(stripped)
    return funcs


# ── a tiny symbolic interpreter over the modelled instruction subset ─────────────

class Block:
    """Straight-line register dataflow for one function body."""

    def __init__(self, insns, arch):
        self.insns = insns
        self.arch = arch

    def evaluate(self, loaded):
        """Run the block with the first load returning `loaded`.

        Returns (stored_value, load_reg, tainted, used_insns) or None when the block has
        no load and no store at all. Registers not derived from the load are modelled as
        opaque stable constants.

        `tainted` records whether the stored value is SYNTACTICALLY reachable from the
        load in the register dataflow. This is what separates the two ways a stored value
        can fail to vary with the loaded value:

          tainted=False -> the dependency is ABSENT: the compiler already removed every
                           path from the load to the store. There is no edge to classify.
          tainted=True  -> the dependency is PRESENT in the register dataflow but the
                           value is constant: a genuine FAKE dependency, the target of
                           this exercise.

        Conflating the two is what makes a naive constancy screen report a huge and
        meaningless "fake" count, since most blocks simply never route the loaded value
        to the store.
        """
        regs = {}
        taint = {}
        opaque = {}
        load_reg = None
        store_val = None
        store_taint = False
        saw_store = False
        used = []

        def val(tok):
            tok = tok.strip()
            if tok in regs:
                return regs[tok]
            m = re.match(r"^[#$]?(-?\d+)$", tok)
            if m:
                return s32(int(m.group(1)))
            m = re.match(r"^[#$]?0[xX]([0-9a-fA-F]+)$", tok)
            if m:
                return s32(int(m.group(1), 16))
            if tok not in opaque:
                opaque[tok] = s32(hash(tok) & 0x7FFFFFFF)
            return opaque[tok]

        def tnt(tok):
            return taint.get(tok.strip(), False)

        for ins in self.insns:
            if ins in ("#APP", "#NO_APP", "//APP", "//NO_APP", "APP", "NO_APP"):
                continue
            parts = re.split(r"[\s,]+", ins.strip())
            op = parts[0].lower().rstrip(".")
            ops = [p for p in parts[1:] if p]

            # taint of the operands, computed before the step mutates regs
            src_taint = any(tnt(o) for o in ops[1:]) if len(ops) > 1 else False

            if self.arch == "aarch64":
                r = self._step_arm(op, ops, regs, val, ins)
                dst = ops[0] if ops else None
                store_src = ops[0] if ops else None
            else:
                r = self._step_x86(op, ops, regs, val, ins)
                dst = ops[1] if len(ops) > 1 else (ops[0] if ops else None)
                store_src = ops[0] if ops else None
                # AT&T: `op src, dst` -- for ALU the destination is also a source
                if op in ("addl", "subl", "xorl", "andl", "orl", "imull") and len(ops) > 1:
                    src_taint = tnt(ops[0]) or tnt(ops[1])
                elif op in ("incl", "decl") and ops:
                    src_taint = tnt(ops[0])
                elif op in ("movl", "movq", "mov") and len(ops) > 1:
                    src_taint = tnt(ops[0])

            if r is None:
                continue
            kind, payload = r
            if kind == "load" and load_reg is None:
                load_reg = payload
                regs[payload] = s32(loaded)
                taint[payload] = True
                used.append(ins)
            elif kind == "load":
                regs[payload] = val("__load2__")
                taint[payload] = False
                used.append(ins)
            elif kind == "loadvoid":
                used.append(ins)
            elif kind == "store":
                if not saw_store:
                    saw_store = True
                    store_val = payload
                    store_taint = tnt(store_src)
                    used.append(ins)
            elif kind == "alu":
                if dst is not None:
                    taint[dst] = src_taint
                used.append(ins)

        if load_reg is None or not saw_store:
            return None
        return store_val, load_reg, store_taint, used

    # AArch64 ---------------------------------------------------------------
    def _step_arm(self, op, ops, regs, val, ins):
        if op in ("ldr", "ldar", "ldapr", "ldrb", "ldrh", "ldaprb") and len(ops) >= 2:
            dst = ops[0]
            if dst in ("wzr", "xzr"):
                return ("loadvoid", dst)
            return ("load", dst)
        if op in ("str", "stlr", "strb", "strh") and len(ops) >= 2:
            return ("store", val(ops[0]))
        if op in ("mov", "movz", "movw") and len(ops) >= 2:
            regs[ops[0]] = val(ops[1])
            return ("alu", None)
        if op == "mvn" and len(ops) >= 2:
            regs[ops[0]] = s32(~val(ops[1]))
            return ("alu", None)
        if op in ("add", "sub", "eor", "and", "orr", "mul") and len(ops) >= 3:
            a, b = val(ops[1]), val(ops[2])
            regs[ops[0]] = {
                "add": lambda: s32(a + b),
                "sub": lambda: s32(a - b),
                "eor": lambda: s32(a ^ b),
                "and": lambda: s32(a & b),
                "orr": lambda: s32(a | b),
                "mul": lambda: s32(a * b),
            }[op]()
            return ("alu", None)
        return None

    # x86_64 ----------------------------------------------------------------
    def _step_x86(self, op, ops, regs, val, ins):
        # AT&T order: op src, dst
        if op in ("movl", "movq", "mov") and len(ops) >= 2:
            src, dst = ops[0], ops[1]
            if self._is_mem(src) and not self._is_mem(dst):
                return ("load", dst)
            if self._is_mem(dst) and not self._is_mem(src):
                return ("store", val(src))
            regs[dst] = val(src)
            return ("alu", None)
        if op in ("addl", "subl", "xorl", "andl", "orl", "imull") and len(ops) >= 2:
            src, dst = ops[0], ops[1]
            if self._is_mem(dst):
                return None
            a, b = val(dst), val(src)
            regs[dst] = {
                "addl": lambda: s32(a + b),
                "subl": lambda: s32(a - b),
                "xorl": lambda: s32(a ^ b),
                "andl": lambda: s32(a & b),
                "orl": lambda: s32(a | b),
                "imull": lambda: s32(a * b),
            }[op]()
            return ("alu", None)
        if op in ("incl", "decl") and len(ops) >= 1:
            if self._is_mem(ops[0]):
                return None
            regs[ops[0]] = s32(val(ops[0]) + (1 if op == "incl" else -1))
            return ("alu", None)
        return None

    @staticmethod
    def _is_mem(tok):
        return "(" in tok


SAMPLE_POINTS = [0, 1, 2, 3, 7, 8, 15, 16, 31, 42, 255, 256, 1023, 65535, 65536,
                 -1, -2, -7, -256, 0x7FFFFFFF, -0x80000000, 0x55555555, 0x2AAAAAAA]


# Instructions whose presence makes the straight-line interpreter's answer unsound.
#
# A call transfers control to code this tool never sees: two calls to the same function
# are NOT guaranteed to return the same value, so a `sub` of their results is a GENUINE
# dependency even though a walker that skips calls will see both operands unchanged and
# report cancellation. A conditional branch means the walker is following a path the
# processor may not take, so neither the dataflow nor the store it lands on is
# trustworthy. Both cases produced concrete false "fake" screens before this guard:
# `opaque_fakedep.c::ofd_opaque_cancel` (two `bl opaque_identity` results subtracted) and
# `mpmc_ring.c::mpmc_pop` (a two-deep CAS retry loop).
CALL_OPS = ("bl", "blr", "call", "callq")
BRANCH_OPS = ("b", "br", "cbz", "cbnz", "tbz", "tbnz", "beq", "bne", "blt", "bgt",
              "bge", "ble", "bhi", "bls", "bcs", "bcc", "bmi", "bpl", "bvs", "bvc",
              "je", "jne", "jmp", "jg", "jl", "jge", "jle", "ja", "jb", "jae", "jbe",
              "js", "jns", "jz", "jnz", "cbnz")


def block_is_straightline(insns):
    """False when the block contains a call or a branch (screen result unusable)."""
    for ins in insns:
        op = re.split(r"[\s,]+", ins.strip())[0].lower().rstrip(".")
        if op in CALL_OPS or op in BRANCH_OPS:
            return False
        if op.startswith("cmov") or op.startswith("cset") or op.startswith("csel") \
                or op.startswith("csinc") or op.startswith("cinc"):
            return False        # predicated: a second path exists
    return True


def classify(insns, arch):
    """Screen a block.

    Returns (screen, load_reg, used_insns) where screen is one of:
        'fake'      -- the loaded value REACHES the store but the stored value is constant
        'real'      -- the loaded value reaches the store and the stored value varies
        'absent'    -- no dataflow from the load to the store (dependency already erased)
        'nonlinear' -- the block has a call/branch, so the screen cannot be trusted
        None        -- the block has no load, or no store, at all
    """
    if not block_is_straightline(insns):
        return "nonlinear", None, None
    blk = Block(insns, arch)
    rng = random.Random(20260728)
    pts = SAMPLE_POINTS + [rng.randint(-(1 << 31), (1 << 31) - 1) for _ in range(200)]
    vals = []
    load_reg = None
    used = None
    tainted = False
    for p in pts:
        r = blk.evaluate(p)
        if r is None:
            return None, None, None
        v, load_reg, tainted, used = r
        vals.append(v)
    if not tainted:
        return "absent", load_reg, used
    return ("fake" if len(set(vals)) == 1 else "real"), load_reg, used
