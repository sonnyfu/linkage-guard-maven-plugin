package io.github.linkageguard.mojo;

import io.github.linkageguard.Main;
import io.github.linkageguard.check.Analyzer;
import io.github.linkageguard.model.Filters;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Severity;
import io.github.linkageguard.report.Reporter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared parameters and helpers for linkage-guard Maven plugin Mojos. */
public abstract class AbstractLinkageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "linkage-guard.skip", defaultValue = "false")
    protected boolean skip = false;

    /** APP = scan only project classes, ALL = scan project classes and all dependency JARs (default: check=APP, upgrade=ALL). */
    @Parameter(property = "linkage-guard.scope")
    protected Main.Scope scope;

    @Parameter(property = "linkage-guard.includeTest", defaultValue = "false")
    protected boolean includeTest = false;

    @Parameter(property = "linkage-guard.includeProvided", defaultValue = "true")
    protected boolean includeProvided = true;

    @Parameter(property = "linkage-guard.failOn", defaultValue = "ERROR")
    protected Severity failOn = Severity.ERROR;

    @Parameter(property = "linkage-guard.ignoreFile")
    protected File ignoreFile;

    @Parameter
    protected List<String> ignores = new ArrayList<>();

    @Parameter(property = "linkage-guard.jsonReport")
    protected File jsonReport;

    @Parameter(property = "linkage-guard.max", defaultValue = "100")
    protected int maxFindings = 100;

    /** Optional override of application class directories/JARs. */
    @Parameter(property = "linkage-guard.app")
    protected List<File> appDirectories;

    protected List<Path> resolveAppEntries() {
        List<Path> entries = new ArrayList<>();
        if (appDirectories != null && !appDirectories.isEmpty()) {
            for (File f : appDirectories) {
                if (f != null) {
                    entries.add(f.toPath());
                }
            }
            return entries;
        }
        if (project != null && project.getBuild() != null) {
            String outputDir = project.getBuild().getOutputDirectory();
            if (outputDir != null && !outputDir.isBlank()) {
                entries.add(Path.of(outputDir));
            }
            if (includeTest) {
                String testOutputDir = project.getBuild().getTestOutputDirectory();
                if (testOutputDir != null && !testOutputDir.isBlank()) {
                    entries.add(Path.of(testOutputDir));
                }
            }
        }
        return entries;
    }

    protected List<Path> resolveProjectDependencyJars() {
        List<Path> jars = new ArrayList<>();
        if (project == null || project.getArtifacts() == null) {
            return jars;
        }
        for (Artifact a : project.getArtifacts()) {
            if (a == null || a.getFile() == null) {
                continue;
            }
            String s = a.getScope();
            if (!includeTest && Artifact.SCOPE_TEST.equals(s)) {
                continue;
            }
            if (!includeProvided && Artifact.SCOPE_PROVIDED.equals(s)) {
                continue;
            }
            Path p = a.getFile().toPath();
            if (Files.exists(p)) {
                jars.add(p);
            }
        }
        return jars;
    }

    protected Path projectDir() throws MojoExecutionException {
        if (project != null && project.getBasedir() != null) {
            return project.getBasedir().toPath();
        }
        throw new MojoExecutionException("MavenProject basedir is required when classpath files are not specified.");
    }

    protected Filters loadFilters() throws IOException {
        Path file = ignoreFile != null ? ignoreFile.toPath() : null;
        return Filters.load(file, ignores);
    }

    protected void warnUnreadable(Analyzer.Result result) {
        List<String> unreadable = result.index().unreadable();
        if (!unreadable.isEmpty()) {
            getLog().warn(unreadable.size() + " unreadable class files, e.g. " + unreadable.get(0));
        }
    }

    protected void logAndWriteReport(
            String title, List<Finding> findings, Map<String, String> hints) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
            Reporter.print(title, findings, hints, maxFindings, ps);
        }
        String text = baos.toString(StandardCharsets.UTF_8);
        boolean hasFailure = findings.stream().anyMatch(f -> f.severity().ordinal() <= failOn.ordinal());
        for (String line : text.split("\\R")) {
            if (hasFailure) {
                getLog().error(line);
            } else if (!findings.isEmpty()) {
                getLog().warn(line);
            } else {
                getLog().info(line);
            }
        }
        if (jsonReport != null) {
            Reporter.json(jsonReport.toPath(), findings);
            getLog().info("Wrote linkage-guard JSON report to " + jsonReport.getAbsolutePath());
        }
    }

    protected void failIfNecessary(List<Finding> findings, String contextLabel) throws MojoFailureException {
        long failingCount = findings.stream()
                .filter(f -> f.severity().ordinal() <= failOn.ordinal())
                .count();
        if (failingCount > 0) {
            throw new MojoFailureException(
                    "linkage-guard "
                            + contextLabel
                            + " detected "
                            + failingCount
                            + " finding(s) with severity >= "
                            + failOn);
        }
    }
}
