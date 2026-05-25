package war.metaphor.tree;

import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.SymbolTable;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import war.metaphor.asm.JRemapper;
import war.jnt.dash.Ansi;
import war.jnt.dash.Level;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.asm.JClassWriter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import war.metaphor.base.ObfuscatorContext;

import static war.jnt.dash.Ansi.Color.YELLOW;

@Getter
public class JClassNode extends ClassNode implements Opcodes {

    public volatile boolean linked = false;

    private final Set<JClassNode> children;
    private final Set<JClassNode> parents;

    private final Set<String> exemptMembers = new HashSet<>();
    private boolean exemptSelf;

    private final boolean library;
    public SymbolTable symbolTable;
    public SymbolTable cachedSymbolTable;

    @Setter
    private String realName;

    @Setter
    private String liftedInitializer;
    private byte[] originalBytes;

    public JClassNode() {
        this(false);
    }

    public JClassNode(boolean library) {
        super(Opcodes.ASM8);
        this.library = library;
        this.children = ConcurrentHashMap.newKeySet();
        this.parents = ConcurrentHashMap.newKeySet();
        this.symbolTable = new SymbolTable(null);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        if (realName == null) realName = name;
    }

    public Set<JClassNode> getParents() {
        Hierarchy.INSTANCE.iterateClass(this);
        return parents;
    }

    public Set<JClassNode> getChildren() {
        Hierarchy.INSTANCE.iterateClass(this);
        return children;
    }

    public void addExempt() {
        exemptSelf = true;
    }

    public void addExemptMember(String member) {
        exemptMembers.add(member);
    }

    public void addExemptMember(MethodNode member) {
        exemptMembers.add(name + "." + member.name + member.desc);
    }

    public void addExemptMember(FieldNode member) {
        exemptMembers.add(name + "." + member.name + member.desc);
    }

    public boolean isExempt() {
        return exemptSelf;
    }

    public boolean isExempt(MethodNode method) {
        String name = this.name + "." + method.name + method.desc;
        return exemptMembers.contains(name);
    }

    public boolean isExempt(FieldNode field) {
        String name = this.name + "." + field.name + field.desc;
        return exemptMembers.contains(name);
    }

    public void addChild(JClassNode child) {
        children.add(child);
    }

    public void addParent(JClassNode parent) {
        parents.add(parent);
    }

    public boolean isFinal() {
        return (access & ACC_FINAL) != 0;
    }

    public boolean isInterface() {
        return (access & ACC_INTERFACE) != 0;
    }

    public boolean isEnum() {
        return (access & ACC_ENUM) != 0;
    }

    public boolean isAnnotation() {
        return (access & ACC_ANNOTATION) != 0;
    }

    public boolean hasAnnotation(String annotation) {
        if (visibleAnnotations != null && visibleAnnotations.stream().anyMatch(a -> a.desc.equals(annotation)))
            return true;
        return invisibleAnnotations != null && invisibleAnnotations.stream().anyMatch(a -> a.desc.equals(annotation));
    }

    public boolean isPublic() {
        return (access & ACC_PUBLIC) != 0;
    }

    public boolean isPrivate() {
        return (access & ACC_PRIVATE) != 0;
    }

    public String getPackage() {
        if (!name.contains("/")) return "";
        return name.substring(0, name.lastIndexOf('/') + 1);
    }

    public void cacheSymbolTable() {
        cachedSymbolTable = symbolTable.clone();
    }

    public void resetSymbolTable() {
        symbolTable = cachedSymbolTable;
        cachedSymbolTable = null;
    }

    public void removeExempt() {
        exemptMembers.clear();
        exemptSelf = false;
    }

    /** Store original bytecode before any mutation for use as a last-resort fallback. */
    public void storeOriginalBytes(byte[] bytes) {
        this.originalBytes = bytes;
    }

    public byte[] compute() {
        JClassWriter writer;
        // Tier 1: full frame recomputation (preferred).
        try {
            cacheSymbolTable();
            writer = new JClassWriter(ClassWriter.COMPUTE_FRAMES, symbolTable);
            symbolTable.classWriter = writer;
            accept(writer);
            return writer.toByteArray();
        } catch (Exception ex) {
            Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                    String.format("Could not compute class %s -> %s (%s)", ex.getMessage(),
                        new Ansi().c(YELLOW).s(name).r(false).c(Ansi.Color.BRIGHT_YELLOW),
                        new Ansi().c(YELLOW).s(realName).r(false).c(Ansi.Color.BRIGHT_YELLOW)));
        }
        // Tier 2: recompute maxs only (skips frame verification, handles most frame issues).
        try {
            resetSymbolTable();
            writer = new JClassWriter(ClassWriter.COMPUTE_MAXS, symbolTable);
            symbolTable.classWriter = writer;
            accept(writer);
            return writer.toByteArray();
        } catch (Exception ex2) {
            Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                    String.format("COMPUTE_MAXS also failed for %s (%s): %s",
                        new Ansi().c(YELLOW).s(name).r(false).c(Ansi.Color.BRIGHT_YELLOW),
                        new Ansi().c(YELLOW).s(realName).r(false).c(Ansi.Color.BRIGHT_YELLOW),
                        ex2.getMessage()));
        }
        // Tier 3: fall back to original unobfuscated bytes so the class is never
        // silently dropped from the JAR (which causes ClassNotFoundException at runtime).
        // Try stored bytes first; if null, re-read lazily from the input JAR by realName.
        byte[] fallback = originalBytes;
        if (fallback == null && ObfuscatorContext.INSTANCE != null
                && ObfuscatorContext.INSTANCE.getInput() != null
                && realName != null) {
            try (JarFile jar = new JarFile(ObfuscatorContext.INSTANCE.getInput().toFile())) {
                JarEntry entry = jar.getJarEntry(realName + ".class");
                if (entry != null) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        fallback = is.readAllBytes();
                    }
                }
            } catch (Exception ignored) { }
        }
        if (fallback != null) {
            Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                    String.format("Falling back to original bytes for %s (%s) — class will be unobfuscated",
                        new Ansi().c(YELLOW).s(name).r(false).c(Ansi.Color.BRIGHT_YELLOW),
                        new Ansi().c(YELLOW).s(realName).r(false).c(Ansi.Color.BRIGHT_YELLOW)));
            // The fallback bytes came from the input JAR: they still have old class names in
            // this_class AND inside method bodies (INVOKEVIRTUAL owners, CHECKCAST targets, etc.).
            // Re-apply the class rename mapping via ClassRemapper so all references are updated,
            // then re-emit.  This prevents NoClassDefFoundError for renamed classes referenced
            // from a fallback class that itself couldn't be fully obfuscated.
            java.util.Map<String, String> renameMap;
            if (ObfuscatorContext.INSTANCE != null
                    && ObfuscatorContext.INSTANCE.getClassRenameMap() != null) {
                renameMap = ObfuscatorContext.INSTANCE.getClassRenameMap();
            } else {
                renameMap = java.util.Collections.emptyMap();
            }
            if (!renameMap.isEmpty()) {
                // ── Tier A ──────────────────────────────────────────────────
                try {
                    ClassReader cr  = new ClassReader(fallback);
                    JClassNode  tmp = new JClassNode();
                    ClassRemapper remapper = new ClassRemapper(tmp, new JRemapper(renameMap));
                    cr.accept(remapper, ClassReader.SKIP_FRAMES);
                    ClassWriter cwA = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            // Conservative fallback: return Object.
                            // Incorrect for some branches but safe — the verifier
                            // accepts it and the code still runs correctly.
                            try { return super.getCommonSuperClass(type1, type2); }
                            catch (Exception ignored) { return "java/lang/Object"; }
                        }
                    };
                    tmp.accept(cwA);
                    return cwA.toByteArray();
                } catch (Exception tierA) {
                    Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                            String.format("Fallback tier A (COMPUTE_FRAMES) failed for %s: %s",
                                name, tierA.getMessage()));
                }
                // ── Tier B ──────────────────────────────────────────────────
                try {
                    ClassReader cr  = new ClassReader(fallback);
                    JClassNode  tmp = new JClassNode();
                    ClassRemapper remapper = new ClassRemapper(tmp, new JRemapper(renameMap));
                    cr.accept(remapper, ClassReader.SKIP_FRAMES);
                    ClassWriter cwB = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    tmp.accept(cwB);
                    byte[] bytes = cwB.toByteArray();
                    // Strip StackMapTable attributes and lower version to 50 (Java 6)
                    // so the JVM uses the forgiving type-inference verifier that does
                    // not require stack map frames.
                    bytes = stripStackMapsAndDowngrade(bytes);
                    return bytes;
                } catch (Exception tierB) {
                    Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                            String.format("Fallback tier B (COMPUTE_MAXS+strip) failed for %s: %s",
                                name, tierB.getMessage()));
                }
            }
            // ── Tier C ──────────────────────────────────────────────────────
            // Strip stack maps from the raw fallback bytes and downgrade version.
            // This handles the case where renameMap is empty or both tiers above failed.
            try {
                return stripStackMapsAndDowngrade(fallback);
            } catch (Exception tierC) {
                Logger.INSTANCE.logln(Level.WARNING, Origin.METAPHOR,
                        String.format("Fallback tier C (raw strip) failed for %s: %s",
                            name, tierC.getMessage()));
            }
            return fallback;
        }
        throw new RuntimeException("All compute() strategies failed for class " + name + " and no original bytes available");
    }


    /**
     * Strips StackMapTable attributes from every method in the class bytecode
     * and downgrades the class version to 50 (Java 6).
     *
     * Java 6 class files use the type-inference verifier which does not require
     * stack map frames.  This lets classes that are too large or too corrupt to
     * have frames recomputed (e.g. after heavy method inlining) still load and
     * run correctly on modern JVMs, which support version-50 class files
     * indefinitely for backwards compatibility.
     *
     * The bytecode layout of a .class file is:
     *   0xCAFEBABE (4) | minor (2) | major (2) | constant_pool ...
     * Major version 50 = Java 6.  We patch bytes [6..7] to 0x0032.
     *
     * StackMapTable is attribute_info with name "StackMapTable".
     * We use ASM ClassReader/ClassWriter with SKIP_FRAMES + no writer flag
     * so ASM simply copies everything except frame attributes, which it
     * drops when the reader skips them.
     */
    private static byte[] stripStackMapsAndDowngrade(byte[] classBytes) {
        // Read with SKIP_FRAMES — ASM will not copy StackMapTable attributes
        // into the ClassNode because it never parsed them.
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(classBytes);
        // Use a ClassNode as intermediate so we can patch the version.
        org.objectweb.asm.tree.ClassNode cn =
                new org.objectweb.asm.tree.ClassNode(Opcodes.ASM8);
        cr.accept(cn, ClassReader.SKIP_FRAMES);
        // Downgrade to Java 6 (major version 50) so the JVM does not require
        // stack map frames.  Minor version stays 0.
        if (cn.version > 50) cn.version = 50;
        // Write with no special flags — no frame recomputation, just copy.
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public MethodNode getStaticInit() {
        String name = "<clinit>";

        for (MethodNode method : methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) {
                return method;
            }
        }

        MethodNode method = new MethodNode(ACC_STATIC, name, "()V", null, null);
        method.instructions.add(new InsnNode(RETURN));
        methods.add(method);

        return method;
    }

    public MethodNode getLiftedInit() {
        String name = getLiftedName("<clinit>");

        for (MethodNode method : methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) {
                return method;
            }
        }

        MethodNode method = new MethodNode(ACC_STATIC, name, "()V", null, null);
        method.instructions.add(new InsnNode(RETURN));
        methods.add(method);

        return method;
    }

    public boolean isAssignableFrom(JClassNode class2) {
        if (this.equals(class2))
            return true;
        return Hierarchy.INSTANCE.getClassParents(class2).contains(this);
    }

    public void resetHierarchy() {
        children.clear();
        parents.clear();
        linked = false;
    }

    public MethodNode getMethod(String name, String desc) {
        Hierarchy.INSTANCE.iterateClass(this);
        MethodNode method = methods.stream().filter(m -> (name == null || m.name.equals(name)) && (desc == null || m.desc.equals(desc)))
                .findFirst().orElse(null);
        if (method == null) {
            for (JClassNode parent : parents) {
                method = parent.getMethod(name, desc);
                if (method != null)
                    return method;
            }
        }
        return method;
    }

    public FieldNode getField(String name, String desc) {
        Hierarchy.INSTANCE.iterateClass(this);
        FieldNode field = fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElse(null);
        if (field == null) {
            for (JClassNode parent : parents) {
                field = parent.getField(name, desc);
                if (field != null) return field;
            }
        }
        return field;
    }

    public void update(JClassNode use) {
        this.interfaces = new ArrayList<>();
        this.innerClasses = new ArrayList<>();
        this.fields = new ArrayList<>();
        this.methods = new ArrayList<>();
        this.visibleAnnotations = null;
        this.invisibleAnnotations = null;
        this.visibleTypeAnnotations = null;
        this.invisibleTypeAnnotations = null;
        this.attrs = null;
        this.signature = null;
        this.sourceDebug = null;
        this.sourceFile = null;
        this.module = null;
        this.nestHostClass = null;
        this.nestMembers = null;
        this.permittedSubclasses = null;
        this.setRealName(use.getRealName());
        use.accept(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (!(obj instanceof JClassNode other)) return false;
        return this.name.equals(other.name);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    public String getLiftedInitializer() {
        if (liftedInitializer == null || liftedInitializer.isEmpty()) {
            return "<clinit>";
        }

        return liftedInitializer;
    }

    public String getLiftedName(String name) {
        if (name.equals("<clinit>")) return getLiftedInitializer();
        return name;
    }

}
