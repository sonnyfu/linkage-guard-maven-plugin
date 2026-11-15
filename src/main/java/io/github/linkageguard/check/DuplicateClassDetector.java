package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.classpath.ClassInfo;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Findings;
import io.github.linkageguard.model.Kind;
import io.github.linkageguard.model.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same class name in several jars with different bytes: "first wins" -&gt; split-brain, CCE / NoSuchMethodError. */
final class DuplicateClassDetector {
    private DuplicateClassDetector() {}

    private record Pair(String winner, String loser) {}

    private static final class Group {
        final List<String> names = new ArrayList<>();
        int apiDiff;
    }

    static void run(ClassIndex idx, Findings out) {
        Map<Pair, Group> groups = new LinkedHashMap<>();
        for (var e : idx.duplicates().entrySet()) {
            List<ClassInfo> l = e.getValue();
            ClassInfo w = l.get(0);
            for (ClassInfo loser : l.subList(1, l.size())) {
                Group g = groups.computeIfAbsent(new Pair(w.origin, loser.origin), k -> new Group());
                g.names.add(e.getKey());
                if (!w.methods.keySet().equals(loser.methods.keySet())
                        || !w.fields.keySet().equals(loser.fields.keySet())) {
                    g.apiDiff++;
                }
            }
        }
        groups.forEach((p, g) -> {
            String w = file(p.winner());
            String l = file(p.loser());
            out.add(new Finding(
                    Kind.DUPLICATE_CLASS,
                    g.apiDiff > 0 ? Severity.ERROR : Severity.WARN,
                    w,
                    p.winner(),
                    null,
                    l,
                    g.names.size()
                            + " classes exist in both jars with different bytes ("
                            + g.apiDiff
                            + " with different API); the first one on the classpath wins. e.g. "
                            + g.names.get(0)));
        });
    }

    private static String file(String origin) {
        Path n = Path.of(origin).getFileName();
        return n == null ? origin : n.toString();
    }
}
