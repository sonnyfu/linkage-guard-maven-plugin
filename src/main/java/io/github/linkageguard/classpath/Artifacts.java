package io.github.linkageguard.classpath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which artifacts changed between two classpaths. */
public final class Artifacts {
    private Artifacts() {}

    public record Ref(String id, String name, String version, Path jar) {}

    public record Diff(List<Ref[]> changed, List<Ref> added, List<Ref> removed) {}

    private static final Pattern FALLBACK = Pattern.compile("^(.+?)-(\\d.*)\\.jar$");

    public static Map<String, Ref> index(List<Path> cp) {
        Map<String, Ref> m = new LinkedHashMap<>();
        for (Path jar : cp) {
            Path filePath = jar.getFileName();
            if (filePath == null) {
                continue;
            }
            String file = filePath.toString();
            if (!file.endsWith(".jar")) {
                continue;
            }
            Path v = jar.getParent();
            Path a = v == null ? null : v.getParent();
            Ref r = null;
            if (a != null && a.getFileName() != null && v.getFileName() != null
                    && file.startsWith(a.getFileName() + "-" + v.getFileName())) { // maven repo layout
                r = new Ref(a.toString(), a.getFileName().toString(), v.getFileName().toString(), jar);
            } else {
                Matcher mt = FALLBACK.matcher(file);
                if (mt.matches()) {
                    r = new Ref(mt.group(1), mt.group(1), mt.group(2), jar);
                }
            }
            if (r != null) {
                m.putIfAbsent(r.id(), r);
            }
        }
        return m;
    }

    public static Diff diff(List<Path> before, List<Path> after) {
        Map<String, Ref> b = index(before);
        Map<String, Ref> a = index(after);
        List<Ref[]> changed = new ArrayList<>();
        List<Ref> added = new ArrayList<>();
        List<Ref> removed = new ArrayList<>();
        a.forEach((k, r) -> {
            Ref o = b.get(k);
            if (o == null) {
                added.add(r);
            } else if (!o.version().equals(r.version())) {
                changed.add(new Ref[]{o, r});
            }
        });
        b.forEach((k, r) -> {
            if (!a.containsKey(k)) {
                removed.add(r);
            }
        });
        return new Diff(changed, added, removed);
    }
}
