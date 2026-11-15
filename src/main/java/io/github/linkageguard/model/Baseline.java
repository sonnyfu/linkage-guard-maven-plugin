package io.github.linkageguard.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class Baseline {
    private Baseline() {}

    public static void save(Collection<Finding> fs, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, fs.stream().map(Finding::key).sorted().toList());
    }

    public static Set<String> load(Path file) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                keys.add(trimmed);
            }
        }
        return keys;
    }
}
