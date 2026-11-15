package io.github.linkageguard.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Metadata of every class on the classpath (first-wins, like the JVM) + JDK fallback. */
public final class ClassIndex {
    private static final Map<String, Module> BOOT_PACKAGE_MODULES = buildBootPackageModules();

    private final Map<String, ClassInfo> classes = new HashMap<>(1 << 16);
    private final Map<String, List<ClassInfo>> duplicates = new TreeMap<>(); // name -> [winner, losers...] (content differs)
    private final Map<String, Optional<ClassInfo>> jdk = new HashMap<>();
    private final List<String> unreadable = new ArrayList<>();

    private static Map<String, Module> buildBootPackageModules() {
        Map<String, Module> map = new HashMap<>();
        for (Module m : ModuleLayer.boot().modules()) {
            if (!m.isNamed()) {
                continue;
            }
            for (String pkg : m.getPackages()) {
                map.putIfAbsent(pkg.replace('.', '/'), m);
            }
        }
        return map;
    }

    public static ClassIndex build(List<Path> entries) throws IOException {
        ClassIndex idx = new ClassIndex();
        for (Path e : entries) {
            ClasspathWalker.walk(e, idx::add);
        }
        return idx;
    }

    private void add(String origin, String entryName, long crc, byte[] bytes) {
        try {
            ClassInfo ci = ClassInfo.read(bytes, origin, crc);
            ClassInfo prev = classes.putIfAbsent(ci.name, ci);
            if (prev != null && prev.crc != ci.crc) {
                duplicates.computeIfAbsent(ci.name, n -> new ArrayList<>(List.of(prev))).add(ci);
            }
        } catch (RuntimeException ex) {
            unreadable.add(origin + "!" + entryName + " : " + ex.getMessage());
        }
    }

    /** @return null if the class exists neither on the classpath nor in the JDK running this tool */
    public ClassInfo get(String internalName) {
        ClassInfo c = classes.get(internalName);
        return c != null ? c : jdk.computeIfAbsent(internalName, this::loadJdk).orElse(null);
    }

    private Optional<ClassInfo> loadJdk(String name) {
        String resource = name + ".class";
        try (InputStream platformIn = ClassLoader.getPlatformClassLoader().getResourceAsStream(resource)) {
            if (platformIn != null) {
                return Optional.of(ClassInfo.read(platformIn.readAllBytes(), "JDK", 0));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        int slash = name.lastIndexOf('/');
        String pkg = slash > 0 ? name.substring(0, slash) : "";
        Module m = BOOT_PACKAGE_MODULES.get(pkg);
        if (m == null) {
            return Optional.empty();
        }
        try (InputStream modIn = m.getResourceAsStream(resource)) {
            return modIn == null ? Optional.empty() : Optional.of(ClassInfo.read(modIn.readAllBytes(), "JDK", 0));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Collection<ClassInfo> all() {
        return classes.values();
    }

    public Map<String, List<ClassInfo>> duplicates() {
        return duplicates;
    }

    public List<String> unreadable() {
        return unreadable;
    }
}
