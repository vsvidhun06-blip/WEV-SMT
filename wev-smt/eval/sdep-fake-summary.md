# §6 sdep fake-edge discriminating sweep — summary

Corpus: `eval/corpus-weakest-manifest.csv` (2 998 supported-syntax tests). A test is included iff the shipped detector classifies ≥1 dependency edge as fake (`isSemantic=false`). Verdicts are WEAKEST under three sdep configurations; **discriminating** = `none != current OR all-deps-semantic != current`.

## Totals

| metric | value |
|---|---|
| tests with fake dependencies | 231 |
| total fake dependency edges | 313 |
| discriminating tests | 2 |
| verdict-changing tests | 2 |

## Breakdown by dependency type (fake edges)

| kind | fake edges | tests carrying |
|---|---|---|
| DATA | 257 | 185 |
| ADDR | 56 | 49 |
| CTRL | 0 | 0 |

## Breakdown by fake-pattern kind (tests matching)

| pattern | tests |
|---|---|
| r^r | 231 |

## Breakdown by architecture

| arch | tests |
|---|---|
| ARM | 71 |
| PPC | 160 |

## Discriminating tests (verdicts + why the verdict changes)

| test | arch | none | all-deps-semantic | current | why |
|---|---|---|---|---|---|
| `herdtools7/catalogue/herding-cats/ppc/tests/illustrative/LB+datas.litmus` | PPC | ALLOWED | FORBIDDEN | ALLOWED | treating the fake r^r DATA edge (on a dep∪rf (load-buffering) cycle) as semantic under all-deps-semantic adds a jf∪sdep ordering that closes a thin-air cycle, flipping ALLOWED→FORBIDDEN; current keeps it fake so the outcome stays ALLOWED. |
| `Dat3M/litmus/PPC/DETOUR0236.litmus` | PPC | ALLOWED | FORBIDDEN | ALLOWED | treating the fake r^r DATA edge (on a dep∪rf (load-buffering) cycle) as semantic under all-deps-semantic adds a jf∪sdep ordering that closes a thin-air cycle, flipping ALLOWED→FORBIDDEN; current keeps it fake so the outcome stays ALLOWED. |
