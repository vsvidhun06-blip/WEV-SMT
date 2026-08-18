# `eval/corpus/` — what is shipped, and what is not

This directory ships the **minimal manifest-referenced reproduction subset**: exactly the
litmus files the paper's measurements read, plus the LKMM model. It is **not** the herd7
or Dat3M corpus, and it is **not** the population the coverage figures describe.

## Scope — read this before quoting any count

The paper evaluates a corpus of **12,324** litmus files, of which **2,972** (24.1%) parse
within the supported fragment. The full working tree from which that population was drawn
contains roughly **26,000** further litmus files across the two upstream projects.

**Only the 2,972 are shipped here.** The 12,324 and 26,000 figures are properties of the
upstream projects at the pinned versions below, not of this directory. The coverage
decomposition (2,972 in-fragment + 7,487 skipped + 1,865 parse errors = 12,324) is derived
by `eval/run_all.py` from `eval/corpus-weakest-manifest-v2.csv` and the committed
validation CSVs, so it still reproduces — but it **cannot be recounted from files on disk
in this package**. To recount it, rebuild the full upstream trees as described below.

## Included

| item | count / size | provenance |
|---|---:|---|
| litmus files named by `eval/corpus-weakest-manifest-v2.csv` | **2,972** (~1.5 MB) | 2,285 from Dat3M, 687 from herdtools7 |
| `Dat3M/cat/` — the `.cat` memory models, incl. `linux-kernel.cat` | 24 files (~146 KB) | Dat3M v4.4.1 |
| `Dat3M/LICENCE` | — | upstream notice, retained |
| `herdtools7/LICENSE.txt` | — | upstream notice, retained |

Paths are exactly as they appear in the first column of
`eval/corpus-weakest-manifest-v2.csv`, so that manifest resolves against this directory
unchanged.

`linux-kernel.cat` is the LKMM model used by the Dat3M/LKMM baseline
(`eval/dat3m_lkmm_hardbench.py`, `--target=lkmm`). It is part of the Dat3M source
distribution and carries Dat3M's licence.

## Not included, and why

| omitted | reason |
|---|---|
| `Dat3M/dartagnan/target/dartagnan.jar` and `target/libs/` (~60 MB) | **redistribution not established** — see below |
| `.git` histories of both upstream projects (~31 MB) | no reproduction value |
| the rest of `Dat3M/dartagnan/target/` (~124 MB) | Maven build output |
| the remaining Dat3M litmus catalogue (~93 MB) | not referenced by the manifest |
| the herdtools7 source tree (~57 MB) | not referenced by the manifest |

### The Dat3M binary is not redistributed

`dartagnan.jar` is a **thin** jar: its manifest declares
`Class-Path: libs/<31 jars>`, so it does not run without the 53 MB `libs/` directory
beside it. That directory also contains native solver binaries — `libz3.dll`,
`libbitwuzlaj.dll`, **`mathsat.dll`**, `mathsat5j.dll`, `mpir.dll` — with **no licence
files accompanying them**, and it bundles LGPL-licensed components (Princess,
SMTInterpol). MathSAT5 in particular is not distributed under an OSI-approved open-source
licence and its terms restrict redistribution.

Rather than guess at those terms, this package **does not redistribute any Dat3M binary**.
Build it from source at the pinned commit instead:

```bash
git clone https://github.com/hernanponcedeleon/Dat3M
cd Dat3M && git checkout d8de5e8ef6aa4a697ff2bbe88fa8416dfcd4ac7c
mvn clean install -DskipTests -pl dartagnan -am
```

Then point the driver at it, or place the built tree at `eval/corpus/Dat3M/`.

**Nothing else in the artifact depends on this.** The Dat3M results are shipped as data
(`eval/dat3m-lkmm-hardbench.csv`, `-grounded-rings.csv`, `-excluded.csv`) together with
the 1,350 rendered inputs (`eval/generated-dat3m/`), so every reported number is auditable
without running Dat3M. `reproduce.sh` verifies those files in `--verify` mode and skips the
live Dat3M control checks with an explanatory message when the binary is absent.

## Upstream versions

| project | version | identifier |
|---|---|---|
| Dat3M / Dartagnan | **4.4.1** | commit `d8de5e8ef6aa4a697ff2bbe88fa8416dfcd4ac7c` ("Release version 4.4.1 (#1032)", 2026-05-22) |
| herdtools7 | **7.58+1** | from `herdtools7/VERSION.txt` |

## Licences

* **Dat3M** — MIT-style permissive (`Dat3M/LICENCE`, © 2019 Hernan Ponce-de-Leon, Florian
  Furbach, Natalia Gavrilenko). Redistribution permitted with the copyright and permission
  notice retained; the notice is shipped.
* **herdtools7** — CeCILL-B (`herdtools7/LICENSE.txt`). Redistribution permitted with
  attribution; the notice is shipped. Some catalogue subdirectories carry joint copyright
  with Arm Ltd — including `aarch64-MTE/`, which contributes files to the shipped subset.
  The upstream `LICENSE.txt` enumerates those directories and is retained verbatim.

Both notices apply to the third-party files in this directory. They are **not** covered by
the artifact's own licence, and the artifact does not claim copyright over them.

## Integrity

`linux-kernel.cat` SHA-256:

```
3f024c8e794cb15d5522f6fb28c68c4873c55fa611f6ba26cca32cc8e22e5ecb
```

To re-verify the litmus subset against the manifest:

```bash
python - <<'EOF'
import csv, os
n = miss = 0
for row in csv.reader(open('eval/corpus-weakest-manifest-v2.csv')):
    if not row: continue
    n += 1
    if not os.path.isfile(os.path.join('eval/corpus', row[0])): miss += 1
print('manifest entries: %d, present: %d, missing: %d' % (n, n - miss, miss))
EOF
```

Expected: `manifest entries: 2972, present: 2972, missing: 0`.
