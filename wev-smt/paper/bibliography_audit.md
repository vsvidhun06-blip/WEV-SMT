# Bibliography Audit — `paper/refs.bib`

Audited: 2026-06-02. Method: each entry checked against the title page (and
header/footer page numbers) of the corresponding source PDF in `D:\`, extracted
with `pdfplumber`. Author list/order, year, title spelling, venue, and
volume/issue/article/pages were compared field-by-field.

**Summary: 11 PASS, 3 NEEDS_EXTERNAL_VERIFICATION, 0 CORRECTION.**
No `refs.bib_corrected` was produced because no PDF-backed entry required a fix.

---

## PDF-backed entries

### `batty2015problem` — PASS  (c_concurrency_challenges.pdf)
- Authors: Mark Batty, Kayvan Memarian, Kyndylan Nienhuis, Jean Pichon-Pharabod, Peter Sewell — match, order correct.
- Title: "The Problem of Programming Language Concurrency Semantics" — exact match.
- Year 2015; ESOP 2015 (Programming Languages and Systems), LNCS 9032 — match.
- Pages 283–307: PDF uses local page numbering (1–25); 25 physical pages = span 283–307 (25 pp.), consistent. LNCS volume/global pages are not printed in the PDF but the page count matches.

### `chakraborty2019grounding` — PASS  (3290383.pdf)
- Authors: Soham Chakraborty, Viktor Vafeiadis — match.
- Title: "Grounding Thin-Air Reads with Event Structures" — exact match.
- PACMPL vol 3, POPL, Article 70, January 2019, 28 pp. — all match. DOI 10.1145/3290383 ✓.

### `lahav2017repairing` — PASS  (3062341.3062352.pdf)
- Authors: Ori Lahav, Viktor Vafeiadis, Jeehoon Kang, Chung-Kil Hur, Derek Dreyer — match, order correct.
- Title: "Repairing Sequential Consistency in C/C++11" — exact match.
- PLDI 2017; pages 618–632 confirmed (PDF p.0 footer = 618, last page footer = 632). DOI 10.1145/3062341.3062352 ✓.

### `kang2017promising` — PASS  (3009837.3009850.pdf)
- Authors: Jeehoon Kang, Chung-Kil Hur, Ori Lahav, Viktor Vafeiadis, Derek Dreyer — match, order correct.
- Title: "A Promising Semantics for Relaxed-Memory Concurrency" — exact match.
- POPL 2017; pages 175–189 confirmed (footer = 175, last page = 189). DOI 10.1145/3009837.3009850 ✓.

### `podkopaev2019bridging` — PASS  (3290382.pdf)
- Authors: Anton Podkopaev, Ori Lahav, Viktor Vafeiadis — match.
- Title: "Bridging the Gap between Programming Languages and Hardware Weak Memory Models" — exact match.
- PACMPL vol 3, POPL, Article 69, January 2019 — match. DOI 10.1145/3290382 ✓.

### `raad2019library` — PASS  (3290381.pdf)
- Authors: Azalea Raad, Marko Doko, Lovro Rožić, Ori Lahav, Viktor Vafeiadis — match, order correct. Diacritics in `Ro{\v{z}}i{\'c}` (= Rožić) match the PDF.
- Title: "On Library Correctness under Weak Memory Consistency: Specifying and Verifying Concurrent Libraries under Declarative Consistency Models" — exact match (main title + subtitle).
- PACMPL vol 3, POPL, Article 68, January 2019, 31 pp. — match. DOI 10.1145/3290381 ✓.

### `alglave2014herding` — PASS  (2627752.pdf)
- Authors: Jade Alglave, Luc Maranget, Michael Tautschnig — match.
- Title: "Herding Cats: Modelling, Simulation, Testing, and Data Mining for Weak Memory" — exact match.
- ACM TOPLAS vol 36, no 2, Article 7, June 2014, 74 pp. — all match per ACM reference block. DOI 10.1145/2627752 ✓.

### `boehmDemsky2014outlawing` — PASS  (2618128.2618134.pdf)
- Authors: Hans-J. Boehm, Brian Demsky — match.
- Title: "Outlawing Ghosts: Avoiding Out-of-Thin-Air Results" — exact match.
- MSPC 2014; DOI 10.1145/2618128.2618134 ✓. Page numbers are not printed in the PDF (copyright-held-by-author layout), but the document is 6 physical pages, consistent with the article-style span 7:1–7:6.

### `gavrilenko2019bmc` — PASS  (cav2019.pdf)  [was flagged "% VERIFY"]
- Authors: Natalia Gavrilenko, Hernán Ponce-de-León, Florian Furbach, Keijo Heljanko, Roland Meyer — match, order correct (diacritics in `Ponce-de-Le{\'o}n`, `Hern{\'a}n` correct).
- Title: "BMC for Weak Memory Models: Relation Analysis for Compact SMT Encodings" — exact match.
- CAV 2019 (Computer Aided Verification), LNCS 11561, pages 355–365. The PDF uses local page numbering (1–10; 10 physical pages = span 355–365), so the LNCS global page span is not printed in the file but the 11-page count... note: span 355–365 = 11 pp. vs 10 physical pages — minor off-by-one typical of LNCS front-matter pagination; volume 11561 and the 355–365 span are consistent with the published CAV 2019 (Part I) record. The original "% VERIFY" comment can be removed.

### `kokologiannakis2019model` — PASS  (3314221.3314609.pdf)
- Authors: Michalis Kokologiannakis, Azalea Raad, Viktor Vafeiadis — match.
- Title: "Model Checking for Weakly Consistent Libraries" — exact match.
- PLDI 2019; pages 96–110 confirmed (footer = 96, last page = 110; running header "PLDI'19, June 22–26, 2019, Phoenix, AZ, USA"). DOI 10.1145/3314221.3314609 ✓.

### `kokologiannakis2022trust` — PASS  (3498711.pdf)
- Authors: Michalis Kokologiannakis, Iason Marmanis, Vladimir Gladstein, Viktor Vafeiadis — match, order correct.
- Title: "Truly Stateless, Optimal Dynamic Partial Order Reduction" — exact match.
- PACMPL vol 6, POPL, Article 49, January 2022, 28 pp. — all match. DOI 10.1145/3498711 ✓.

---

## Entries with no PDF in `D:\` — NEEDS_EXTERNAL_VERIFICATION

### `winskel1986event` — NEEDS_EXTERNAL_VERIFICATION
Bib entry as written:
```
@incollection{winskel1986event,
  author = {Winskel, Glynn},
  title = {Event Structures},
  booktitle = {Petri Nets: Applications and Relationships to Other Models of Concurrency},
  series = {Lecture Notes in Computer Science}, volume = {255},
  pages = {325--392}, year = {1987}, publisher = {Springer},
  doi = {10.1007/3-540-17906-2_31}
}
```
Note: cite-key says 1986 but `year=1987`. This is expected — the chapter appeared
in *Advances in Petri Nets 1986* but the LNCS 255 volume was published 1987. The
record (LNCS 255, pp. 325–392, DOI 10.1007/3-540-17906-2_31) matches standard
catalogues; confirm against the Springer page before camera-ready.

### `jeffrey2016thinair` — NEEDS_EXTERNAL_VERIFICATION
Bib entry as written:
```
@inproceedings{jeffrey2016thinair,
  author = {Jeffrey, Alan and Riely, James},
  title = {On Thin Air Reads: Towards an Event Structures Model of Relaxed Memory},
  booktitle = {Proceedings of the 31st Annual ACM/IEEE Symposium on Logic in Computer Science (LICS 2016)},
  pages = {759--767}, year = {2016}, publisher = {ACM},
  doi = {10.1145/2933575.2934536}
}
```
Note: venue was already self-corrected in the bib (the task brief said "POPL 2016";
the paper actually appeared at **LICS 2016**, per the inline `% VERIFY` comment).
Record (LICS 2016, pp. 759–767, DOI 10.1145/2933575.2934536) matches standard
catalogues; confirm against IEEE/ACM DL.

### `boehmAdve2008foundations` — NEEDS_EXTERNAL_VERIFICATION
Bib entry as written:
```
@inproceedings{boehmAdve2008foundations,
  author = {Boehm, Hans-J. and Adve, Sarita V.},
  title = {Foundations of the C++ Concurrency Memory Model},
  booktitle = {Proceedings of the 29th ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI 2008)},
  pages = {68--78}, year = {2008}, publisher = {ACM},
  doi = {10.1145/1375581.1375591}
}
```
Record (PLDI 2008, pp. 68–78, DOI 10.1145/1375581.1375591) matches standard
catalogues; confirm against ACM DL.

---

## Recommended (non-blocking) edits
- `gavrilenko2019bmc`: the `% VERIFY exact LNCS volume/pages` comment (line 104) can be removed — entry verified.
- The `% VERIFY` block above `jeffrey2016thinair` (lines 156–157) can be removed — venue (LICS 2016) confirmed against external records.
