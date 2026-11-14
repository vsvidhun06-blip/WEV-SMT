// Memory-hop repair pass (2026-11-08): witness extractor for the C / NODEP contrast.
// Read-only use of the pinned corrected-v1 classes and of ComposedLabelRun.composedDeps (unchanged).
// For each litmus file it prints: events; mode C's per-edge decisions; then, for every assignment of
// one rf source per read, whether wf and WEAKEST is satisfiable with sdep = {} (NODEP) and with
// sdep = mode C's semantic edges (C). For each satisfiable case it prints the Z3 model's rf/co choices
// and 32-bit values. For each rf assignment that NODEP admits and C forbids it prints a cycle in
// sdep U rf found by a DFS over the tool's own edge sets (the DFS is this driver's code, not the tool's).
// Usage: java wev.smt.cli.MemhopWitness <litmus>...
package wev.smt.cli;

import java.nio.file.*;
import java.util.*;
import com.weakest.model.*;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.*;
import wev.smt.*;
import wev.smt.parse.*;

public class MemhopWitness {
  static String ev(Event e) {
    String k = e instanceof ReadEvent ? "R" : (e instanceof WriteEvent ? "W" : e.getType().toString());
    return "e" + e.getId() + "(" + k + " T" + e.getThreadId() + " " + e.getVariable() + ")";
  }

  public static void main(String[] args) throws Exception {
    var cfg = Configuration.defaultConfiguration();
    var log = BasicLogManager.create(cfg);
    try (var ctx = SolverContextFactory.createSolverContext(cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
         var oracle = new QfbvConstancyOracle()) {
      var bvm = ctx.getFormulaManager().getBitvectorFormulaManager();
      for (String a : args) {
        Path f = Path.of(a);
        String src = Files.readString(f);
        String name = f.getFileName().toString();
        LitmusCase lc;
        LitmusParser.CONSTANCY_ORACLE = oracle;
        try { lc = LitmusParser.parse(src, name); } finally { LitmusParser.CONSTANCY_ORACLE = null; }
        System.out.println("=== " + name);
        for (Event e : lc.es().getEvents()) {
          String mo = (e instanceof ReadEvent r) ? " order=" + r.getMemoryOrder() : "";
          String val = lc.writeValueExprs().containsKey(e.getId()) ? " value=" + lc.writeValueExprs().get(e.getId()) : "";
          System.out.println("  event " + ev(e) + mo + val);
        }
        System.out.println("  exists reads: " + lc.existsReadValues() + "  final mem: " + lc.existsMemFinal());
        StringBuilder reason = new StringBuilder();
        List<String[]> rows = new ArrayList<>();
        DependencyInfo dc = ComposedLabelRun.composedDeps(lc, oracle, rows, name, new HashMap<>(), reason);
        if (dc == null) { System.out.println("  mode C REJECTED: " + reason); continue; }
        for (String[] r : rows) System.out.println("  data edge R" + r[1] + " -> W" + r[2] + " (" + r[3] + "): composed " + r[6] + "  expr " + r[8]);
        System.out.println("  sdep(C) semantic edges: " + dc.semanticEdges().size());
        for (var e : dc.semanticEdges()) System.out.println("    sdep " + ev(e.producer()) + " -> " + ev(e.consumer()));

        // group rf variables by reader
        EventStructureEncoder probe = new EventStructureEncoder(ctx, lc.es(), DependencyInfo.empty());
        probe.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
        probe.encodeWellFormedness();
        Map<Event, List<Event>> srcs = new LinkedHashMap<>();
        for (EventPair p : probe.getRfVars().keySet()) srcs.computeIfAbsent(p.to(), k -> new ArrayList<>()).add(p.from());
        List<Event> readers = new ArrayList<>(srcs.keySet());
        int[] idx = new int[readers.size()];
        int combos = 0, wfOk = 0, noOk = 0, cOk = 0;
        while (true) {
          Map<Event, Event> choice = new LinkedHashMap<>();
          for (int i = 0; i < readers.size(); i++) choice.put(readers.get(i), srcs.get(readers.get(i)).get(idx[i]));
          combos++;
          String[] res = new String[3]; String model = null;
          for (int mode = 0; mode < 3; mode++) {   // 0 = wf only, 1 = NODEP, 2 = C
            DependencyInfo deps = mode == 2 ? dc : DependencyInfo.empty();
            EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), deps);
            enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
            BooleanFormula wf = enc.encodeWellFormedness();
            var bm = enc.getBmgr();
            List<BooleanFormula> pin = new ArrayList<>();
            for (var en : enc.getRfVars().entrySet())
              pin.add(choice.get(en.getKey().to()) == en.getKey().from() ? en.getValue() : bm.not(en.getValue()));
            BooleanFormula phi = mode == 0 ? wf : bm.and(wf, new AxiomaticConsistency(enc).consistencyWEAKEST());
            try (ProverEnvironment p = ctx.newProverEnvironment(SolverContext.ProverOptions.GENERATE_MODELS)) {
              p.addConstraint(phi); p.addConstraint(bm.and(pin));
              boolean sat = !p.isUnsat();
              res[mode] = sat ? "SAT" : "UNSAT";
              if (sat && mode == 1) {
                StringBuilder sb = new StringBuilder();
                try (Model m = p.getModel()) {
                  for (Event e : lc.es().getEvents()) {
                    String n = (e instanceof ReadEvent) ? "vr_e" + e.getId() : (e instanceof WriteEvent || e.getType() == EventType.INIT) ? "vw_e" + e.getId() : null;
                    if (n == null) continue;
                    var v = m.evaluate(bvm.makeVariable(32, n));
                    sb.append("      ").append(ev(e)).append(" value=").append(v).append('\n');
                  }
                  for (var en : enc.getCoVars().entrySet())
                    if (Boolean.TRUE.equals(m.evaluate(en.getValue())))
                      sb.append("      co ").append(ev(en.getKey().from())).append(" -> ").append(ev(en.getKey().to())).append('\n');
                }
                model = sb.toString();
              }
            }
          }
          if (res[0].equals("SAT")) wfOk++; if (res[1].equals("SAT")) noOk++; if (res[2].equals("SAT")) cOk++;
          if (res[0].equals("SAT")) {
            StringBuilder rf = new StringBuilder();
            for (var en : choice.entrySet()) rf.append(" rf ").append(ev(en.getValue())).append("->").append(ev(en.getKey())).append(';');
            System.out.println("  candidate" + rf + "  wf=" + res[0] + " NODEP=" + res[1] + " C=" + res[2]);
            if (model != null) System.out.print("    NODEP model (Z3):\n" + model);
            if (res[1].equals("SAT") && res[2].equals("UNSAT")) {
              // DFS for a cycle in sdep(C) U rf(choice)
              Map<Event, List<String[]>> g = new LinkedHashMap<>();
              Map<Integer, Event> byId = new HashMap<>();
              for (Event e : lc.es().getEvents()) byId.put(e.getId(), e);
              for (var e : dc.semanticEdges()) g.computeIfAbsent(e.producer(), k -> new ArrayList<>()).add(new String[]{"sdep", Integer.toString(e.consumer().getId())});
              for (var en : choice.entrySet()) g.computeIfAbsent(en.getValue(), k -> new ArrayList<>()).add(new String[]{"rf", Integer.toString(en.getKey().getId())});
              String cyc = findCycle(g, byId);
              System.out.println("    cycle in sdep U rf (driver DFS): " + cyc);
            }
          }
          int i = 0;
          while (i < idx.length) { idx[i]++; if (idx[i] < srcs.get(readers.get(i)).size()) break; idx[i] = 0; i++; }
          if (i == idx.length) break;
        }
        System.out.println("  rf assignments: " + combos + "; wf-satisfiable: " + wfOk + "; NODEP-consistent: " + noOk + "; C-consistent: " + cOk);
      }
    }
  }

  static String findCycle(Map<Event, List<String[]>> g, Map<Integer, Event> byId) {
    for (Event start : g.keySet()) {
      Deque<Object[]> st = new ArrayDeque<>();
      st.push(new Object[]{start, ev(start)});
      Set<Integer> seen = new HashSet<>();
      while (!st.isEmpty()) {
        Object[] cur = st.pop(); Event u = (Event) cur[0]; String path = (String) cur[1];
        for (String[] ed : g.getOrDefault(u, List.of())) {
          Event v = byId.get(Integer.parseInt(ed[1]));
          String np = path + " -" + ed[0] + "-> " + ev(v);
          if (v.getId() == start.getId()) return np;
          if (seen.add(v.getId())) st.push(new Object[]{v, np});
        }
      }
    }
    return "none";
  }
}
