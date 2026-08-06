# The minimal detector fix — exact change

Reproducible reference for the fix validated in `eval/fix-delta.md`.

Base commit `dc6fb602e67a800d996033806936ee155b7f523b`. No git tag was created: the
working tree already carried unrelated uncommitted changes when this session began, and
tagging would have folded that work into a supposedly minimal artifact. The two touched
files are archived verbatim alongside this note (`*.postfix`), with sha256 prefixes in
`MANIFEST.json`. Reverting the two hunks below restores pre-fix behaviour exactly.

## Files changed

| file | methods | lines |
|---|---|---|
| `src/main/java/wev/smt/parse/LitmusParser.java` | `Job.classify` (edited), `Job.precOf`, `Job.foldSubterms`, `Job.isSubterm` (new) | +88 / −6 |
| `src/test/java/wev/smt/parse/LitmusParserTest.java` | 3 new `@Test` methods + 1 helper | +68 / −0 |

Total production change: **88 inserted, 6 deleted, 1 file**. No refactoring, no unrelated
edits, no change to the encoder, the oracle, the Isabelle theories, or the paper source.

## Hunk 1 — `classify`: route folds through the sub-term guard

```diff
   String[] toZeroName = { "r^r", "r-r", "r&~r", "~r&r", "r*0", "0*r", "r&0", "0&r" };
+  // Precedence of each idiom's TOP-LEVEL operator, used by foldSubterms to check
+  // that a textual match is really a sub-term. Order matches toZero/toOnes.
+  int[] toZeroPrec = { PREC_XOR, PREC_ADD, PREC_AND, PREC_AND,
+                       PREC_MUL, PREC_MUL, PREC_AND, PREC_AND };
   String[] toOnes = { R + "\\|~" + R, "~" + R + "\\|" + R };
   String[] toOnesName = { "r|~r", "~r|r" };
+  int[] toOnesPrec = { PREC_OR, PREC_OR };
   ...
   for (int i = 0; i < toZero.length; i++) {
-      String n = e.replaceAll(bounded(toZero[i]), "0");
+      String n = foldSubterms(e, bounded(toZero[i]), "0", toZeroPrec[i]);
       if (!n.equals(e)) { e = n; changed = true; fired.add(toZeroName[i]); }
   }
   for (int i = 0; i < toOnes.length; i++) {
-      String n = e.replaceAll(bounded(toOnes[i]), "1");
+      String n = foldSubterms(e, bounded(toOnes[i]), "1", toOnesPrec[i]);
       if (!n.equals(e)) { e = n; changed = true; fired.add(toOnesName[i]); }
   }
```

## Hunk 2 — new helpers, next to `bounded()`

```java
private static final int PREC_OR = 1, PREC_XOR = 2, PREC_AND = 3,
                         PREC_ADD = 4, PREC_MUL = 5, PREC_UNARY = 6;

private static int precOf(char c) {
    return switch (c) {
        case '|' -> PREC_OR;   case '^' -> PREC_XOR;  case '&' -> PREC_AND;
        case '+', '-' -> PREC_ADD;  case '*' -> PREC_MUL;  case '~' -> PREC_UNARY;
        default -> -1;
    };
}

private static String foldSubterms(String e, String pattern, String repl, int p) {
    Matcher m = Pattern.compile(pattern).matcher(e);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (m.find()) {
        if (!isSubterm(e, m.start(), m.end(), p)) continue;
        out.append(e, last, m.start()).append(repl);
        last = m.end();
    }
    if (last == 0) return e;
    return out.append(e.substring(last)).toString();
}

private static boolean isSubterm(String e, int s, int t, int p) {
    if (s > 0) {
        char l = e.charAt(s - 1);
        if (l != '(') {
            int lp = precOf(l);
            if (lp < 0 || lp >= p) return false;   // binds at least as tightly
        }
    }
    if (t < e.length()) {
        char r = e.charAt(t);
        if (r != ')') {
            int rp = precOf(r);
            if (rp < 0 || rp > p) return false;    // binds more tightly
        }
    }
    return true;
}
```

## Rationale

The folder rewrote the expression **string**, so a cancellation pattern could match text
that is not a sub-term. `~r0 - r0` contains `r0-r0` but parses as `(~r0) - r0 = -2·r0 − 1`,
which is not constant; folding it produced `~0`, the register vanished, and a genuinely
value-carrying dependency was reported fake — an **unsound** classification, since it
removes an edge from `sdep`.

The guard admits a fold only when the match is a genuine sub-term. Given the idiom's
top-level precedence `p`:

- **left** neighbour must be start-of-string, `(`, or an operator binding *strictly
  looser* than `p` — equality is rejected because the grammar is left-associative, so in
  `a^r0^r0` the parse is `(a^r0)^r0` and the trailing `r0^r0` is not a sub-term;
- **right** neighbour must be end-of-string, `)`, or an operator binding *no tighter*
  than `p` — equality is accepted here for the same reason: in `r0^r0^1` the leading
  `r0^r0` *is* the left child.

The check is deliberately conservative: an unknown neighbour blocks the fold. Skipping a
legitimate fold costs precision (a fake edge reported real, which is sound); performing an
illegitimate one costs soundness. Only the latter is unacceptable.

## Reproducing

```bash
mvn -o test-compile
mvn -o test                      # 57/57
python eval/run_all.py           # regenerates results.json / numbers.tex / tables.tex
python eval/research/delta.py    # OLD/NEW/DELTA against the two snapshots
```

Behavioural probes:

```bash
python eval/research/a7_soundness.py      # exhaustive enumeration: 0/8448 unsound
python eval/research/a9_postfix_sweep.py  # adversarial sweep: 0/5076 unsound
python eval/research/a10_interaction.py   # interaction with the stripImm defect
```
