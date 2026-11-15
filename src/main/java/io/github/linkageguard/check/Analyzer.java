package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.classpath.ClassInfo;
import io.github.linkageguard.classpath.ClasspathWalker;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Findings;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Public entry point for static linkage analysis. */
public final class Analyzer {
    private Analyzer() {}

    public record Result(ClassIndex index, List<Finding> findings) {}

    /**
     * @param app      application class dirs / jars (always indexed, scanned)
     * @param libs     dependency jars in classpath order
     * @param scanLibs also scan library bytecode (needed to catch "lib A calls removed API of lib B")
     */
    public static Result analyze(List<Path> app, List<Path> libs, boolean scanLibs) throws IOException {
        List<Path> all = new ArrayList<>(app);
        all.addAll(libs);
        all.removeIf(p -> !Files.exists(p));
        ClassIndex idx = ClassIndex.build(all);
        Context ctx = new Context(idx, new Resolver(idx), new Hierarchy(idx), new Findings());

        List<Path> toScan = scanLibs ? all : app.stream().filter(Files::exists).toList();
        for (Path entry : toScan) {
            ClasspathWalker.walk(entry, (origin, name, crc, bytes) -> {
                ClassInfo ci = idx.get(name.substring(0, name.length() - ".class".length()));
                if (ci == null || !origin.equals(ci.origin)) {
                    return; // shadowed duplicate: never loaded by the JVM
                }
                try {
                    new ClassReader(bytes).accept(new ClassScanner(ctx, origin), ClassReader.SKIP_FRAMES);
                } catch (RuntimeException ignored) {
                    // reported via idx.unreadable()
                }
                AbstractMethodDetector.run(ctx, ci);
            });
        }
        DuplicateClassDetector.run(idx, ctx.findings());
        NamespaceMixDetector.run(idx, ctx.findings());
        return new Result(idx, ctx.findings().all());
    }
}
