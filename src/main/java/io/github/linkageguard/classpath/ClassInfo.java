package io.github.linkageguard.classpath;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

public final class ClassInfo {
    public final String name;
    public final String superName;
    public final String origin;
    public final String[] interfaces;
    public final int access;
    public final long crc;
    public final Map<String, Integer> methods; // name+desc -> access
    public final Map<String, Integer> fields;  // name:desc -> access

    private ClassInfo(
            String name,
            String superName,
            String[] interfaces,
            int access,
            String origin,
            long crc,
            Map<String, Integer> methods,
            Map<String, Integer> fields) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.access = access;
        this.origin = origin;
        this.crc = crc;
        this.methods = methods;
        this.fields = fields;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    static ClassInfo read(byte[] bytes, String origin, long crc) {
        ClassReader cr = new ClassReader(bytes);
        Map<String, Integer> m = new HashMap<>();
        Map<String, Integer> f = new HashMap<>();
        cr.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                        f.put(n + ":" + d, a);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        m.put(n + d, a);
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassInfo(
                cr.getClassName(),
                cr.getSuperName(),
                cr.getInterfaces(),
                cr.getAccess(),
                origin,
                crc,
                m,
                f);
    }
}
