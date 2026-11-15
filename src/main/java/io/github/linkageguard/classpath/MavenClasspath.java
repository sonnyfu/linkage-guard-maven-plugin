package io.github.linkageguard.classpath;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the REAL runtime classpath via Maven itself (mediation, BOMs, exclusions all honoured).
 * "What-if" upgrades are done on a temp copy of the pom: pin in &lt;dependencyManagement&gt; (covers transitive deps)
 * and rewrite direct &lt;dependency&gt; versions.
 */
public final class MavenClasspath {
    private static final boolean WIN = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private MavenClasspath() {}

    public static List<Path> resolve(Path projectDir, Map<String, String> overrides, boolean includeTest)
            throws Exception {
        Path pom = projectDir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            throw new IllegalArgumentException("pom.xml not found in " + projectDir.toAbsolutePath());
        }
        Path effective = pom;
        Path tmpPom = null;
        if (!overrides.isEmpty()) {
            effective = tmpPom = projectDir.resolve("pom-linkage-guard.xml");
            writeOverridePom(pom, tmpPom, overrides);
        }
        Path out = Files.createTempFile("lg-cp", ".txt");
        try {
            String mvn = findMavenExecutable(projectDir);
            List<String> cmd = new ArrayList<>();
            cmd.add(mvn);
            Path localSettings = projectDir.resolve(".mvn/settings.xml");
            if (Files.exists(localSettings) && !isWrapper(projectDir, mvn)) {
                cmd.add("-s");
                cmd.add(localSettings.toAbsolutePath().toString());
            }
            cmd.add("-B");
            cmd.add("-q");
            cmd.add("-f");
            cmd.add(effective.toAbsolutePath().toString());
            cmd.add("dependency:build-classpath");
            cmd.add("-Dmdep.outputFile=" + out.toAbsolutePath());
            cmd.add("-DincludeScope=" + (includeTest ? "test" : "runtime"));

            Process p = new ProcessBuilder(cmd).directory(projectDir.toFile()).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                throw new IllegalStateException("mvn failed:\n" + log);
            }
            return ClasspathFiles.read(out);
        } finally {
            Files.deleteIfExists(out);
            if (tmpPom != null) {
                Files.deleteIfExists(tmpPom);
            }
        }
    }

    private static boolean isWrapper(Path projectDir, String mvn) {
        Path wrapper = projectDir.resolve(WIN ? "mvnw.cmd" : "mvnw").toAbsolutePath();
        return Path.of(mvn).toAbsolutePath().equals(wrapper);
    }

    static String findMavenExecutable(Path projectDir) {
        String exeName = WIN ? "mvn.cmd" : "mvn";
        Path wrapper = projectDir.resolve(WIN ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        String mavenHomeProp = System.getProperty("maven.home");
        if (mavenHomeProp != null && !mavenHomeProp.isBlank()) {
            Path candidate = Path.of(mavenHomeProp, "bin", exeName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        for (String envVar : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                Path candidate = Path.of(home, "bin", exeName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        if (WIN) {
            Path known = Path.of("D:\\program2\\apache-maven-3.9.0\\bin\\mvn.cmd");
            if (Files.isRegularFile(known)) {
                return known.toString();
            }
        }
        return exeName;
    }

    // ---- pom rewriting -------------------------------------------------------------------------------
    private static void writeOverridePom(Path src, Path dst, Map<String, String> overrides) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().parse(src.toFile());
        Element root = doc.getDocumentElement();
        Element mgd = child(child(root, "dependencyManagement", true, doc), "dependencies", true, doc);
        Element direct = child(root, "dependencies", false, doc);
        for (var o : overrides.entrySet()) {
            String[] ga = o.getKey().split(":");
            if (ga.length != 2) {
                throw new IllegalArgumentException("Expected groupId:artifactId, got " + o.getKey());
            }
            String g = ga[0];
            String a = ga[1];
            String v = o.getValue();
            for (Element d : children(mgd, "dependency")) {
                if (g.equals(text(d, "groupId")) && a.equals(text(d, "artifactId"))) {
                    mgd.removeChild(d);
                }
            }
            Element dep = doc.createElement("dependency");
            for (String[] kv : new String[][]{{"groupId", g}, {"artifactId", a}, {"version", v}}) {
                Element e = doc.createElement(kv[0]);
                e.setTextContent(kv[1]);
                dep.appendChild(e);
            }
            mgd.appendChild(dep);
            if (direct != null) {
                for (Element d : children(direct, "dependency")) {
                    if (g.equals(text(d, "groupId")) && a.equals(text(d, "artifactId"))) {
                        Element ve = child(d, "version", true, doc);
                        ve.setTextContent(v);
                    }
                }
            }
        }
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(dst.toFile()));
    }

    private static List<Element> children(Element p, String name) {
        List<Element> r = new ArrayList<>();
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                r.add(e);
            }
        }
        return r;
    }

    private static Element child(Element p, String name, boolean create, Document doc) {
        List<Element> l = children(p, name);
        if (!l.isEmpty()) {
            return l.get(0);
        }
        if (!create) {
            return null;
        }
        Element e = doc.createElement(name);
        p.appendChild(e);
        return e;
    }

    private static String text(Element p, String name) {
        Element e = child(p, name, false, null);
        return e == null ? null : e.getTextContent().trim();
    }
}
