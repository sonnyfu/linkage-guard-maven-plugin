package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.classpath.ClassInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Subtype queries and "provably impossible cast" logic. */
final class Hierarchy {
    private final ClassIndex idx;
    private final Map<String, Optional<Set<String>>> cache = new HashMap<>();

    Hierarchy(ClassIndex idx) {
        this.idx = idx;
    }

    /** self + all ancestors, or null if some ancestor cannot be resolved (=&gt; unknown, never report). */
    Set<String> supertypes(String name) {
        return cache.computeIfAbsent(name, this::compute).orElse(null);
    }

    private Optional<Set<String>> compute(String name) {
        Set<String> out = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(name));
        while (!q.isEmpty()) {
            String n = q.poll();
            if (!out.add(n)) {
                continue;
            }
            ClassInfo c = idx.get(n);
            if (c == null) {
                return Optional.empty();
            }
            if (c.superName != null) {
                q.add(c.superName);
            }
            if (c.interfaces != null) {
                q.addAll(Arrays.asList(c.interfaces));
            }
        }
        return Optional.of(out);
    }

    /** TRUE/FALSE, or null when unknown. */
    Boolean assignable(String from, String to) {
        if (from.equals(to) || to.equals("java/lang/Object")) {
            return true;
        }
        Set<String> s = supertypes(from);
        return s == null ? null : s.contains(to);
    }

    /** True only if a checkcast from static type {@code from} to {@code to} can NEVER succeed (for non-null). */
    boolean impossibleCast(String from, String to) {
        Boolean up = assignable(from, to);
        if (up == null || up) {
            return false;
        }
        Boolean down = assignable(to, from);
        if (down == null || down) {
            return false; // legal downcast
        }
        ClassInfo f = idx.get(from);
        ClassInfo t = idx.get(to);
        if (f == null || t == null) {
            return false;
        }
        boolean fi = f.isInterface();
        boolean ti = t.isInterface();
        if (!fi && !ti) {
            return true; // two unrelated classes
        }
        if (fi && ti) {
            return false; // some class could implement both
        }
        return (fi ? t : f).isFinal(); // final class that doesn't implement the interface
    }
}
