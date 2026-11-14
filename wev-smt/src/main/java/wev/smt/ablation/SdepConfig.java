package wev.smt.ablation;

import com.weakest.model.Event;
import wev.smt.DependencyInfo;

import java.util.Map;
import java.util.Set;

/**
 * Runtime selector for the §6 sdep ablation (paper risk #3: "is the regex
 * dependency heuristic empirically necessary?"). Each value names one of the
 * three semantic-dependency configurations of the <em>otherwise unchanged</em>
 * WEAKEST checker, expressed purely as a transform of the {@link DependencyInfo}
 * sidecar that {@code AxiomaticConsistency.jfCoherence} reads via
 * {@link DependencyInfo#semanticEdges()}:
 *
 * <ul>
 *   <li>{@link #NONE} — {@code sdep = ∅}: every syntactic dependency is treated as
 *       fake ({@code isSemantic=false}), so {@code semanticEdges()} is empty and the
 *       no-thin-air axiom degenerates to {@code acyclic(jf)} alone. The syntactic
 *       edges (and their inert decision vars) are retained so the encoding cost is
 *       unchanged — only their semantic status is stripped.</li>
 *   <li>{@link #ALL_DEPS_SEMANTIC} — {@code sdep = dep}: every syntactic dependency
 *       is retained as semantic ({@code isSemantic=true}), with no fake/real
 *       stratification at all.</li>
 *   <li>{@link #CURRENT} — the existing 10-pattern regex detector, returned
 *       unchanged ({@code sdep_true ⊆ sdep_impl ⊆ dep}). This is bit-for-bit the
 *       commit-41db4b2 behaviour; {@link #transform} is the identity.</li>
 * </ul>
 *
 * <p>This class lives entirely in the new {@code wev.smt.ablation} package and is
 * the <em>only</em> mechanism the ablation adds: it touches no commit-41db4b2 file.
 * The semantic core ({@code AxiomaticConsistency}, {@code DependencyInfo},
 * {@code EventStructureEncoder}) is frozen — the three configurations are obtained
 * solely by feeding the encoder a re-flagged sidecar.
 *
 * <p>Selectable via the {@code WEV_SDEP_MODE} environment variable or a CLI token
 * ({@code none} / {@code all-deps-semantic} / {@code current}); see {@link #parse}.
 */
public enum SdepConfig {

    NONE("none"),
    ALL_DEPS_SEMANTIC("all-deps-semantic"),
    CURRENT("current");

    private final String token;

    SdepConfig(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    /**
     * Resolve a CLI/env token to a configuration. Accepts the canonical tokens and a
     * couple of obvious aliases; throws on anything else so a typo cannot silently
     * select the wrong (or default) configuration.
     */
    public static SdepConfig parse(String s) {
        String t = s.trim().toLowerCase();
        return switch (t) {
            case "none", "empty", "no-deps" -> NONE;
            case "all-deps-semantic", "all", "all-deps", "alldeps" -> ALL_DEPS_SEMANTIC;
            case "current", "regex", "default", "41db4b2" -> CURRENT;
            default -> throw new IllegalArgumentException(
                    "unknown sdep mode '" + s + "' (expected none|all-deps-semantic|current)");
        };
    }

    /** The configuration named by {@code WEV_SDEP_MODE}, or {@link #CURRENT} if unset. */
    public static SdepConfig fromEnv() {
        String v = System.getenv("WEV_SDEP_MODE");
        return (v == null || v.isBlank()) ? CURRENT : parse(v);
    }

    /**
     * Re-flag {@code orig} for this configuration, returning a sidecar the frozen
     * encoder can consume unchanged. {@link #CURRENT} returns {@code orig} itself
     * (identity — the unmodified checker); the other two rebuild the data/addr/ctrl
     * relations with a uniform {@code isSemantic} flag.
     */
    public DependencyInfo transform(DependencyInfo orig) {
        if (this == CURRENT) {
            return orig;
        }
        boolean semantic = (this == ALL_DEPS_SEMANTIC);
        DependencyInfo out = new DependencyInfo();
        copy(orig.dataDepMap(), semantic, out::addDataDep);
        copy(orig.addrDepMap(), semantic, out::addAddrDep);
        copy(orig.ctrlDepMap(), semantic, out::addCtrlDep);
        return out;
    }

    @FunctionalInterface
    private interface EdgeAdder {
        void add(Event consumer, Event producer, boolean isSemantic);
    }

    private static void copy(Map<Event, Set<Event>> rel, boolean semantic, EdgeAdder adder) {
        for (Map.Entry<Event, Set<Event>> e : rel.entrySet()) {
            for (Event producer : e.getValue()) {
                adder.add(e.getKey(), producer, semantic);
            }
        }
    }
}
