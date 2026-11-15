package io.github.linkageguard.check;

import io.github.linkageguard.classpath.ClassInfo;
import io.github.linkageguard.model.Finding;
import io.github.linkageguard.model.Kind;
import io.github.linkageguard.model.Severity;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.H_INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.H_NEWINVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;

/** One pass over a class: linkage checks + cast checks. */
final class ClassScanner extends ClassVisitor {
    private final Context ctx;
    private final String origin;
    private String cls;

    ClassScanner(Context ctx, String origin) {
        super(ASM9);
        this.ctx = ctx;
        this.origin = origin;
    }

    private void add(Kind k, Severity s, String loc, String target, String msg) {
        ctx.findings().add(new Finding(k, s, cls, origin, loc, target, msg));
    }

    static String elementClass(String internalOrArray) {
        Type t = Type.getObjectType(internalOrArray);
        if (t.getSort() == Type.ARRAY) {
            t = t.getElementType();
        }
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    // ---- class header: extends / implements ---------------------------------------------------------
    @Override
    public void visit(int ver, int access, String name, String sig, String sup, String[] itfs) {
        cls = name;
        if (sup != null) {
            ClassInfo s = ctx.idx().get(sup);
            if (s == null) {
                add(Kind.MISSING_CLASS, Severity.ERROR, "<header>", sup, "superclass not found");
            } else if (s.isInterface()) {
                add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, "<header>", sup, "superclass is now an interface");
            } else if (s.isFinal()) {
                add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, "<header>", sup, "superclass is now final");
            }
        }
        if (itfs != null) {
            for (String i : itfs) {
                ClassInfo ic = ctx.idx().get(i);
                if (ic == null) {
                    add(Kind.MISSING_CLASS, Severity.ERROR, "<header>", i, "interface not found");
                } else if (!ic.isInterface()) {
                    add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, "<header>", i, "is no longer an interface");
                }
            }
        }
    }

    @Override
    public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
        return new Code(name);
    }

    // ---- method bodies ------------------------------------------------------------------------------
    private final class Code extends MethodVisitor {
        private final String method;
        private int line;
        /** static type of the value on top of the stack if we know it (result of last call / field read / cast) */
        private String last;

        Code(String method) {
            super(ASM9);
            this.method = method;
        }

        private String loc() {
            return cls + "." + method + ":" + line;
        }

        private void classRef(String ref, Severity sev, String why) {
            String c = elementClass(ref);
            if (c != null && ctx.idx().get(c) == null) {
                add(Kind.MISSING_CLASS, sev, loc(), c, why);
            }
        }

        @Override
        public void visitLineNumber(int l, Label s) {
            line = l;
        }

        @Override
        public void visitTryCatchBlock(Label s, Label e, Label h, String type) {
            if (type != null) {
                classRef(type, Severity.WARN, "exception type in catch clause not found");
            }
        }

        @Override
        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            checkMethod(op, owner, name, desc);
            Type r = Type.getReturnType(desc);
            last = r.getSort() == Type.OBJECT ? r.getInternalName() : null;
        }

        @Override
        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
            for (Object a : args) { // method references: Foo::bar
                if (a instanceof Handle h && h.getTag() >= H_INVOKEVIRTUAL) {
                    int op = switch (h.getTag()) {
                        case H_INVOKESTATIC -> INVOKESTATIC;
                        case H_INVOKEINTERFACE -> INVOKEINTERFACE;
                        case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> INVOKESPECIAL;
                        default -> INVOKEVIRTUAL;
                    };
                    checkMethod(op, h.getOwner(), h.getName(), h.getDesc());
                }
            }
            last = null;
        }

        private void checkMethod(int op, String owner, String name, String desc) {
            if (owner.startsWith("[")
                    || "java/lang/invoke/MethodHandle".equals(owner)
                    || "java/lang/invoke/VarHandle".equals(owner)) {
                return; // arrays / signature-polymorphic
            }
            ClassInfo oc = ctx.idx().get(owner);
            if (oc == null) {
                add(Kind.MISSING_CLASS, Severity.ERROR, loc(), owner,
                        "class not found (needed by call to " + name + ")");
                return;
            }
            String target = owner + "#" + name + desc;
            if (op == INVOKEINTERFACE && !oc.isInterface()) {
                add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, loc(), target,
                        "class is no longer an interface, call site uses invokeinterface");
                return;
            }
            if (op == INVOKEVIRTUAL && oc.isInterface()) {
                add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, loc(), target,
                        "target is now an interface, call site uses invokevirtual");
                return;
            }
            Resolver.Result r = ctx.resolver().method(owner, name, desc);
            switch (r.status()) {
                case MISSING -> add(Kind.MISSING_METHOD, Severity.ERROR, loc(), target,
                        "no such method in " + owner + " or its supertypes");
                case FOUND -> {
                    boolean st = (r.access() & ACC_STATIC) != 0;
                    if (st != (op == INVOKESTATIC)) {
                        add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, loc(), target,
                                st ? "method is now static, call site expects instance method"
                                        : "method is no longer static, call site expects static method");
                    } else if (!Resolver.accessible(r.access(), r.declaring(), cls)) {
                        add(Kind.ILLEGAL_ACCESS, Severity.ERROR, loc(), target, "not accessible from " + cls);
                    }
                }
                case UNKNOWN -> {
                    // an ancestor is missing; that is reported at its own reference
                }
            }
        }

        @Override
        public void visitFieldInsn(int op, String owner, String name, String desc) {
            ClassInfo oc = ctx.idx().get(owner);
            if (oc == null) {
                add(Kind.MISSING_CLASS, Severity.ERROR, loc(), owner,
                        "class not found (needed by field " + name + ")");
            } else {
                String target = owner + "#" + name + ":" + desc;
                Resolver.Result r = ctx.resolver().field(owner, name, desc);
                switch (r.status()) {
                    case MISSING -> add(Kind.MISSING_FIELD, Severity.ERROR, loc(), target,
                            "no such field in " + owner + " or its supertypes");
                    case FOUND -> {
                        boolean st = (r.access() & ACC_STATIC) != 0;
                        boolean stOp = op == GETSTATIC || op == PUTSTATIC;
                        if (st != stOp) {
                            add(Kind.INCOMPATIBLE_CHANGE, Severity.ERROR, loc(), target,
                                    "static-ness of field changed");
                        } else if (!Resolver.accessible(r.access(), r.declaring(), cls)) {
                            add(Kind.ILLEGAL_ACCESS, Severity.ERROR, loc(), target,
                                    "not accessible from " + cls);
                        }
                    }
                    case UNKNOWN -> { }
                }
            }
            Type t = Type.getType(desc);
            last = (op == GETFIELD || op == GETSTATIC) && t.getSort() == Type.OBJECT ? t.getInternalName() : null;
        }

        @Override
        public void visitTypeInsn(int op, String type) {
            classRef(type, op == NEW ? Severity.ERROR : Severity.WARN, "class not found (" + opName(op) + ")");
            if (op == CHECKCAST
                    && last != null
                    && !type.isEmpty()
                    && type.charAt(0) != '['
                    && ctx.idx().get(type) != null
                    && ctx.idx().get(last) != null
                    && ctx.hier().impossibleCast(last, type)) {
                add(Kind.IMPOSSIBLE_CAST, Severity.ERROR, loc(), last + " -> " + type,
                        type + " is not a subtype of " + last + " (nor vice-versa): this cast always throws ClassCastException");
            }
            last = (op == CHECKCAST && !type.isEmpty() && type.charAt(0) != '[') ? type : null;
        }

        private String opName(int op) {
            if (op == NEW) {
                return "new";
            }
            if (op == CHECKCAST) {
                return "checkcast";
            }
            if (op == INSTANCEOF) {
                return "instanceof";
            }
            return "anewarray";
        }

        @Override
        public void visitLdcInsn(Object v) {
            if (v instanceof Type t && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) {
                classRef(t.getInternalName(), Severity.WARN, "class literal not found");
            }
            last = null;
        }

        // everything else invalidates our "type on top of stack" knowledge
        @Override public void visitInsn(int o) { last = null; }
        @Override public void visitIntInsn(int o, int a) { last = null; }
        @Override public void visitVarInsn(int o, int v) { last = null; }
        @Override public void visitJumpInsn(int o, Label l) { last = null; }
        @Override public void visitIincInsn(int v, int i) { last = null; }
        @Override public void visitTableSwitchInsn(int a, int b, Label d, Label... l) { last = null; }
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { last = null; }
        @Override public void visitMultiANewArrayInsn(String d, int n) { last = null; }
    }
}
