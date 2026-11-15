package io.github.linkageguard.mojo;

import io.github.linkageguard.Main;
import io.github.linkageguard.check.Analyzer;
import io.github.linkageguard.classpath.Artifacts;
import io.github.linkageguard.classpath.ClasspathFiles;
import io.github.linkageguard.classpath.MavenClasspath;
import io.github.linkageguard.explain.ApiDiffExplainer;
import io.github.linkageguard.model.Filters;
import io.github.linkageguard.model.Finding;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What-if upgrade check: reports only NEW linkage problems introduced by overriding dependency versions.
 */
@Mojo(
        name = "upgrade",
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class UpgradeMojo extends AbstractLinkageMojo {

    /** Coordinate overrides in groupId:artifactId:version format. */
    @Parameter(property = "linkage-guard.set")
    protected List<String> sets = new ArrayList<>();

    @Parameter(property = "linkage-guard.explain", defaultValue = "true")
    protected boolean explain = true;

    @Parameter(property = "linkage-guard.beforeCp")
    protected File beforeClasspathFile;

    @Parameter(property = "linkage-guard.afterCp")
    protected File afterClasspathFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping linkage-guard upgrade (linkage-guard.skip=true).");
            return;
        }
        try {
            Map<String, String> overrides = new LinkedHashMap<>();
            if (sets != null) {
                for (String s : sets) {
                    if (s == null || s.isBlank()) {
                        continue;
                    }
                    for (String token : s.split(",")) {
                        String trimmed = token.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        String[] p = trimmed.split(":");
                        if (p.length != 3) {
                            throw new MojoExecutionException(
                                    "Expected groupId:artifactId:version in <sets> / -Dlinkage-guard.set, got: " + trimmed);
                        }
                        overrides.put(p[0] + ":" + p[1], p[2]);
                    }
                }
            }

            List<Path> beforeLibs = beforeClasspathFile != null
                    ? ClasspathFiles.read(beforeClasspathFile.toPath())
                    : MavenClasspath.resolve(projectDir(), Map.of(), includeTest);

            List<Path> afterLibs;
            if (afterClasspathFile != null) {
                afterLibs = ClasspathFiles.read(afterClasspathFile.toPath());
            } else if (!overrides.isEmpty()) {
                afterLibs = MavenClasspath.resolve(projectDir(), overrides, includeTest);
            } else {
                throw new MojoExecutionException(
                        "Specify at least one -Dlinkage-guard.set=groupId:artifactId:version or afterClasspathFile.");
            }

            Artifacts.Diff diff = Artifacts.diff(beforeLibs, afterLibs);
            getLog().info("Changed artifacts:");
            diff.changed().forEach(c -> getLog().info("  ~ " + c[0].name() + " " + c[0].version() + " -> " + c[1].version()));
            diff.added().forEach(a -> getLog().info("  + " + a.name() + " " + a.version()));
            diff.removed().forEach(r -> getLog().info("  - " + r.name() + " " + r.version() + "   (removed: watch NoClassDefFoundError)"));

            List<Path> app = resolveAppEntries();
            boolean scanLibs = (scope != null ? scope : Main.Scope.ALL) == Main.Scope.ALL;
            Analyzer.Result before = Analyzer.analyze(app, beforeLibs, scanLibs);
            Analyzer.Result after = Analyzer.analyze(app, afterLibs, scanLibs);
            warnUnreadable(after);

            Filters filters = loadFilters();
            Set<String> existing = new HashSet<>();
            before.findings().forEach(f -> existing.add(f.key()));

            List<Finding> introduced = after.findings().stream()
                    .filter(f -> !filters.ignored(f) && !existing.contains(f.key()))
                    .toList();

            Set<String> nowKeys = new HashSet<>();
            after.findings().forEach(f -> nowKeys.add(f.key()));
            long fixed = before.findings().stream().filter(f -> !nowKeys.contains(f.key())).count();

            Map<String, String> hints = new HashMap<>();
            if (explain && !introduced.isEmpty()) {
                for (Artifacts.Ref[] c : diff.changed()) {
                    hints.putAll(ApiDiffExplainer.explain(c[0].jar(), c[0].version(), c[1].jar(), c[1].version()));
                }
            }

            logAndWriteReport(
                    "problems INTRODUCED by this upgrade (" + fixed + " pre-existing problem(s) fixed)",
                    introduced,
                    hints);
            failIfNecessary(introduced, "upgrade");
        } catch (MojoFailureException | MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("linkage-guard upgrade failed: " + e.getMessage(), e);
        }
    }
}
