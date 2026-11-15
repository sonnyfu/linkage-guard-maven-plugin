package io.github.linkageguard;

import io.github.linkageguard.check.Analyzer;
import io.github.linkageguard.explain.ApiDiffExplainer;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Kind;
import io.github.linkageguard.model.Severity;
import io.github.linkageguard.mojo.CheckMojo;
import io.github.linkageguard.mojo.UpgradeMojo;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkageGuardTest {
    @TempDir
    Path tmp;

    static Path compile(Path dir, Map<String, String> sources, Path... cp) throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classes.toString());
        if (cp.length > 0) {
            args.add("-cp");
            args.add(Arrays.stream(cp).map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        for (var e : sources.entrySet()) {
            Path f = dir.resolve("src").resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        assertNotNull(jc, "JDK JavaCompiler must be available");
        assertEquals(0, jc.run(null, null, null, args.toArray(String[]::new)), "javac failed");
        return classes;
    }

    static Path jar(Path classesDir, Path jarFile) throws IOException {
        Files.createDirectories(jarFile.toAbsolutePath().getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile));
             var walk = Files.walk(classesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : files) {
                String entryName = classesDir.relativize(p).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    @Test
    void detectsMissingMethod_missingClass_andImpossibleCast() throws Exception {
        Path v1 = compile(
                tmp.resolve("v1"),
                Map.of(
                        "lib/Greeter.java", "package lib; public class Greeter { public String hello(String n){ return n; } }",
                        "lib/Legacy.java", "package lib; public class Legacy { }",
                        "lib/Animal.java", "package lib; public class Animal { }",
                        "lib/Dog.java", "package lib; public class Dog extends Animal { }",
                        "lib/Zoo.java", "package lib; public class Zoo { public static Animal get(){ return new Dog(); } }"));

        Path app = compile(
                tmp.resolve("app"),
                Map.of(
                        "app/Main.java",
                        """
                        package app;
                        public class Main {
                            public Object run() {
                                new lib.Greeter().hello("x");
                                new lib.Legacy();
                                return (lib.Dog) lib.Zoo.get();
                            }
                        }"""),
                v1);

        Path v2 = compile(
                tmp.resolve("v2"),
                Map.of(
                        "lib/Greeter.java", "package lib; public class Greeter { public String hello(String n, int k){ return n; } }",
                        "lib/Animal.java", "package lib; public class Animal { }",
                        "lib/Dog.java", "package lib; public class Dog { }",
                        "lib/Zoo.java", "package lib; public class Zoo { public static Animal get(){ return new Animal(); } }"));

        var ok = Analyzer.analyze(List.of(app), List.of(v1), false).findings();
        assertTrue(ok.stream().noneMatch(f -> f.severity() == Severity.ERROR), "v1 must be clean: " + ok);

        var bad = Analyzer.analyze(List.of(app), List.of(v2), false).findings();
        Set<Kind> kinds = bad.stream().map(Finding::kind).collect(Collectors.toSet());
        assertTrue(kinds.contains(Kind.MISSING_METHOD), "expected NoSuchMethodError, got " + bad);
        assertTrue(kinds.contains(Kind.MISSING_CLASS), "expected NoClassDefFoundError, got " + bad);
        assertTrue(kinds.contains(Kind.IMPOSSIBLE_CAST), "expected ClassCastException, got " + bad);
    }

    @Test
    void detectsAbstractMethodError() throws Exception {
        Path v1 = compile(
                tmp.resolve("abs-v1"),
                Map.of(
                        "lib/Plugin.java", "package lib; public interface Plugin { void start(); }",
                        "lib/BaseTask.java", "package lib; public abstract class BaseTask { public void init() {} }"));

        Path app = compile(
                tmp.resolve("abs-app"),
                Map.of(
                        "app/MyPlugin.java", "package app; public class MyPlugin implements lib.Plugin { public void start() {} }",
                        "app/MyTask.java", "package app; public class MyTask extends lib.BaseTask { }"),
                v1);

        Path v2Bad = compile(
                tmp.resolve("abs-v2-bad"),
                Map.of(
                        "lib/Plugin.java", "package lib; public interface Plugin { void start(); void stop(); }",
                        "lib/BaseTask.java", "package lib; public abstract class BaseTask { public void init() {} public abstract void execute(); }"));

        Path v2Default = compile(
                tmp.resolve("abs-v2-default"),
                Map.of(
                        "lib/Plugin.java", "package lib; public interface Plugin { void start(); default void stop() {} }",
                        "lib/BaseTask.java", "package lib; public abstract class BaseTask { public void init() {} }"));

        var ok = Analyzer.analyze(List.of(app), List.of(v1), false).findings();
        assertTrue(ok.stream().noneMatch(f -> f.severity() == Severity.ERROR), "v1 must be clean: " + ok);

        var bad = Analyzer.analyze(List.of(app), List.of(v2Bad), false).findings();
        List<Finding> absFindings = bad.stream().filter(f -> f.kind() == Kind.ABSTRACT_METHOD).toList();
        assertTrue(
                absFindings.stream().anyMatch(f -> f.subject().equals("app/MyPlugin") && f.target().equals("lib/Plugin#stop()V")),
                "expected ABSTRACT_METHOD for MyPlugin implementing Plugin#stop, got " + bad);
        assertTrue(
                absFindings.stream().anyMatch(f -> f.subject().equals("app/MyTask") && f.target().equals("lib/BaseTask#execute()V")),
                "expected ABSTRACT_METHOD for MyTask extending BaseTask#execute, got " + bad);

        var withDefault = Analyzer.analyze(List.of(app), List.of(v2Default), false).findings();
        assertTrue(
                withDefault.stream().noneMatch(f -> f.kind() == Kind.ABSTRACT_METHOD),
                "default interface method must not trigger ABSTRACT_METHOD: " + withDefault);
    }

    @Test
    void detectsIncompatibleClassChangeAndIllegalAccess() throws Exception {
        Path v1 = compile(
                tmp.resolve("icc-v1"),
                Map.of(
                        "lib/Util.java",
                        """
                        package lib;
                        public class Util {
                            public static String greet() { return "hi"; }
                            public String inst() { return "inst"; }
                        }""",
                        "lib/Service.java", "package lib; public interface Service { void serve(); }",
                        "lib/AccessTarget.java", "package lib; public class AccessTarget { public void secret() {} }"));

        Path app = compile(
                tmp.resolve("icc-app"),
                Map.of(
                        "app/Caller.java",
                        """
                        package app;
                        public class Caller {
                            public void call(lib.Service s) {
                                lib.Util.greet();
                                new lib.Util().inst();
                                if (s != null) {
                                    s.serve();
                                }
                                new lib.AccessTarget().secret();
                            }
                        }"""),
                v1);

        Path v2 = compile(
                tmp.resolve("icc-v2"),
                Map.of(
                        "lib/Util.java",
                        """
                        package lib;
                        public class Util {
                            public String greet() { return "hi"; }
                            public static String inst() { return "inst"; }
                        }""",
                        "lib/Service.java", "package lib; public class Service { public void serve() {} }",
                        "lib/AccessTarget.java", "package lib; public class AccessTarget { void secret() {} }"));

        var ok = Analyzer.analyze(List.of(app), List.of(v1), false).findings();
        assertTrue(ok.stream().noneMatch(f -> f.severity() == Severity.ERROR), "v1 must be clean: " + ok);

        var bad = Analyzer.analyze(List.of(app), List.of(v2), false).findings();
        assertTrue(
                bad.stream().anyMatch(f -> f.kind() == Kind.INCOMPATIBLE_CHANGE && f.target().startsWith("lib/Util#greet")),
                "expected static->instance INCOMPATIBLE_CHANGE: " + bad);
        assertTrue(
                bad.stream().anyMatch(f -> f.kind() == Kind.INCOMPATIBLE_CHANGE && f.target().startsWith("lib/Util#inst")),
                "expected instance->static INCOMPATIBLE_CHANGE: " + bad);
        assertTrue(
                bad.stream().anyMatch(f -> f.kind() == Kind.INCOMPATIBLE_CHANGE && f.target().startsWith("lib/Service#serve")),
                "expected interface->class INCOMPATIBLE_CHANGE: " + bad);
        assertTrue(
                bad.stream().anyMatch(f -> f.kind() == Kind.ILLEGAL_ACCESS && f.target().startsWith("lib/AccessTarget#secret")),
                "expected ILLEGAL_ACCESS for package-private method: " + bad);
    }

    @Test
    void detectsDuplicateClassesAndNamespaceMix() throws Exception {
        Path c1 = compile(
                tmp.resolve("dup1"),
                Map.of(
                        "dup/Shared.java", "package dup; public class Shared { public void a() {} }",
                        "javax/servlet/Servlet.java", "package javax.servlet; public interface Servlet {}"));
        Path c2 = compile(
                tmp.resolve("dup2"),
                Map.of(
                        "dup/Shared.java", "package dup; public class Shared { public void a() {} public void b() {} }",
                        "jakarta/servlet/Servlet.java", "package jakarta.servlet; public interface Servlet {}"));

        Path jar1 = jar(c1, tmp.resolve("jars/first-1.0.jar"));
        Path jar2 = jar(c2, tmp.resolve("jars/second-2.0.jar"));

        var res = Analyzer.analyze(List.of(), List.of(jar1, jar2), false);
        var findings = res.findings();

        assertTrue(
                findings.stream().anyMatch(f -> f.kind() == Kind.DUPLICATE_CLASS && f.severity() == Severity.ERROR),
                "expected DUPLICATE_CLASS ERROR: " + findings);
        assertTrue(
                findings.stream().anyMatch(f -> f.kind() == Kind.NAMESPACE_MIX && f.subject().equals("javax.servlet")),
                "expected NAMESPACE_MIX for javax.servlet vs jakarta.servlet: " + findings);
    }

    @Test
    void explainsChangesWithJapicmp() throws Exception {
        Path v1Classes = compile(
                tmp.resolve("exp-v1"),
                Map.of(
                        "lib/Greeter.java", "package lib; public class Greeter { public String hello(String n){ return n; } }",
                        "lib/Legacy.java", "package lib; public class Legacy { }"));
        Path v2Classes = compile(
                tmp.resolve("exp-v2"),
                Map.of("lib/Greeter.java", "package lib; public class Greeter { public String hello(String n, int k){ return n; } }"));

        Path v1Jar = jar(v1Classes, tmp.resolve("exp-jars/greeter-1.0.jar"));
        Path v2Jar = jar(v2Classes, tmp.resolve("exp-jars/greeter-2.0.jar"));

        Map<String, String> hints = ApiDiffExplainer.explain(v1Jar, "1.0", v2Jar, "2.0");
        assertTrue(hints.containsKey("lib/Legacy"), "expected explanation for removed Legacy class: " + hints);
        assertTrue(hints.get("lib/Legacy").contains("REMOVED"), hints.get("lib/Legacy"));
        assertTrue(hints.containsKey("lib/Greeter#hello"), "expected explanation for changed hello method: " + hints);
    }

    @Test
    void checkMojoAndUpgradeMojoEndToEnd() throws Exception {
        Path v1Classes = compile(
                tmp.resolve("mojo-v1"),
                Map.of(
                        "lib/Greeter.java", "package lib; public class Greeter { public String hello(String n){ return n; } }",
                        "lib/Legacy.java", "package lib; public class Legacy { }"));
        Path v2Classes = compile(
                tmp.resolve("mojo-v2"),
                Map.of("lib/Greeter.java", "package lib; public class Greeter { public String hello(String n, int k){ return n; } }"));

        Path appClasses = compile(
                tmp.resolve("mojo-app"),
                Map.of(
                        "app/Main.java",
                        """
                        package app;
                        public class Main {
                            public void run() {
                                new lib.Greeter().hello("world");
                                new lib.Legacy();
                            }
                        }"""),
                v1Classes);

        Path v1Jar = jar(v1Classes, tmp.resolve("mojo-jars/lib-1.0.jar"));
        Path v2Jar = jar(v2Classes, tmp.resolve("mojo-jars/lib-2.0.jar"));

        // 1. CheckMojo clean pass on v1
        CheckMojo checkV1 = new CheckMojo();
        configureMojoProject(checkV1, appClasses, v1Jar);
        checkV1.execute();

        // 2. CheckMojo fails on v2 and writes baseline
        Path baselineFile = tmp.resolve("baseline.txt");
        CheckMojo checkV2 = new CheckMojo();
        configureMojoProject(checkV2, appClasses, v2Jar);
        setField(checkV2, "writeBaseline", baselineFile.toFile());
        assertThrows(MojoFailureException.class, checkV2::execute);
        assertTrue(Files.exists(baselineFile));

        // 3. CheckMojo with baseline passes on v2
        CheckMojo checkV2WithBaseline = new CheckMojo();
        configureMojoProject(checkV2WithBaseline, appClasses, v2Jar);
        setField(checkV2WithBaseline, "baseline", baselineFile.toFile());
        checkV2WithBaseline.execute();

        // 4. CheckMojo with dot-separated ignores passes and writes empty JSON findings
        Path checkJson = tmp.resolve("reports/check.json");
        CheckMojo checkV2Ignored = new CheckMojo();
        configureMojoProject(checkV2Ignored, appClasses, v2Jar);
        setField(checkV2Ignored, "ignores", List.of("lib."));
        setField(checkV2Ignored, "jsonReport", checkJson.toFile());
        checkV2Ignored.execute();
        String checkJsonText = Files.readString(checkJson);
        assertTrue(checkJsonText.contains("\"findings\""), checkJsonText);
        assertFalse(checkJsonText.contains("MISSING_METHOD"), checkJsonText);

        // 5. UpgradeMojo detects newly introduced breakage between v1Jar and v2Jar
        Path beforeCp = tmp.resolve("before.cp");
        Path afterCp = tmp.resolve("after.cp");
        Files.writeString(beforeCp, v1Jar.toString());
        Files.writeString(afterCp, v2Jar.toString());
        Path upgradeJson = tmp.resolve("reports/upgrade.json");

        UpgradeMojo upgradeBad = new UpgradeMojo();
        configureMojoProject(upgradeBad, appClasses, v1Jar);
        setField(upgradeBad, "beforeClasspathFile", beforeCp.toFile());
        setField(upgradeBad, "afterClasspathFile", afterCp.toFile());
        setField(upgradeBad, "explain", true);
        setField(upgradeBad, "jsonReport", upgradeJson.toFile());
        assertThrows(MojoFailureException.class, upgradeBad::execute);

        String upgradeJsonText = Files.readString(upgradeJson);
        assertTrue(upgradeJsonText.contains("MISSING_METHOD"), upgradeJsonText);
        assertTrue(upgradeJsonText.contains("MISSING_CLASS"), upgradeJsonText);

        // 6. UpgradeMojo passes when no new breakage is introduced
        UpgradeMojo upgradeClean = new UpgradeMojo();
        configureMojoProject(upgradeClean, appClasses, v1Jar);
        setField(upgradeClean, "beforeClasspathFile", beforeCp.toFile());
        setField(upgradeClean, "afterClasspathFile", beforeCp.toFile());
        upgradeClean.execute();
    }

    private static void configureMojoProject(Object mojo, Path appClasses, Path depJar) throws Exception {
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setOutputDirectory(appClasses.toString());
        build.setTestOutputDirectory(appClasses.toString());
        project.setBuild(build);
        DefaultArtifact artifact = new DefaultArtifact(
                "com.example",
                "lib",
                "1.0",
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(depJar.toFile());
        project.setArtifacts(Set.of(artifact));
        setField(mojo, "project", project);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }
}
