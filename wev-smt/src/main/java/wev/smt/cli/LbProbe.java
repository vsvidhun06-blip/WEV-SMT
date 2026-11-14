// LB probe. Mirrors F01CorpusRegressionTest.symbolicVerdicts of the
// corrected-v1 snapshot; adds (i) the well-formedness-only query (no model axiom: is the outcome
// value-reachable at all?), (ii) the dependency edges with their semantic flags, and (iii) both
// local deciders of the chain experiment: A = shipped folding classifier (no oracle installed),
// B = exact QF_BV oracle per instruction (LitmusParser.CONSTANCY_ORACLE). It does not modify any
// shipped class. Usage: java LbProbe <litmus>...
package wev.smt.cli;

import java.nio.file.*;
import java.util.*;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.*;
import wev.smt.*;

import wev.smt.parse.*;

public class LbProbe {
  static BooleanFormula cons(AxiomaticConsistency ax, MemoryModel m) {
    return switch (m) {
      case SC -> ax.consistencySC();
      case TSO -> ax.consistencyTSO();
      case PSO -> ax.consistencyPSO();
      case RA -> ax.consistencyRA();
      case WEAKEST -> ax.consistencyWEAKEST();
      case RC11 -> ax.consistencyRC11();
    };
  }

  static String run(SolverContext ctx, LitmusCase lc) throws Exception {
    List<String> gaps = CorpusValidation.symbolicEncodingGaps(lc, ctx);
    if (!gaps.isEmpty()) return "GAPS " + gaps;
    EventStructureEncoder enc = new EventStructureEncoder(ctx, lc.es(), lc.deps());
    enc.enableSymbolic(lc.writeValueExprs(), lc.existsReadValues(), lc.existsMemFinal());
    AxiomaticConsistency ax = new AxiomaticConsistency(enc);
    BooleanFormula wf = enc.encodeWellFormedness();
    StringBuilder sb = new StringBuilder();
    try (ProverEnvironment p = ctx.newProverEnvironment()) {
      p.addConstraint(wf);
      sb.append("WF-only=").append(p.isUnsat() ? "UNREACHABLE" : "reachable").append(' ');
    }
    for (MemoryModel m : MemoryModel.values()) {
      try (ProverEnvironment p = ctx.newProverEnvironment()) {
        p.addConstraint(enc.getBmgr().and(wf, cons(ax, m)));
        sb.append(m).append('=').append(p.isUnsat() ? "F" : "A").append(' ');
      }
    }
    StringBuilder ed = new StringBuilder();
    for (var k : lc.deps().allEdges())
      ed.append(k.kind()).append(k.edge().isSemantic() ? ":semantic " : ":fake ");
    return sb.toString().trim() + " | edges " + ed.toString().trim();
  }

  public static void main(String[] args) throws Exception {
    var cfg = Configuration.defaultConfiguration();
    var log = BasicLogManager.create(cfg);
    try (var ctx = SolverContextFactory.createSolverContext(cfg, log, ShutdownNotifier.createDummy(), Solvers.Z3);
         var oracle = new QfbvConstancyOracle()) {
      for (String a : args) {
        String src = Files.readString(Path.of(a));
        String name = Path.of(a).getFileName().toString();
        LitmusCase la = LitmusParser.parse(src, name);
        LitmusParser.CONSTANCY_ORACLE = oracle;
        LitmusCase lb;
        try { lb = LitmusParser.parse(src, name); } finally { LitmusParser.CONSTANCY_ORACLE = null; }
        System.out.println(name);
        System.out.println("  A (folding)      " + run(ctx, la));
        System.out.println("  B (exact local)  " + run(ctx, lb));
      }
    }
  }
}
