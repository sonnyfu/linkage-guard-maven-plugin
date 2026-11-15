package io.github.linkageguard.classpath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reads mvn build-classpath output (path-separated) or one-path-per-line. */
public final class ClasspathFiles {
    private ClasspathFiles() {}

    public static List<Path> read(Path file) throws IOException {
        String s = Files.readString(file).trim();
        List<Path> out = new ArrayList<>();
        if (s.isEmpty()) {
            return out;
        }
        for (String part : s.split("[\\r\\n" + Pattern.quote(File.pathSeparator) + "]+")) {
            if (!part.isBlank()) {
                out.add(Path.of(part.trim()));
            }
        }
        return out;
    }
}
