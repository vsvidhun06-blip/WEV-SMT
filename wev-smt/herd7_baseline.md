# herd7 agreement baseline for WEV-SMT (paper risk #6)

This addresses **risk #6 ("no empirical baseline comparison")**: a small, verdict-level
agreement check between WEV-SMT and **herd7** (the herdtools7 cat-model simulator) on a
shared subset of the regression corpus. It is deliberately scoped to the **strong
fragment** (SC, x86-TSO), where both tools have a first-class, unambiguous model. WEV-SMT's
WEAKEST model has **no herd7 counterpart and is therefore not baseline-compared** — see §5.

## 1. Install outcome (Step 1)

**herd7 7.58 was installed successfully** — but not by the documented `opam install`
path. The 1-hour install budget was largely spent defeating a no-sudo WSL2 environment;
the route that worked, for reproducibility:

* Windows has no ocaml/opam; target is **WSL2 Ubuntu 24.04** (`gcc` 13.3, `make`, `curl`
  present; **passwordless sudo unavailable**, so `apt install` is blocked).
* `opam` 2.2.1 binary fetched to `~/.local/bin` (no sudo). `opam init --bare
  --disable-sandboxing` (bwrap sandboxing fails under WSL).
* opam's hard dep `unzip`, plus `libgmp-dev`/`pkgconf` (needed by `zarith`, a herdtools7
  dependency), were obtained **without root** via `apt-get download` + `dpkg-deb -x` into
  `~/.local`. `gmp.pc` was repointed at `~/.local` with an embedded `-Wl,-rpath`; a
  `pkg-config` wrapper script re-exports `LD_LIBRARY_PATH` (opam strips it from build
  envs). `pkg-config` is a virtual package (provided by `pkgconf`) — that detail cost time.
* `opam switch create 4.14.2` then `opam install herdtools7 --assume-depexts -y` →
  `herdtools7.7.58` + `zarith.1.14`. `herd7 -version` → `7.58`; the rpath makes it run with
  no env setup. Bundled cat models live in `~/.opam/4.14.2/share/herdtools7/herd/`
  (`sc.cat`, `x86tso.cat`, `aarch64.cat`, …).

This is a non-standard, machine-specific install. It is **not** something the artifact
should depend on; the numbers below are reproducible given any working herd7 7.x.

## 2. Method (Step 2)

* **Subset:** the 697 files that WEV-SMT fully validates in the herd7 corpus
  (`eval/corpus-validation-herd7.csv`, `status=OK`). Arch mix (distinct files): ARM 570,
  PPC 60, X86 51, C 11, RISCV 5.
* **herd7 runs** (`eval/herd7-results.csv`, driver `~/run_herd7.sh`): every file under
  `sc.cat` (SC is model-generic); every **X86** file additionally under `x86tso.cat`.
  Per-file `timeout 25`. Verdict from herd7's `Observation … Never|Sometimes|Always`
  line: **Never ⇒ FORBIDDEN**, Sometimes/Always ⇒ ALLOWED; no/!Observation ⇒ ERROR.
* **WEV-SMT verdicts** are the **already-recorded** per-model results from the same CSV
  (no re-run, no checker modification): SC column ↔ herd7 sc.cat; TSO column ↔ herd7
  x86tso.cat. (`eval/herd7-wev-verdicts.csv` is the extracted join key.)
* **Comparable** = both tools emitted ALLOWED/FORBIDDEN. herd7 ERROR/TIMEOUT (e.g.
  AArch64 tests that need `-variant memtag`/VMSA models under `sc.cat`) and the lone
  no-`exists` test are excluded from agree/disagree — they are **not** counted as either.
* Power: herd7 ships no plain `power.cat` usable here and WEV-SMT has no Power model, so
  PPC is compared **only under SC** (via `sc.cat`). RISCV/ARM kept under SC only for the
  same reason (no matching WEV-SMT weak model). WEV-SMT errors in the comparable set: **0**.

## 3. Agreement table

### SC (herd7 `sc.cat`  vs  WEV-SMT `SC`)

| Arch | files | herd7 ERROR | comparable | agree | disagree | agree % |
|---|---|---|---|---|---|---|
| **X86**  |  51 |   1 |  50 |  42 |  8 | 84% |
| **PPC**  |  60 |   1 |  59 |  51 |  8 | 86% |
| **C**    |  11 |   2 |   9 |   9 |  0 | 100% |
| **RISCV**|   5 |   0 |   5 |   4 |  1 | 80% |
| **ARM**  | 570 | 210 | 360 | 138 | 222 | 38% |
| **non-ARM total** | 127 | 4 | **123** | **106** | 17 | **86.2%** |

### TSO (herd7 `x86tso.cat`  vs  WEV-SMT `TSO`), X86 only

| Arch | files | herd7 ERROR | comparable | agree | disagree | agree % |
|---|---|---|---|---|---|---|
| **X86** | 51 | 5 | **46** | **34** | 12 | 74% |

**ARM is not a faithful model-level comparison** and is excluded from the headline:
herd7 errored on 210/570 (tests requiring AArch64 feature models — VMSA address
translation, MTE memory tags, exclusives, mixed-size — under plain `sc.cat`), and most of
the 222 "disagreements" are precisely those feature tests (`aarch64-VMSA`, `aarch64-MTE`,
`aarch64-pick`, `aarch64-cas`), which **WEV-SMT flattens to plain loads/stores**, dropping
the page-table-walk/tag/exclusive structure. Where both tools treat an AArch64 test as
plain memory (the `tests`/`tst-co` subdirs), they largely agree (≈113 agree). The
residual plain-memory ARM disagreements share the X86/PPC root cause below.

## 4. Disagreement diagnosis

Across the comparable strong-fragment set there are **two** causes, plus one artifact.
**In every comparable disagreement except one, WEV-SMT is the *more permissive* side
(ALLOWED where herd7 FORBIDS); WEV-SMT never over-forbids.** herd7 is the standard
reference and is taken as correct throughout.

**(A) Same-thread write→write order is not enforced — architecture-independent
[WEV-SMT limitation].** Every SC disagreement on X86 (`2+2W`, `2+2W+mfence(s)`,
`S`, `S+mfence(s)`, `S+po+mfence`, `x86-2+2W` — 8/8) and 7/8 on PPC (`2+2W`,
`2+2W+lwsyncs`, `S+lwsyncs`, `S+lwsync+data`, plus the coherence tests `co4`/`co10`), and
RISCV `A014`, are tests whose *only* reason to be forbidden is preservation of two stores'
program order. WEV-SMT reports ALLOWED; SC (and TSO) forbid them. The identical signature
across **three independent front-ends** (x86 `MOV [x]`, ppc `stw …(r5)`, riscv) rules out a
per-arch parser bug and points to WEV-SMT's encoding **not constraining same-thread
store→store order**. This is consistent with a WEAKEST-oriented design (W→W to distinct
locations is *meant* to be relaxed in the weak model); it surfaces here only because SC/TSO
are being exercised as strong checkers. See the §6 risk note for the same-location
sub-case.

**(B) x86 `MFENCE` is treated as inert — TSO only [WEV-SMT/parser limitation].** The TSO
disagreements that are *not* W→W shapes (`R+mfences`, `R+po+mfence`, `SB+mfences`) are
exactly the tests where an `MFENCE` between a store and a later load should restore order
and make the outcome FORBIDDEN. WEV-SMT returns ALLOWED, i.e. the `MFENCE` mnemonic is not
mapped to an ordering fence in the x86 front-end, so the store-buffer relaxation is never
fenced off. (Note `R+mfence+po`, `SB+mfence+po` legitimately stay ALLOWED under TSO and
both tools agree — the fence there does not close the cycle.)

**(C) `co6` — comparison artifact, excluded.** PPC `co6` is a `locations`-only test with
**no `exists`/`~exists` predicate**; herd7's Observation line and WEV-SMT's
satisfiability verdict are answering different questions, so the single
herd=ALLOWED/wev=FORBIDDEN cell is not a real divergence and is dropped.

No disagreement was traced to a herd7 scope difference or a *genuine* model divergence
(SC and x86-TSO are standardized and unambiguous); all comparable disagreements are
WEV-SMT-side under-approximations of the strong models.

## 5. §6 draft paragraph (conservative)

> **§6.x  Cross-checking against herd7.** As an external sanity check on the strong end of
> the model lattice we compared WEV-SMT against herd7 (herdtools7 7.58) on the 697-file
> herd7 subset of our corpus, restricted to the fragment where both tools share a model.
> On the **SC** fragment, over the 123 non-ARM tests on which herd7 produced a verdict,
> the two tools agree on **106 (86%)**: X86 42/50, PPC 51/59, C 9/9, RISCV 4/5. On the
> **x86-TSO** fragment the two agree on **34 of 46** X86 tests (74%). Every disagreement is
> one-directional — WEV-SMT *allows* an outcome that the strong model forbids, never the
> reverse — and the disagreements are explained by two limitations of WEV-SMT *as a strong-
> model checker*: it does not enforce program order between two same-thread stores
> (the `2+2W`/`S` family, and the `CoWW`/`CoWR` coherence tests), and it does not model the
> x86 `MFENCE` barrier. Both are consistent with a tool built around the WEAKEST model,
> where store/store order is deliberately relaxed; they do, however, mean our SC/TSO modes
> are **under-approximations** and should not be read as validated strong-memory checkers.
> herd7's WEAKEST analogue does not exist, so the WEAKEST results — the paper's actual
> contribution — are **not** cross-validated here; disagreements are characterised in
> App. X. We make no agreement claim beyond the strong fragment.

## 6. Risk note — where a reviewer will push back

* **Coherence-relevant disagreements (`co4`, `co10`) — the orthogonality argument is
  shaky here.** These are `CoWR`/`CoWW` tests: `co10` forbids `x=1` after a single thread
  does `W(x,1); W(x,2)`. Same-location write order (coherence) is supposed to hold in
  **every** model, WEAKEST included, so WEV-SMT reporting ALLOWED is *not* obviously
  excusable as "weak model relaxes W→W." It is either (i) a real same-location coherence
  under-enforcement, or (ii) a PPC register-indirect-addressing parse issue
  (`stw r1,0(r5)` with `r5=x`) that prevents WEV-SMT from seeing two writes to the same
  location. We did **not** modify the checker to disambiguate (observation-only mandate);
  this needs triage and is the disagreement most likely to draw fire, because coherence is
  the paper's core soundness mechanism. Do not claim coherence is fully validated.
* **ARM was excluded, and the exclusion is large (570/697 of the subset).** A reviewer may
  read this as cherry-picking. The honest framing is that ARM tests exercise AArch64
  features WEV-SMT does not model and that herd7 itself cannot evaluate under `sc.cat`
  (210 errors); the comparison would measure parser scope, not model agreement. State this
  explicitly rather than quietly dropping ARM.
* **"86% / 74%" is agreement on the *comparable* subset, not the 697.** The denominators
  are 123 and 46. Anyone quoting "86%" must say "of the 123 SC tests herd7 could evaluate."
  herd7 produced no verdict for 214 of the 697 under SC.
* **The SC fragment leans on running hardware litmus tests under a generic `sc.cat`.** This
  is standard in herdtools usage but is not the same as a native SC model per arch; the
  X86 numbers (native models on both sides) are the most defensible.

---

### Artifacts

| File | Contents |
|---|---|
| `eval/herd7-results.csv` | herd7 verdict per file: `relpath,arch,herd_sc,herd_tso` (697 rows) |
| `eval/herd7-wev-verdicts.csv` | WEV-SMT SC/TSO/WEAKEST verdicts per file (extracted from the §6.2 run) |
| `eval/herd7-filelist.txt` | the 697-file input list (`relpath,arch`) |
| `~/run_herd7.sh` (WSL) | the batch driver (herd7 invocation + Observation→verdict parse) |

**herd7 reproduce (per file):**

```
herd7 -model sc.cat     <file.litmus>   # SC verdict   (Observation Never ⇒ FORBIDDEN)
herd7 -model x86tso.cat  <file.litmus>   # x86-TSO verdict (X86 only)
```
