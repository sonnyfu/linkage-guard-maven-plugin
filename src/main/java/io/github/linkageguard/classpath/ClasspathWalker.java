package io.github.linkageguard.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClasspathWalker {
    private ClasspathWalker() {}

    @FunctionalInterface
    public interface Visitor {
        void accept(String origin, String entryName, long crc, byte[] bytes) throws IOException;
    }

    /** NOTE: multi-release (META-INF/versions) entries are skipped in this MVP: base classes only. */
    private static boolean skip(String n) {
        return !n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class");
    }

    public static void walk(Path entry, Visitor v) throws IOException {
        String origin = entry.toString();
        if (Files.isDirectory(entry)) {
            List<Path> files;
            try (var s = Files.walk(entry)) {
                files = s.filter(p -> p.toString().endsWith(".class")).toList();
            }
            for (Path p : files) {
                String rel = entry.relativize(p).toString().replace('\\', '/');
                if (skip(rel)) {
                    continue;
                }
                byte[] b = Files.readAllBytes(p);
                CRC32 c = new CRC32();
                c.update(b);
                v.accept(origin, rel, c.getValue(), b);
            }
        } else if (Files.isRegularFile(entry) && origin.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (ZipFile z = new ZipFile(entry.toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory() || skip(ze.getName())) {
                        continue;
                    }
                    try (InputStream in = z.getInputStream(ze)) {
                        v.accept(origin, ze.getName(), ze.getCrc(), in.readAllBytes());
                    }
                }
            }
        }
    }
}
