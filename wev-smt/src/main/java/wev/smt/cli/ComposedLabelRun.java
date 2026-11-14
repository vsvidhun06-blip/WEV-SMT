// FSEN 2027 revision (2026-11-05): composed-label dependency mode ("mode C").
//
// Kept separate from the shipped instruction-local modes; no shipped class is modified.
//   A = shipped folding classifier, per instruction (no oracle installed)
//   B = exact QF_BV oracle per instruction (LitmusParser.CONSTANCY_ORACLE), chained closure
//   C = composed label: each DATA edge (load p -> store w) is semantic iff the composed store
//       expression writeValueExprs[w] (registers replaced by their definitions, every load
//       bound to a fresh symbol R<id>, initial registers bound to their litmus initial values)
//       is non-constant in R<p>, all other load symbols free. Decided by the same BV32
//       two-copy oracle, applied to the composed expression instead of the local one.
//
// Supported fragment (anything else is REJECTED with a reason, never silently decided):
//   parsed by the shipped front end; no RMW event; no control or address dependency edge;
//   every composed store expression inside the oracle grammar (BV32 + - * ^ & | ~, unary -,
//   signed relations); no page-table (VMSA) constructs; no symbolic-encoding gaps.
// Width: the oracle is BV32. Width sensitivity (32 vs 64 bit) is checked separately by the
// independent Python reference on the exported composed expressions.
//
// Usage: java wev.smt.cli.ComposedLabelRun <outDir> <listFile> <baseDir>
//   listFile: one litmus path per line, relative to baseDir.
package wev.smt.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import com.weakest.model.*;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.*;
import wev.smt.*;
import wev.smt.parse.*;

public class ComposedLabelRun {
  static final Pattern RSYM = Pattern.compile("(?<![A-Za-z0-9_])R(\\d+)(?![A-Za-z0-9_])");

  /** Mode C dependency info: DATA flags re-decided on composed labels; null if unsupported. */
  static DependencyInfo composedDeps(LitmusCase lc, LitmusParser.ConstancyOracle oracle,
                                     List<String[]> edgeRows, String file,
                                     Map<String, Boolean> localA, StringBuilder reason) {
    DependencyInfo d = DependencyInfo.empty();
    for (var ke : lc.deps().allEdges()) {
      var e = ke.edge();
      if (ke.kind() != DependencyInfo.DepKind.DATA) {
        reason.append(ke.kind() == DependencyInfo.DepKind.CTRL ? "CTRL_DEP" : "ADDR_DEP");
        return null;
      }
      String expr = lc.writeValueExprs().get(e.consumer().getId());
      if (expr == null) { reason.append("NO_VALUE_EXPR"); return null; }
      String sym = "R" + e.producer().getId();
      Boolean constant = oracle.isConstant(expr, sym);
      if (constant == null) { reason.append("OUT_OF_GRAMMAR"); return null; }
      boolean occurs = RSYM.matcher(expr).results().anyMatch(m -> m.group(0).equals(sym));
      String key = e.consumer().getId() + ">" + e.producer().getId();
      edgeRows.add(new String[]{file, Integer.toString(e.producer().getId()),
          Integer.toString(e.consumer().getId()), e.consumer().getVariable(),
          String.valueOf(localA.getOrDefault(key, null)), String.valueOf(e.isSemantic()),
          constant ? "CONST" : "VARIES", String.valueOf(occurs), expr});
      d.addDataDep(e.consumer(), e.producer(), !constant);
    }
    return d;
  }

  /** Front-end coverage check: every load symbol in a composed store expression must be a
   *  syntactic DATA producer of that store (the assumption esdep subset-of dep). */
  static int coverageViolations(LitmusCase lc) {
    int v = 0;
    for (Event ev : lc.es().getEvents()) {
      if (!(ev instanceof WriteEvent) || ev instanceof RMWEvent) continue;
      String expr = lc.writeValueExprs().get(ev.getId());
      if (expr == null) continue;
      Set<Integer> producers = new HashSet<>();
      for (Event p : lc.deps().getDataDeps(ev)) producers.add(p.getId());
      Matcher m = RSYM.matcher(expr);
      while (m.find()) if (!producers.contains(Integer.parseInt(m.group(1)))) v++;
    }
    return v;
  }

  static DependencyInfo allSemantic(LitmusCase lc) {
    DependencyInfo d = DependencyInfo.empty();
    for (var ke : lc.deps().allEdges()) {
      var e = ke.edge();
      switch (ke.kind()) {
        case DATA -> d.addDataDep(e.consumer(), e.producer(), true);
        case ADDR -> d.addAddrDep(e.consumer(), e.producer(), true);
        case CTRL -> d.addCtrlDep(e.consumer(), e.producer(), true);
      }
    }
    return d;
  }

  /** Returns {wfOnly, WEAKEST} as "R"/"U" and "A"/"F". */
  static String[] verdicts(SolverContext ctx, LitmusCase lc, DependencyInfo deps) throws Exception {
    EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), deps);
    enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
    AxiomaticConsistency ax = new AxiomaticConsistency(enc);
    BooleanFormula wf = enc.encodeWellFormedness();
    String wfv, wk;
    try (ProverEnvironment p = ctx.newProverEnvironment()) {
      p.addConstraint(wf); wfv = p.isUnsat() ? "U" : "R";
    }
    try (ProverEnvironment p = ctx.newProverEnvironment()) {
      p.addConstraint(enc.getBmgr().and(wf, ax.consistencyWEAKEST())); wk = p.isUnsat() ? "F" : "A";
    }
    return new String[]{wfv, wk};
  }

  static String csv(String s) {
    if (s == null) return "";
    return (s.contains(",") || s.contains("\"")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
  }

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]); Files.createDirectories(out);
    List<String> files = Files.readAllLines(Path.of(args[1]));
    Path base = Path.of(args[2]);
    var cfg = Configuration.defaultConfiguration();
    var log = BasicLogManager.create(cfg);
    try (var ctx = SolverContextFactory.createSolverContext(cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
         var oracle = new QfbvConstancyOracle();
         var fw = new PrintWriter(Files.newBufferedWriter(out.resolve("files.csv"), StandardCharsets.UTF_8));
         var ew = new PrintWriter(Files.newBufferedWriter(out.resolve("edges.csv"), StandardCharsets.UTF_8));
         var vw = new PrintWriter(Files.newBufferedWriter(out.resolve("events.csv"), StandardCharsets.UTF_8))) {
      vw.println("file,id,class,thread,var");
      fw.println("file,support,reason,data_edges,coverage_violations,wf,weakest_A,weakest_B,weakest_C,weakest_ALL,weakest_NODEP,edges_fake_A,edges_fake_B,edges_fake_C");
      ew.println("file,read_id,write_id,write_loc,local_A_semantic,local_B_semantic,composed,symbol_occurs,composed_expr");
      for (String rel : files) {
        rel = rel.strip(); if (rel.isEmpty()) continue;
        Path f = base.resolve(rel);
        String src;
        try { src = Files.readString(f); } catch (IOException ex) { fw.println(csv(rel) + ",REJECTED,READ_ERROR,,,,,,,,,,,"); continue; }
        String name = f.getFileName().toString();
        LitmusCase la, lb;
        try {
          la = LitmusParser.parse(src, name);
          LitmusParser.CONSTANCY_ORACLE = oracle;
          try { lb = LitmusParser.parse(src, name); } finally { LitmusParser.CONSTANCY_ORACLE = null; }
        } catch (Exception ex) {
          fw.println(csv(rel) + ",REJECTED,PARSE_ERROR,,,,,,,,,,,"); continue;
        }
        // A and B are separate parses; event ids come from a global counter, so align the two
        // edge lists by position and check that they describe the same edges.
        Map<String, Boolean> localA = new HashMap<>();
        var ea = la.deps().allEdges(); var eb = lb.deps().allEdges();
        if (ea.size() != eb.size()) { fw.println(csv(rel) + ",REJECTED,A_B_EDGE_MISMATCH,,,,,,,,,,,"); continue; }
        if (lb.es().getEvents().isEmpty()) { fw.println(csv(rel) + ",REJECTED,NO_EVENTS,,,,,,,,,,,"); continue; }
        int off = lb.es().getEvents().get(0).getId() - la.es().getEvents().get(0).getId();
        boolean aligned = true;
        for (int i = 0; i < ea.size(); i++) {
          var x = ea.get(i); var y = eb.get(i);
          if (x.kind() != y.kind() || x.edge().consumer().getId() + off != y.edge().consumer().getId()
              || x.edge().producer().getId() + off != y.edge().producer().getId()) { aligned = false; break; }
          localA.put(y.edge().consumer().getId() + ">" + y.edge().producer().getId(), x.edge().isSemantic());
        }
        if (!aligned) { fw.println(csv(rel) + ",REJECTED,A_B_EDGE_MISMATCH,,,,,,,,,,,"); continue; }
        int dataEdges = 0, fakeA = 0, fakeB = 0;
        for (var ke : la.deps().allEdges()) if (!ke.edge().isSemantic()) fakeA++;
        for (var ke : lb.deps().allEdges()) { if (ke.kind() == DependencyInfo.DepKind.DATA) dataEdges++; if (!ke.edge().isSemantic()) fakeB++; }
        StringBuilder reason = new StringBuilder();
        boolean gap = !CorpusValidation.symbolicEncodingGaps(lb, ctx).isEmpty();
        if (rel.contains("aarch64-VMSA") || src.contains("pte_")) reason.append("VMSA_PAGE_TABLE");
        else if (lb.es().getEvents().stream().anyMatch(e -> e instanceof RMWEvent)) reason.append("RMW");
        else if (gap) reason.append("ENCODING_GAP");
        List<String[]> rows = new ArrayList<>();
        DependencyInfo dc = null;
        if (reason.length() == 0) dc = composedDeps(lb, oracle, rows, rel, localA, reason);
        int cov = coverageViolations(lb);
        // Baseline verdicts for every encodable file (independent of mode-C support).
        String wf = "", wA = "", wB = "", wAll = "", wNo = "", wC = "", fC = "";
        if (!gap) {
          String[] vA = verdicts(ctx, la, la.deps());
          String[] vB = verdicts(ctx, lb, lb.deps());
          wf = vB[0]; wA = vA[1]; wB = vB[1];
          wAll = verdicts(ctx, lb, allSemantic(lb))[1];
          wNo = verdicts(ctx, lb, DependencyInfo.empty())[1];
        }
        if (dc != null) {
          int fakeC = 0; for (var ke : dc.allEdges()) if (!ke.edge().isSemantic()) fakeC++;
          wC = verdicts(ctx, lb, dc)[1]; fC = Integer.toString(fakeC);
        }
        fw.println(String.join(",", csv(rel), dc != null ? "SUPPORTED" : "REJECTED", reason.toString(),
            Integer.toString(dataEdges), Integer.toString(cov), wf, wA, wB, wC, wAll, wNo,
            Integer.toString(fakeA), Integer.toString(fakeB), fC));
        for (Event ev : lb.es().getEvents())
          vw.println(String.join(",", csv(rel), Integer.toString(ev.getId()), ev.getClass().getSimpleName(),
              Integer.toString(ev.getThreadId()), csv(ev.getVariable())));
        vw.flush();
        if (dc != null) for (String[] r : rows) {
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < r.length; i++) { if (i > 0) sb.append(','); sb.append(csv(r[i])); }
          ew.println(sb);
        }
        fw.flush(); ew.flush();
      }
    }
  }
}
