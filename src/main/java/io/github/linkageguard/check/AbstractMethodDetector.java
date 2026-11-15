package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassInfo;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Kind;
import io.github.linkageguard.model.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

/**
 * Detects unimplemented abstract methods on concrete classes (which cause {@link AbstractMethodError} at runtime),
 * as well as conflicting default/abstract interface method inheritances ({@link IncompatibleClassChangeError}).
 */
final class AbstractMethodDetector {
    private AbstractMethodDetector() {}

    static void run(Context ctx, ClassInfo ci) {
        if (ci.isInterface() || ci.isAbstract()) {
            return;
        }
        Set<String> supertypes = ctx.hier().supertypes(ci.name);
        if (supertypes == null) {
            return; // some ancestor is missing; already reported as MISSING_CLASS
        }

        // 1. Walk superclass chain from ci up to java/lang/Object
        Set<String> concreteMethods = new HashSet<>();
        Set<String> objectPublicMethods = new HashSet<>();
        Map<String, String> unimplementedFromSuperclasses = new LinkedHashMap<>();

        for (String cur = ci.name; cur != null; ) {
            ClassInfo sc = ctx.idx().get(cur);
            if (sc == null) {
                return;
            }
            boolean isJavaLangObject = "java/lang/Object".equals(cur);
            for (var entry : sc.methods.entrySet()) {
                String sig = entry.getKey();
                if (sig.startsWith("<init>") || sig.startsWith("<clinit>")) {
                    continue;
                }
                int acc = entry.getValue();
                if ((acc & (ACC_PRIVATE | ACC_STATIC)) != 0) {
                    continue;
                }
                if ((acc & ACC_ABSTRACT) != 0) {
                    if (!concreteMethods.contains(sig)) {
                        unimplementedFromSuperclasses.putIfAbsent(sig, cur);
                    }
                } else {
                    concreteMethods.add(sig);
                    if (isJavaLangObject && (acc & ACC_PUBLIC) != 0) {
                        objectPublicMethods.add(sig);
                    }
                }
            }
            cur = sc.superName;
        }

        unimplementedFromSuperclasses.forEach((sig, declaringSuper) ->
                ctx.findings().add(new Finding(
                        Kind.ABSTRACT_METHOD,
                        Severity.ERROR,
                        ci.name,
                        ci.origin,
                        "<header>",
                        declaringSuper + "#" + sig,
                        "concrete class does not implement abstract method from " + declaringSuper)));

        // 2. Check all superinterfaces in supertypes
        Map<String, List<ClassInfo>> ifaceDeclarations = new LinkedHashMap<>();
        for (String st : supertypes) {
            ClassInfo itf = ctx.idx().get(st);
            if (itf == null || !itf.isInterface()) {
                continue;
            }
            for (var entry : itf.methods.entrySet()) {
                String sig = entry.getKey();
                if (sig.startsWith("<clinit>") || sig.startsWith("<init>")) {
                    continue;
                }
                int acc = entry.getValue();
                if ((acc & (ACC_PRIVATE | ACC_STATIC)) != 0) {
                    continue;
                }
                ifaceDeclarations.computeIfAbsent(sig, k -> new ArrayList<>()).add(itf);
            }
        }

        // Precompute non-Object concrete methods (protected Object methods like clone() do not satisfy public interface methods)
        Set<String> classConcreteExcludingObjectProtected = new HashSet<>(concreteMethods);
        ClassInfo obj = ctx.idx().get("java/lang/Object");
        if (obj != null) {
            for (var e : obj.methods.entrySet()) {
                if ((e.getValue() & ACC_PUBLIC) == 0) {
                    // Only restore if overridden in a subclass (checked via non-Object superclasses below)
                    classConcreteExcludingObjectProtected.remove(e.getKey());
                }
            }
            // Re-add any non-Object concrete implementation along the subclass chain
            for (String cur = ci.name; cur != null && !"java/lang/Object".equals(cur); ) {
                ClassInfo sc = ctx.idx().get(cur);
                if (sc == null) {
                    break;
                }
                for (var e : sc.methods.entrySet()) {
                    int acc = e.getValue();
                    if ((acc & (ACC_ABSTRACT | ACC_PRIVATE | ACC_STATIC)) == 0) {
                        classConcreteExcludingObjectProtected.add(e.getKey());
                    }
                }
                cur = sc.superName;
            }
        }

        Map<String, Set<String>> supertypeCache = new HashMap<>();
        for (var entry : ifaceDeclarations.entrySet()) {
            String sig = entry.getKey();
            if (classConcreteExcludingObjectProtected.contains(sig) || objectPublicMethods.contains(sig)) {
                continue;
            }
            List<ClassInfo> declarers = entry.getValue();
            Set<String> declarerNames = new LinkedHashSet<>();
            for (ClassInfo d : declarers) {
                declarerNames.add(d.name);
            }

            List<ClassInfo> maximallySpecific = new ArrayList<>();
            for (ClassInfo candidate : declarers) {
                boolean shadowedBySubinterface = false;
                for (ClassInfo other : declarers) {
                    if (candidate.name.equals(other.name)) {
                        continue;
                    }
                    Set<String> otherSupers = supertypeCache.computeIfAbsent(other.name, ctx.hier()::supertypes);
                    if (otherSupers != null && otherSupers.contains(candidate.name)) {
                        shadowedBySubinterface = true;
                        break;
                    }
                }
                if (!shadowedBySubinterface) {
                    maximallySpecific.add(candidate);
                }
            }

            if (maximallySpecific.isEmpty()) {
                continue;
            }
            boolean anyDefault = maximallySpecific.stream()
                    .anyMatch(itf -> (itf.methods.get(sig) & ACC_ABSTRACT) == 0);
            String firstMaxSpecific = maximallySpecific.get(0).name;
            if (!anyDefault) {
                ctx.findings().add(new Finding(
                        Kind.ABSTRACT_METHOD,
                        Severity.ERROR,
                        ci.name,
                        ci.origin,
                        "<header>",
                        firstMaxSpecific + "#" + sig,
                        "concrete class does not implement abstract interface method"));
            } else if (maximallySpecific.size() > 1) {
                ctx.findings().add(new Finding(
                        Kind.INCOMPATIBLE_CHANGE,
                        Severity.ERROR,
                        ci.name,
                        ci.origin,
                        "<header>",
                        firstMaxSpecific + "#" + sig,
                        "conflicting default/abstract interface methods inherited from multiple interfaces"));
            }
        }
    }
}
