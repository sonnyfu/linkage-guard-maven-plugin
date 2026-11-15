package io.github.linkageguard;

import io.github.linkageguard.check.Analyzer;
import io.github.linkageguard.classpath.Artifacts;
import io.github.linkageguard.classpath.ClasspathFiles;
import io.github.linkageguard.classpath.MavenClasspath;
import io.github.linkageguard.explain.ApiDiffExplainer;
import io.github.linkageguard.model.Baseline;
import io.github.linkageguard.model.Filters;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Severity;
import io.github.linkageguard.report.Reporter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "linkage-guard",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Find NoSuchMethodError / NoClassDefFoundError / AbstractMethodError / ClassCastException risks BEFORE runtime",
        subcommands = {Main.Check.class, Main.Upgrade.class})
public class Main implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public enum Scope {
        APP,
        ALL
    }

    abstract static class Common {
        @Option(names = "--project", description = "Maven project dir (needs mvn / mvnw)")
        Path project;

        @Option(names = "--app", split = ",", description = "App class dirs/jars (default: <project>/target/classes)")
        List<Path> app;

        @Option(names = "--scope", description = "APP = scan only app classes, ALL = scan every jar too (default: check=APP, upgrade=ALL)")
        Scope scope;

        @Option(names = "--ignore", description = "File with ignore prefixes (internal names, one per line)")
        Path ignore;

        @Option(names = "--json", description = "Write findings as JSON")
        Path json;

        @Option(names = "--max", defaultValue = "100", description = "Max findings printed")
        int max;

        @Option(names = "--include-test", description = "Use test scope classpath")
        boolean includeTest;

        List<Path> appEntries() {
            if (app != null) {
                return app;
            }
            return project != null ? List.of(project.resolve("target/classes")) : List.of();
        }

        Path requireProject() {
            if (project == null) {
                throw new IllegalArgumentException("--project is required (or pass classpath files)");
            }
            return project;
        }

        void warnUnreadable(Analyzer.Result r) {
            if (!r.index().unreadable().isEmpty()) {
                System.err.println(
                        "[warn] "
                                + r.index().unreadable().size()
                                + " unreadable class files, e.g. "
                                + r.index().unreadable().get(0));
            }
        }
    }

    @Command(name = "check", description = "Check that the current classpath is internally consistent")
    static class Check extends Common implements Callable<Integer> {
        @Option(names = "--classpath-file", description = "File with the classpath (instead of --project)")
        Path cpFile;

        @Option(names = "--baseline", description = "Only report findings NOT in this baseline file")
        Path baseline;

        @Option(names = "--write-baseline", description = "Write all current findings as baseline")
        Path writeBaseline;

        @Option(names = "--fail-on", defaultValue = "ERROR", description = "Exit 1 if a finding of this severity (or worse) exists")
        Severity failOn;

        @Override
        public Integer call() throws Exception {
            List<Path> libs = cpFile != null
                    ? ClasspathFiles.read(cpFile)
                    : MavenClasspath.resolve(requireProject(), Map.of(), includeTest);
            var res = Analyzer.analyze(appEntries(), libs, scope == Scope.ALL);
            warnUnreadable(res);
            Filters filters = Filters.load(ignore);
            List<Finding> all = res.findings().stream().filter(f -> !filters.ignored(f)).toList();
            if (writeBaseline != null) {
                Baseline.save(all, writeBaseline);
            }
            Set<String> known = baseline != null ? Baseline.load(baseline) : Set.of();
            List<Finding> shown = all.stream().filter(f -> !known.contains(f.key())).toList();

            Reporter.print("linkage check", shown, Map.of(), max, System.out);
            Reporter.json(json, shown);
            return shown.stream().anyMatch(f -> f.severity().ordinal() <= failOn.ordinal()) ? 1 : 0;
        }
    }

    @Command(name = "upgrade", description = "What-if: which NEW linkage problems does upgrading dependencies introduce?")
    static class Upgrade extends Common implements Callable<Integer> {
        @Option(names = "--set", split = ",", description = "groupId:artifactId:version (repeatable)")
        List<String> sets = List.of();

        @Option(names = "--before-cp", description = "classpath file BEFORE the upgrade (instead of --project)")
        Path beforeCp;

        @Option(names = "--after-cp", description = "classpath file AFTER the upgrade")
        Path afterCp;

        @Option(names = "--explain", description = "Run japicmp on changed artifacts to explain the root cause")
        boolean explain;

        @Option(names = "--fail-on", defaultValue = "ERROR", description = "Exit 1 if a finding of this severity (or worse) exists")
        Severity failOn;

        @Override
        public Integer call() throws Exception {
            Map<String, String> ov = new LinkedHashMap<>();
            for (String s : sets) {
                String[] p = s.split(":");
                if (p.length != 3) {
                    throw new IllegalArgumentException("--set expects groupId:artifactId:version, got " + s);
                }
                ov.put(p[0] + ":" + p[1], p[2]);
            }
            List<Path> beforeLibs = beforeCp != null
                    ? ClasspathFiles.read(beforeCp)
                    : MavenClasspath.resolve(requireProject(), Map.of(), includeTest);
            List<Path> afterLibs = afterCp != null
                    ? ClasspathFiles.read(afterCp)
                    : MavenClasspath.resolve(requireProject(), ov, includeTest);

            var diff = Artifacts.diff(beforeLibs, afterLibs);
            System.out.println("Changed artifacts:");
            diff.changed().forEach(
                    c -> System.out.println("  ~ " + c[0].name() + " " + c[0].version() + " -> " + c[1].version()));
            diff.added().forEach(a -> System.out.println("  + " + a.name() + " " + a.version()));
            diff.removed().forEach(
                    r -> System.out.println("  - " + r.name() + " " + r.version() + "   (removed: watch NoClassDefFoundError)"));

            boolean scanLibs = scope != Scope.APP;
            var before = Analyzer.analyze(appEntries(), beforeLibs, scanLibs);
            var after = Analyzer.analyze(appEntries(), afterLibs, scanLibs);
            warnUnreadable(after);

            Filters filters = Filters.load(ignore);
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

            Reporter.print(
                    "problems INTRODUCED by this upgrade (" + fixed + " pre-existing problem(s) fixed)",
                    introduced,
                    hints,
                    max,
                    System.out);
            Reporter.json(json, introduced);
            return introduced.stream().anyMatch(f -> f.severity().ordinal() <= failOn.ordinal()) ? 1 : 0;
        }
    }
}
