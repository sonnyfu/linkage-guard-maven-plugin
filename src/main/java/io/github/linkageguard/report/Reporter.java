package io.github.linkageguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Kind;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class Reporter {
    private Reporter() {}

    public static void print(
            String title, Collection<Finding> findings, Map<String, String> hints, int max, PrintStream out) {
        out.println("\n=== " + title + " : " + findings.size() + " finding(s) ===");
        var sorted = findings.stream()
                .sorted(Comparator.comparing(Finding::severity)
                        .thenComparing(Finding::kind)
                        .thenComparing(Finding::subject))
                .toList();
        int n = 0;
        for (Finding f : sorted) {
            if (n++ >= max) {
                out.println("... " + (sorted.size() - max) + " more, use --json for the full list");
                break;
            }
            out.printf(
                    "%n[%s] %s%n  at     : %s%s%n  target : %s%n  reason : %s%n",
                    f.severity(),
                    f.kind().symptom,
                    f.subject(),
                    f.location() == null ? "" : " (" + f.location() + ")",
                    f.target(),
                    f.message());
            String h = resolveHint(hints, f.target());
            if (h != null) {
                out.println("  japicmp: " + h);
            }
            out.println("  origin : " + f.origin());
        }
        Map<Kind, Long> byKind = findings.stream()
                .collect(Collectors.groupingBy(Finding::kind, TreeMap::new, Collectors.counting()));
        out.println("\nSummary: " + byKind);
    }

    private static String resolveHint(Map<String, String> hints, String target) {
        if (hints == null || hints.isEmpty() || target == null) {
            return null;
        }
        String h = hints.get(hintKey(target));
        if (h != null) {
            return h;
        }
        int arrow = target.indexOf(" -> ");
        if (arrow >= 0) {
            String left = target.substring(0, arrow).trim();
            String right = target.substring(arrow + 4).trim();
            String lh = hints.get(hintKey(left));
            String rh = hints.get(hintKey(right));
            if (lh != null && rh != null) {
                return left + ": " + lh + "; " + right + ": " + rh;
            }
            if (lh != null) {
                return left + ": " + lh;
            }
            if (rh != null) {
                return right + ": " + rh;
            }
        }
        int hash = target.indexOf('#');
        if (hash >= 0) {
            return hints.get(target.substring(0, hash));
        }
        return null;
    }

    public static String hintKey(String target) {
        int p = target.indexOf('(');
        int c = target.indexOf(':');
        int cut = p >= 0 ? p : c;
        return cut >= 0 ? target.substring(0, cut) : target;
    }

    public static void json(Path file, Collection<Finding> findings) throws IOException {
        if (file == null) {
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of("findings", findings));
    }
}
