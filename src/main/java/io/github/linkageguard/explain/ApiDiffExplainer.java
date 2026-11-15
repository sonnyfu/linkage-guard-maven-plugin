package io.github.linkageguard.explain;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.AccessModifier;
import japicmp.model.JApiChangeStatus;
import japicmp.model.JApiClass;
import japicmp.model.JApiConstructor;
import japicmp.model.JApiField;
import japicmp.model.JApiMethod;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds "why": which API the library itself removed/changed between two versions.
 * Keys: owner or owner#member
 */
public final class ApiDiffExplainer {
    private ApiDiffExplainer() {}

    public static Map<String, String> explain(Path oldJar, String oldV, Path newJar, String newV) {
        Map<String, String> hints = new HashMap<>();
        try {
            JarArchiveComparatorOptions o = new JarArchiveComparatorOptions();
            o.setAccessModifier(AccessModifier.PRIVATE);
            o.getIgnoreMissingClasses().setIgnoreAllMissingClasses(true);
            JarArchiveComparator cmp = new JarArchiveComparator(o);
            List<JApiClass> classes = cmp.compare(
                    new JApiCmpArchive(oldJar.toFile(), oldV),
                    new JApiCmpArchive(newJar.toFile(), newV));
            String tag = " [" + oldV + " -> " + newV + "]";
            for (JApiClass c : classes) {
                String owner = c.getFullyQualifiedName().replace('.', '/');
                if (c.getChangeStatus() == JApiChangeStatus.REMOVED) {
                    hints.put(owner, "class REMOVED" + tag);
                } else if (!c.isBinaryCompatible()) {
                    hints.put(owner, "class " + c.getChangeStatus() + " " + c.getCompatibilityChanges() + tag);
                }
                for (JApiConstructor ctor : c.getConstructors()) {
                    if (ctor.getChangeStatus() == JApiChangeStatus.REMOVED || !ctor.isBinaryCompatible()) {
                        hints.putIfAbsent(
                                owner + "#<init>",
                                "constructor " + ctor.getChangeStatus() + " " + ctor.getCompatibilityChanges() + tag);
                    }
                }
                for (JApiMethod m : c.getMethods()) {
                    if (m.getChangeStatus() == JApiChangeStatus.REMOVED
                            || !m.isBinaryCompatible()
                            || !m.isSourceCompatible()
                            || m.getChangeStatus() == JApiChangeStatus.NEW) {
                        hints.putIfAbsent(
                                owner + "#" + m.getName(),
                                "method " + m.getChangeStatus() + " " + m.getCompatibilityChanges() + tag);
                    }
                }
                for (JApiField f : c.getFields()) {
                    if (f.getChangeStatus() == JApiChangeStatus.REMOVED || !f.isBinaryCompatible()) {
                        hints.putIfAbsent(
                                owner + "#" + f.getName(),
                                "field " + f.getChangeStatus() + " " + f.getCompatibilityChanges() + tag);
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            System.err.println("[explain] japicmp failed for " + oldJar.getFileName() + ": " + e);
        }
        return hints;
    }
}
