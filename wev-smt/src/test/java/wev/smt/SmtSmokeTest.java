package wev.smt;

import org.junit.jupiter.api.Test;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.FormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtSmokeTest {

    @Test
    void z3IntegerRangeIsSat() throws Exception {
        Configuration config = Configuration.defaultConfiguration();
        LogManager logger = BasicLogManager.create(config);
        ShutdownNotifier shutdown = ShutdownNotifier.createDummy();

        try (SolverContext ctx = SolverContextFactory.createSolverContext(
                config, logger, shutdown, Solvers.Z3)) {

            FormulaManager fmgr = ctx.getFormulaManager();
            IntegerFormulaManager imgr = fmgr.getIntegerFormulaManager();
            BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();

            IntegerFormula x = imgr.makeVariable("x");
            BooleanFormula inRange = bmgr.and(
                    imgr.greaterThan(x, imgr.makeNumber(0)),
                    imgr.lessThan(x, imgr.makeNumber(5)));

            try (ProverEnvironment prover =
                         ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
                prover.addConstraint(inRange);
                assertFalse(prover.isUnsat(), "0 < x < 5 must be satisfiable");

                try (Model model = prover.getModel()) {
                    BigInteger xv = model.evaluate(x);
                    assertNotNull(xv, "model must assign x");
                    assertTrue(xv.compareTo(BigInteger.ZERO) > 0, "x > 0");
                    assertTrue(xv.compareTo(BigInteger.valueOf(5)) < 0, "x < 5");
                }
            }
        }
    }
}
