package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.classpath.ClassInfo;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Findings;
import io.github.linkageguard.model.Kind;
import io.github.linkageguard.model.Severity;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** javax.X and jakarta.X API both on the classpath =&gt; objects of "the other world" blow up with CCE/NSME. */
final class NamespaceMixDetector {
    private NamespaceMixDetector() {}

    private static final List<String> FAMILIES = List.of(
            "servlet",
            "persistence",
            "validation",
            "transaction",
            "jms",
            "mail",
            "ws/rs",
            "xml/bind",
            "websocket");

    static void run(ClassIndex idx, Findings out) {
        for (String fam : FAMILIES) {
            Set<String> javax = new TreeSet<>();
            Set<String> jakarta = new TreeSet<>();
            for (ClassInfo c : idx.all()) {
                if (c.name.startsWith("javax/" + fam + "/")) {
                    javax.add(file(c.origin));
                } else if (c.name.startsWith("jakarta/" + fam + "/")) {
                    jakarta.add(file(c.origin));
                }
            }
            if (!javax.isEmpty() && !jakarta.isEmpty()) {
                out.add(new Finding(
                        Kind.NAMESPACE_MIX,
                        Severity.WARN,
                        "javax." + fam.replace('/', '.'),
                        "classpath",
                        null,
                        "jakarta." + fam.replace('/', '.'),
                        "both namespaces present. javax in " + javax + " ; jakarta in " + jakarta));
            }
        }
    }

    private static String file(String o) {
        Path n = Path.of(o).getFileName();
        return n == null ? o : n.toString();
    }
}
