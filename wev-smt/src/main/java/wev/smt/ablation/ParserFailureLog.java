package wev.smt.ablation;

import wev.smt.parse.LitmusParser;
import wev.smt.parse.ParseException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Diagnostic harness for paper risk #4 (the 24.3% parser-failure coverage gap). It
 * re-runs the <em>frozen</em> {@link LitmusParser} over a list of failing corpus files
 * and records, <strong>untruncated</strong>, the exact failure each one triggers — the
 * exception kind, the full message (the corpus CSV's {@code note} column is clipped to
 * 120 chars, which loses the offending instruction on long Dat3M paths), and the first
 * non-comment line of the source.
 *
 * <p>This is logging only: it calls {@code LitmusParser.parse} exactly as
 * {@code CorpusValidation} does and reports what it throws. The parser is not modified.
 *
 * <p>Usage: {@code ParserFailureLog <corpusRoot> <fileList> <outCsv>} where
 * {@code fileList} is one corpus-relative path per line.
 */
public final class ParserFailureLog {

    private ParserFailureLog() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path list = Path.of(args[1]);
        Path out = Path.of(args[2]);

        List<String> rels = Files.readAllLines(list);
        StringBuilder sb = new StringBuilder("file\tkind\toffending\tfirstline\n");
        int n = 0, parsedOk = 0;
        for (String rel : rels) {
            rel = rel.strip();
            if (rel.isEmpty()) continue;
            n++;
            Path f = root.resolve(rel);
            String content;
            try {
                content = Files.readString(f);
            } catch (Exception io) {
                row(sb, rel, "READ_ERROR", clean(io.toString()), "");
                continue;
            }
            String firstLine = firstNonComment(content);
            try {
                LitmusParser.parse(content, rel);
                parsedOk++;
                row(sb, rel, "PARSED_OK", "", firstLine);
            } catch (ParseException pe) {
                row(sb, rel, pe.kind().toString(), clean(pe.getMessage()), firstLine);
            } catch (Throwable ex) {
                // CorpusValidation records these as PARSE_ERROR with note "unexpected:..."
                row(sb, rel, "UNEXPECTED:" + ex.getClass().getSimpleName(),
                        clean(ex.toString()), firstLine);
            }
        }
        Files.writeString(out, sb.toString());
        System.out.printf("processed %d files; parsed-ok-now=%d; wrote %s%n",
                n, parsedOk, out.toAbsolutePath());
    }

    private static String firstNonComment(String content) {
        for (String raw : content.split("\r\n|\r|\n")) {
            String t = raw.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("(*")) {
                continue;
            }
            return t;
        }
        return "";
    }

    private static void row(StringBuilder sb, String file, String kind,
                            String offending, String firstLine) {
        sb.append(file).append('\t').append(kind).append('\t')
          .append(offending).append('\t').append(firstLine).append('\n');
    }

    /** Tab/newline-safe one-liner; keep it long (untruncated is the whole point). */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }
}
