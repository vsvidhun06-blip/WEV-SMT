String hdr = "C T\n{ }\n";
java.util.function.BiConsumer<String,String> go = (id, body) -> {
  java.util.List<String> tr = new java.util.ArrayList<>();
  wev.smt.parse.LitmusParser.DEP_TRACE = d -> tr.add("classify(" + d.expr() + " , " + d.reg() + ") = " + (d.real() ? "REAL" : "FAKE") + " [" + d.pattern() + "]");
  try {
    var lc = wev.smt.parse.LitmusParser.parse(hdr + body, "t.litmus");
    long sem = lc.deps().allEdges().stream().filter(e -> e.edge().isSemantic()).count();
    System.out.println("CASE " + id + "  semanticEdges=" + sem);
    for (String s : tr) System.out.println("      " + s);
  } catch (Throwable t) { System.out.println("CASE " + id + " EXC " + t); }
  wev.smt.parse.LitmusParser.DEP_TRACE = null;
};
go.accept("72", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = r0 ^ ~1 - r0 ;\n WRITE_ONCE(*y, t0 ^ t0 & 7 & 7) ;\nexists (0:r0=1)\n");
go.accept("91", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = 3 & r0 ;\n WRITE_ONCE(*y, r0 & r0 - r0 + 3) ;\nexists (0:r0=1)\n");
go.accept("178", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = ~r0 - r0 ;\n t1 = 2 - t0 - ~0 ;\n WRITE_ONCE(*y, t0) ;\nexists (0:r0=1)\n");
go.accept("205", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = ~7 - r0 ^ r0 ;\n WRITE_ONCE(*y, r0 - t0 - r0) ;\nexists (0:r0=1)\n");
go.accept("312", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = 0 ;\n WRITE_ONCE(*y, ~r0 - r0) ;\nexists (0:r0=1)\n");
go.accept("313", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = ~r0 ^ r0 + r0 ^ r0 ;\n WRITE_ONCE(*y, 0 ^ 7 & t0) ;\nexists (0:r0=1)\n");
go.accept("383", " P0 ;\n r0 = READ_ONCE(*x) ;\n t0 = ~r0 & r0 ^ r0 ;\n WRITE_ONCE(*y, t0 ^ 7 & 1) ;\nexists (0:r0=1)\n");
/exit
