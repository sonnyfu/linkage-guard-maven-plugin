package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassIndex;
import io.github.linkageguard.classpath.ClassInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

/** JVMS 5.4.3 member resolution (simplified). */
final class Resolver {
    enum Status {
        FOUND,
        MISSING,
        UNKNOWN
    }

    record Result(Status status, ClassInfo declaring, int access) {
        static final Result NOT_FOUND = new Result(Status.MISSING, null, 0);
        static final Result UNRESOLVED = new Result(Status.UNKNOWN, null, 0); // ancestor missing: reported elsewhere
    }

    private final ClassIndex idx;

    Resolver(ClassIndex idx) {
        this.idx = idx;
    }

    Result method(String owner, String name, String desc) {
        String key = name + desc;
        if ("<init>".equals(name)) { // constructors are not inherited
            ClassInfo c = idx.get(owner);
            if (c == null) {
                return Result.UNRESOLVED;
            }
            Integer a = c.methods.get(key);
            return a == null ? Result.NOT_FOUND : new Result(Status.FOUND, c, a);
        }
        for (String n = owner; n != null; ) { // 1. class chain
            ClassInfo c = idx.get(n);
            if (c == null) {
                return Result.UNRESOLVED;
            }
            Integer a = c.methods.get(key);
            if (a != null) {
                return new Result(Status.FOUND, c, a);
            }
            n = c.superName;
        }
        Deque<String> q = new ArrayDeque<>(); // 2. super-interfaces (incl. default methods)
        for (String n = owner; n != null; n = idx.get(n).superName) {
            ClassInfo c = idx.get(n);
            if (c.interfaces != null) {
                q.addAll(Arrays.asList(c.interfaces));
            }
        }
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String n = q.poll();
            if (!seen.add(n)) {
                continue;
            }
            ClassInfo c = idx.get(n);
            if (c == null) {
                return Result.UNRESOLVED;
            }
            Integer a = c.methods.get(key);
            if (a != null && (a & (ACC_PRIVATE | ACC_STATIC)) == 0) {
                return new Result(Status.FOUND, c, a);
            }
            if (c.interfaces != null) {
                q.addAll(Arrays.asList(c.interfaces));
            }
        }
        return Result.NOT_FOUND;
    }

    Result field(String owner, String name, String desc) {
        return field(owner, name + ":" + desc, new HashSet<>());
    }

    private Result field(String n, String key, Set<String> seen) {
        if (n == null || !seen.add(n)) {
            return Result.NOT_FOUND;
        }
        ClassInfo c = idx.get(n);
        if (c == null) {
            return Result.UNRESOLVED;
        }
        Integer a = c.fields.get(key);
        if (a != null) {
            return new Result(Status.FOUND, c, a);
        }
        if (c.interfaces != null) {
            for (String i : c.interfaces) {
                Result r = field(i, key, seen);
                if (r.status() != Status.MISSING) {
                    return r;
                }
            }
        }
        return field(c.superName, key, seen);
    }

    /** protected is treated as accessible (lenient); private uses a nest approximation (same outermost class). */
    static boolean accessible(int access, ClassInfo declaring, String caller) {
        if ((access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
            return true;
        }
        if ((access & ACC_PRIVATE) != 0) {
            return outer(declaring.name).equals(outer(caller));
        }
        return pkg(declaring.name).equals(pkg(caller));
    }

    private static String outer(String n) {
        int i = n.indexOf('$');
        return i < 0 ? n : n.substring(0, i);
    }

    private static String pkg(String n) {
        int i = n.lastIndexOf('/');
        return i < 0 ? "" : n.substring(0, i);
    }
}
