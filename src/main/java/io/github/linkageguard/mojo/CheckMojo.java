package io.github.linkageguard.mojo;

import io.github.linkageguard.Main;
import io.github.linkageguard.check.Analyzer;
import io.github.linkageguard.classpath.ClasspathFiles;
import io.github.linkageguard.model.Baseline;
import io.github.linkageguard.model.Filters;
import io.github.linkageguard.model.Finding;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that the resolved Maven classpath is linkage-consistent before runtime.
 */
@Mojo(
        name = "check",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class CheckMojo extends AbstractLinkageMojo {

    @Parameter(property = "linkage-guard.baseline")
    protected File baseline;

    @Parameter(property = "linkage-guard.writeBaseline")
    protected File writeBaseline;

    @Parameter(property = "linkage-guard.classpathFile")
    protected File classpathFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping linkage-guard check (linkage-guard.skip=true).");
            return;
        }
        try {
            List<Path> app = resolveAppEntries();
            List<Path> libs = classpathFile != null
                    ? ClasspathFiles.read(classpathFile.toPath())
                    : resolveProjectDependencyJars();

            boolean scanLibs = (scope != null ? scope : Main.Scope.APP) == Main.Scope.ALL;
            Analyzer.Result res = Analyzer.analyze(app, libs, scanLibs);
            warnUnreadable(res);

            Filters filters = loadFilters();
            List<Finding> all = res.findings().stream().filter(f -> !filters.ignored(f)).toList();

            if (writeBaseline != null) {
                Baseline.save(all, writeBaseline.toPath());
                getLog().info("Wrote linkage-guard baseline to " + writeBaseline.getAbsolutePath());
            }

            Set<String> known = baseline != null && baseline.exists()
                    ? Baseline.load(baseline.toPath())
                    : Set.of();
            List<Finding> shown = all.stream().filter(f -> !known.contains(f.key())).toList();

            logAndWriteReport("linkage check", shown, Map.of());
            failIfNecessary(shown, "check");
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("linkage-guard check failed: " + e.getMessage(), e);
        }
    }
}
