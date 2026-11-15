package io.github.linkageguard.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ignore filter: one prefix per line, '#' comments.
 * A finding is ignored if its subject OR target starts with a prefix.
 * Both JVM internal names (e.g. org/apache/log4j/) and dot-separated names (e.g. org.apache.log4j.) are supported.
 */
public final class Filters {
    private final List<String> prefixes;

    private Filters(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    public static Filters load(Path file) throws IOException {
        return load(file, List.of());
    }

    public static Filters load(Path file, Collection<String> extraPrefixes) throws IOException {
        Set<String> all = new LinkedHashSet<>();
        if (file != null) {
            for (String line : Files.readAllLines(file)) {
                addNormalized(all, line);
            }
        }
        if (extraPrefixes != null) {
            for (String p : extraPrefixes) {
                addNormalized(all, p);
            }
        }
        return new Filters(new ArrayList<>(all));
    }

    private static void addNormalized(Set<String> out, String rawLine) {
        if (rawLine == null) {
            return;
        }
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        out.add(trimmed);
        String normalized = normalizePrefix(trimmed);
        if (!normalized.equals(trimmed)) {
            out.add(normalized);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix.contains("/")) {
            return prefix;
        }
        int hash = prefix.indexOf('#');
        if (hash < 0) {
            return prefix.replace('.', '/');
        }
        return prefix.substring(0, hash).replace('.', '/') + prefix.substring(hash);
    }

    public boolean ignored(Finding f) {
        return prefixes.stream().anyMatch(p -> f.subject().startsWith(p) || f.target().startsWith(p));
    }
}
