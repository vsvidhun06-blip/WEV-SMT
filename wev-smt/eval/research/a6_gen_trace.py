#!/usr/bin/env python3
"""Emit a jshell script that traces the shipped detector on chosen generated cases."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
specs = json.load(open(os.path.join(HERE, "a4_spec.json")))
IDS = [int(a) for a in sys.argv[1:]] or [72, 91, 178, 205, 312, 313, 383]

NL = "\\n"          # a literal backslash-n inside the emitted Java string literal
Q = '\\"'

out = [
    'String hdr = "C T' + NL + '{ }' + NL + '";',
    'java.util.function.BiConsumer<String,String> go = (id, body) -> {',
    '  java.util.List<String> tr = new java.util.ArrayList<>();',
    '  wev.smt.parse.LitmusParser.DEP_TRACE = d -> tr.add('
    '"classify(" + d.expr() + " , " + d.reg() + ") = " '
    '+ (d.real() ? "REAL" : "FAKE") + " [" + d.pattern() + "]");',
    '  try {',
    '    var lc = wev.smt.parse.LitmusParser.parse(hdr + body, "t.litmus");',
    '    long sem = lc.deps().allEdges().stream().filter(e -> e.edge().isSemantic()).count();',
    '    System.out.println("CASE " + id + "  semanticEdges=" + sem);',
    '    for (String s : tr) System.out.println("      " + s);',
    '  } catch (Throwable t) { System.out.println("CASE " + id + " EXC " + t); }',
    '  wev.smt.parse.LitmusParser.DEP_TRACE = null;',
    '};',
]

for i in IDS:
    sp = specs[i]
    body = " P0 ;" + NL + " r0 = READ_ONCE(*x) ;" + NL
    for n, e in sp["defs"]:
        body += " %s = %s ;%s" % (n, e, NL)
    body += " WRITE_ONCE(*y, %s) ;%sexists (0:r0=1)%s" % (sp["store"], NL, NL)
    out.append('go.accept("%d", "%s");' % (i, body))

out.append("/exit")
path = os.path.join(HERE, "a6_trace.jsh")
with open(path, "w") as fh:
    fh.write("\n".join(out) + "\n")
print("wrote", path, "for cases", IDS)
