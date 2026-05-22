package war.metaphor.mutator.misc;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;
import war.metaphor.util.builder.ClassBuilder;
import war.metaphor.util.builder.InsnListBuilder;
import war.metaphor.util.builder.MethodBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FakeClassMutator
 *
 * Generates and injects convincing decoy classes into the output JAR.
 * These classes look completely legitimate to decompilers and reverse
 * engineers — they have real fields, real method bodies with actual logic,
 * real inheritance, and realistic naming — but they are never referenced
 * by any real code path and contribute nothing to runtime behaviour.
 *
 * Goals:
 *   - Inflate JAR class count to bury real classes in noise
 *   - Force analysts to waste time inspecting dead classes
 *   - Pollute call-graph tools (e.g. Recaf, JD-GUI, JADX) with false edges
 *   - Mimic real framework patterns (service, util, handler, config, repo)
 *     so decoys are indistinguishable at a glance from real application code
 *
 * What each fake class contains:
 *   - A realistic package path sampled from your real classes' packages
 *   - 2–6 instance fields of varied types (String, int, boolean, long, List, Map)
 *   - A full constructor that initialises every field
 *   - 2–5 methods with realistic non-trivial bytecode bodies:
 *       * Arithmetic loops
 *       * String building
 *       * Array manipulation
 *       * Conditional branches
 *       * Exception handling blocks
 *   - A toString() override
 *   - Static utility methods
 *   - Optionally extends a randomly chosen real class (safe — no method override)
 *
 * Configuration (config.yml):
 *
 *   fake-class:
 *     enabled: true
 *     count: 50              # total fake classes to inject (default 50)
 *     methods-min: 2         # min methods per fake class (default 2)
 *     methods-max: 5         # max methods per fake class (default 5)
 *     fields-min: 2          # min fields per fake class (default 2)
 *     fields-max: 6          # max fields per fake class (default 6)
 *     use-real-packages: true  # inject into packages that already exist (default true)
 *     extend-real: false       # randomly extend real non-final classes (default false)
 *
 * Registration in Metaphor.java:
 *   .mutator("fake-class", FakeClassMutator.class)
 *
 * Recommended order:
 *   Place AFTER renaming (so fake class names don't collide with pre-rename names)
 *   and BEFORE strip (so fake classes also get debug info stripped).
 *
 *   ... renamer.field
 *   - fake-class          ← here
 *   - flow.break
 *   ...
 *
 * @author jnt
 */
@Stability(Level.HIGH)
public class FakeClassTransformer extends Mutator {

    // ── config ────────────────────────────────────────────────────────────────

    private final int     count;
    private final int     methodsMin;
    private final int     methodsMax;
    private final int     fieldsMin;
    private final int     fieldsMax;
    private final boolean useRealPackages;
    private final boolean extendReal;

    // ── realistic class name suffixes to mimic framework patterns ─────────────

    private static final String[] SUFFIXES = {
        "Service", "Manager", "Handler", "Controller", "Repository",
        "Processor", "Util", "Helper", "Factory", "Builder",
        "Adapter", "Provider", "Resolver", "Listener", "Observer",
        "Executor", "Dispatcher", "Scheduler", "Registry", "Cache",
        "Validator", "Converter", "Formatter", "Parser", "Encoder",
        "Decoder", "Client", "Context", "Session", "Request",
        "Response", "Wrapper", "Delegate", "Interceptor", "Filter",
        "Pool", "Queue", "Event", "Task", "Job",
        "Transformer", "Mapper", "Loader", "Writer", "Reader",
        "Scanner", "Checker", "Guard", "Monitor", "Tracker"
        "Nigga", "NiggaFuck"
    };

    // ── field descriptors to inject (varied, realistic types) ─────────────────

    private static final String[] FIELD_DESCS = {
        "Ljava/lang/String;",
        "I",
        "Z",
        "J",
        "D",
        "Ljava/util/List;",
        "Ljava/util/Map;",
        "Ljava/util/Set;",
        "[Ljava/lang/String;",
        "[I",
        "Ljava/util/concurrent/atomic/AtomicInteger;",
        "Ljava/util/concurrent/atomic/AtomicBoolean;",
        "Ljava/lang/StringBuilder;",
        "Ljava/util/logging/Logger;"
    };

    // ── static initializer values used in fake clinit ─────────────────────────

    private static final String[] FAKE_STRINGS = {
        "application/json", "UTF-8", "yyyy-MM-dd HH:mm:ss",
        "localhost", "127.0.0.1", "0.0.0.0",
        "INFO", "DEBUG", "ERROR", "WARN",
        "true", "false", "null", "default",
        "v1", "v2", "api", "internal",
        "com.example", "org.example", "io.example"
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public FakeClassTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.count          = config == null ? 50    : config.getInt("count", 50);
        this.methodsMin     = config == null ? 2     : config.getInt("methods-min", 2);
        this.methodsMax     = config == null ? 5     : config.getInt("methods-max", 5);
        this.fieldsMin      = config == null ? 2     : config.getInt("fields-min", 2);
        this.fieldsMax      = config == null ? 6     : config.getInt("fields-max", 6);
        this.useRealPackages= config == null || config.getBoolean("use-real-packages", true);
        this.extendReal     = config != null && config.getBoolean("extend-real", false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Collect real packages and eligible superclasses from existing classes
        List<String> realPackages   = collectPackages(base);
        List<String> eligibleSupers = extendReal ? collectEligibleSupers(base) : List.of();

        List<JClassNode> fakeClasses = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            // ── pick a package ────────────────────────────────────────────────
            String pkg;
            if (useRealPackages && !realPackages.isEmpty()) {
                pkg = realPackages.get(rng.nextInt(realPackages.size()));
            } else {
                pkg = "com/generated/" + Dictionary.gen(4, Purpose.GENERIC);
            }

            // ── pick a name ───────────────────────────────────────────────────
            String simpleName = Dictionary.gen(rng.nextInt(4) + 4, Purpose.CLASS)
                    + SUFFIXES[rng.nextInt(SUFFIXES.length)];
            String internalName = pkg.isEmpty() ? simpleName : (pkg + "/" + simpleName);

            // ── pick superclass ───────────────────────────────────────────────
            String superName = "java/lang/Object";
            if (extendReal && !eligibleSupers.isEmpty() && rng.nextBoolean()) {
                superName = eligibleSupers.get(rng.nextInt(eligibleSupers.size()));
            }

            // ── build the class ───────────────────────────────────────────────
            JClassNode fake = buildFakeClass(internalName, superName, rng);
            fakeClasses.add(fake);
        }

        base.getClasses().addAll(fakeClasses);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Class construction
    // ─────────────────────────────────────────────────────────────────────────

    private JClassNode buildFakeClass(String name, String superName, ThreadLocalRandom rng) {

        JClassNode cls = ClassBuilder.create()
                .withName(name)
                .withSuperName(superName)
                .withAccess(ACC_PUBLIC | ACC_SYNTHETIC)
                .withVersion(V11)
                .build();

        // ── fields ────────────────────────────────────────────────────────────
        int fieldCount = rng.nextInt(fieldsMin, fieldsMax + 1);
        List<FieldEntry> fields = new ArrayList<>(fieldCount);

        for (int f = 0; f < fieldCount; f++) {
            String fdesc    = FIELD_DESCS[rng.nextInt(FIELD_DESCS.length)];
            String fname    = Dictionary.gen(rng.nextInt(3) + 4, Purpose.FIELD);
            int    faccess  = rng.nextBoolean()
                    ? (ACC_PRIVATE | ACC_SYNTHETIC)
                    : (ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC);

            FieldNode fn = new FieldNode(faccess, fname, fdesc, null, null);
            cls.fields.add(fn);
            fields.add(new FieldEntry(fname, fdesc));
        }

        // Static constant field with a fake string value
        String constField = Dictionary.gen(6, Purpose.FIELD).toUpperCase();
        String constValue = FAKE_STRINGS[rng.nextInt(FAKE_STRINGS.length)];
        cls.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC,
                constField, "Ljava/lang/String;", null, constValue));

        // ── <clinit> ──────────────────────────────────────────────────────────
        cls.methods.add(buildClinit(name, constField, constValue));

        // ── <init> (constructor) ──────────────────────────────────────────────
        cls.methods.add(buildConstructor(name, superName, fields));

        // ── toString() ────────────────────────────────────────────────────────
        cls.methods.add(buildToString(name, fields));

        // ── instance methods ──────────────────────────────────────────────────
        int methodCount = rng.nextInt(methodsMin, methodsMax + 1);
        for (int m = 0; m < methodCount; m++) {
            cls.methods.add(buildFakeMethod(name, fields, rng));
        }

        // ── static utility method ─────────────────────────────────────────────
        cls.methods.add(buildStaticUtil(name, rng));

        return cls;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <clinit>  — initialises the static constant field
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildClinit(String owner, String fieldName, String value) {
        MethodNode m = MethodBuilder.create()
                .withName("<clinit>")
                .withDesc("()V")
                .withAccess(ACC_STATIC)
                .withInstructions(InsnListBuilder.builder()
                        .ldc(value)
                        .putstatic(owner, fieldName, "Ljava/lang/String;")
                        ._return()
                        .build())
                .build();
        m.maxLocals = 0;
        m.maxStack  = 1;
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <init>  — initialises all instance fields
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildConstructor(String owner, String superName, List<FieldEntry> fields) {
        InsnListBuilder b = InsnListBuilder.builder();

        // super()
        b.aload(0)
         .invokespecial(superName, "<init>", "()V");

        // Initialise each field with a zero/null/default value
        for (FieldEntry fe : fields) {
            b.aload(0);
            pushDefault(b, fe.desc);
            b.putfield(owner, fe.name, fe.desc);
        }

        b._return();

        MethodNode m = MethodBuilder.create()
                .withName("<init>")
                .withDesc("()V")
                .withAccess(ACC_PUBLIC)
                .withInstructions(b.build())
                .build();
        m.maxLocals = 1;
        m.maxStack  = 3;
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // toString()
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildToString(String owner, List<FieldEntry> fields) {
        InsnListBuilder b = InsnListBuilder.builder();

        // new StringBuilder(owner.getSimpleName() + "[")
        b._new("java/lang/StringBuilder")
         .dup()
         .ldc(simpleNameOf(owner) + "[")
         .invokespecial("java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V");

        // Append first String field if available, else append a literal
        boolean appended = false;
        for (FieldEntry fe : fields) {
            if (fe.desc.equals("Ljava/lang/String;") && !appended) {
                b.aload(0)
                 .getfield(owner, fe.name, fe.desc)
                 .invokevirtual("java/lang/StringBuilder", "append",
                         "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                appended = true;
                break;
            }
        }
        if (!appended) {
            b.ldc("?")
             .invokevirtual("java/lang/StringBuilder", "append",
                     "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
        }

        b.ldc("]")
         .invokevirtual("java/lang/StringBuilder", "append",
                 "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
         .invokevirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
         .areturn();

        MethodNode m = MethodBuilder.create()
                .withName("toString")
                .withDesc("()Ljava/lang/String;")
                .withAccess(ACC_PUBLIC)
                .withInstructions(b.build())
                .build();
        m.maxLocals = 1;
        m.maxStack  = 4;
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fake instance method  — picks one of several body patterns
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildFakeMethod(String owner, List<FieldEntry> fields,
                                       ThreadLocalRandom rng) {

        String mname = Dictionary.gen(rng.nextInt(4) + 4, Purpose.METHOD);

        // Pick a body style at random
        int style = rng.nextInt(5);
        InsnListBuilder b = InsnListBuilder.builder();
        int maxStack  = 4;
        int maxLocals = 3;

        switch (style) {

            // ── Style 0: arithmetic accumulation loop ─────────────────────────
            case 0 -> {
                /*
                 * int acc = 0;
                 * for (int i = 0; i < 16; i++) acc = acc * 31 + i;
                 * return acc;
                 */
                LabelNode loopStart = new LabelNode();
                LabelNode loopEnd   = new LabelNode();

                b.iconst_0().istore(1)          // acc = 0
                 .iconst_0().istore(2)          // i = 0
                 .label(loopStart)
                 .iload(2).constant(16).if_icmpge(loopEnd)  // i < 16
                 .iload(1).constant(31).imul()
                 .iload(2).iadd().istore(1)     // acc = acc*31 + i
                 .iinc(2, 1)                    // i++
                 ._goto(loopStart)
                 .label(loopEnd)
                 .iload(1).ireturn();

                maxStack  = 3;
                maxLocals = 3;
                return finishMethod(mname, "()I", ACC_PUBLIC, b, maxStack, maxLocals);
            }

            // ── Style 1: string builder chain ────────────────────────────────
            case 1 -> {
                /*
                 * String result = new StringBuilder()
                 *     .append("key=")
                 *     .append(hashCode())
                 *     .append(";ts=")
                 *     .append(System.currentTimeMillis())
                 *     .toString();
                 * return result;
                 */
                b._new("java/lang/StringBuilder")
                 .dup()
                 .invokespecial("java/lang/StringBuilder", "<init>", "()V")
                 .ldc("key=")
                 .invokevirtual("java/lang/StringBuilder", "append",
                         "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                 .aload(0)
                 .invokevirtual("java/lang/Object", "hashCode", "()I")
                 .invokevirtual("java/lang/StringBuilder", "append",
                         "(I)Ljava/lang/StringBuilder;")
                 .ldc(";ts=")
                 .invokevirtual("java/lang/StringBuilder", "append",
                         "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                 .invokestatic("java/lang/System", "currentTimeMillis", "()J")
                 .invokevirtual("java/lang/StringBuilder", "append",
                         "(J)Ljava/lang/StringBuilder;")
                 .invokevirtual("java/lang/StringBuilder", "toString",
                         "()Ljava/lang/String;")
                 .areturn();

                maxStack  = 3;
                maxLocals = 1;
                return finishMethod(mname, "()Ljava/lang/String;", ACC_PUBLIC, b, maxStack, maxLocals);
            }

            // ── Style 2: field read + conditional branch ──────────────────────
            case 2 -> {
                /*
                 * Reads a boolean/int field (or returns false if no field).
                 * if (field != 0) return true; else return false;
                 */
                LabelNode isFalse = new LabelNode();

                // Find an int or boolean field to read
                FieldEntry target = null;
                for (FieldEntry fe : fields) {
                    if (fe.desc.equals("I") || fe.desc.equals("Z")) {
                        target = fe;
                        break;
                    }
                }

                if (target != null) {
                    b.aload(0)
                     .getfield(owner, target.name, target.desc)
                     .ifeq(isFalse)
                     .iconst_1()
                     .ireturn()
                     .label(isFalse)
                     .iconst_0()
                     .ireturn();
                } else {
                    b.iconst_0().ireturn();
                }

                maxStack  = 2;
                maxLocals = 1;
                return finishMethod(mname, "()Z", ACC_PUBLIC, b, maxStack, maxLocals);
            }

            // ── Style 3: array creation + fill ────────────────────────────────
            case 3 -> {
                /*
                 * int[] buf = new int[8];
                 * for (int i = 0; i < 8; i++) buf[i] = i * i;
                 * return buf;
                 */
                LabelNode loopTop = new LabelNode();
                LabelNode loopEnd = new LabelNode();

                b.constant(8)
                 .newarray(T_INT)
                 .astore(1)         // buf = new int[8]
                 .iconst_0()
                 .istore(2)         // i = 0
                 .label(loopTop)
                 .iload(2).constant(8).if_icmpge(loopEnd)
                 .aload(1)
                 .iload(2)
                 .iload(2).iload(2).imul()
                 .iastore()          // buf[i] = i*i
                 .iinc(2, 1)
                 ._goto(loopTop)
                 .label(loopEnd)
                 .aload(1)
                 .areturn();

                maxStack  = 4;
                maxLocals = 3;
                return finishMethod(mname, "()[I", ACC_PUBLIC, b, maxStack, maxLocals);
            }

            // ── Style 4: try/catch with runtime computation ───────────────────
            default -> {
                /*
                 * try {
                 *   long t = System.nanoTime();
                 *   return (int)(t ^ (t >>> 32));
                 * } catch (Exception e) {
                 *   return -1;
                 * }
                 */
                LabelNode tryStart = new LabelNode();
                LabelNode tryEnd   = new LabelNode();
                LabelNode catchLbl = new LabelNode();
                LabelNode endLbl   = new LabelNode();

                b.label(tryStart)
                 .invokestatic("java/lang/System", "nanoTime", "()J")
                 .lstore(1)          // long t = System.nanoTime()
                 .lload(1)
                 .lload(1)
                 .constant(32L)
                 .lushr()
                 .lxor()
                 .l2i()
                 .ireturn()
                 .label(tryEnd)
                 .label(catchLbl)
                 .pop()              // discard exception
                 .iconst_m1()
                 .ireturn()
                 .label(endLbl);

                MethodNode m = MethodBuilder.create()
                        .withName(mname)
                        .withDesc("()I")
                        .withAccess(ACC_PUBLIC | ACC_SYNTHETIC)
                        .withInstructions(b.build())
                        .build();
                m.maxStack  = 4;
                m.maxLocals = 3;
                m.tryCatchBlocks = new ArrayList<>();
                m.tryCatchBlocks.add(new TryCatchBlockNode(
                        tryStart, tryEnd, catchLbl, "java/lang/Exception"));
                return m;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static utility method  — a pure computation; looks like a helper
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildStaticUtil(String owner, ThreadLocalRandom rng) {
        String mname = Dictionary.gen(rng.nextInt(4) + 4, Purpose.METHOD);

        /*
         * Generates:
         *   public static int checksum(String s) {
         *     if (s == null) return 0;
         *     int h = 0;
         *     for (int i = 0; i < s.length(); i++)
         *         h = h * 31 + s.charAt(i);
         *     return h;
         *   }
         */
        InsnListBuilder b       = InsnListBuilder.builder();
        LabelNode nullCase      = new LabelNode();
        LabelNode loopStart     = new LabelNode();
        LabelNode loopEnd       = new LabelNode();

        b.aload(0)
         .ifnull(nullCase)      // if (s == null) return 0
         .iconst_0().istore(1)  // h = 0
         .iconst_0().istore(2)  // i = 0
         .label(loopStart)
         .iload(2)
         .aload(0)
         .invokevirtual("java/lang/String", "length", "()I")
         .if_icmpge(loopEnd)    // i < s.length()
         .iload(1).constant(31).imul()
         .aload(0).iload(2)
         .invokevirtual("java/lang/String", "charAt", "(I)C")
         .iadd().istore(1)      // h = h*31 + s.charAt(i)
         .iinc(2, 1)
         ._goto(loopStart)
         .label(loopEnd)
         .iload(1).ireturn()
         .label(nullCase)
         .iconst_0().ireturn();

        MethodNode m = MethodBuilder.create()
                .withName(mname)
                .withDesc("(Ljava/lang/String;)I")
                .withAccess(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();
        m.maxStack  = 4;
        m.maxLocals = 3;
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Finish building a simple method with the given return type and descriptor. */
    private MethodNode finishMethod(String name, String desc, int access,
                                    InsnListBuilder b, int maxStack, int maxLocals) {
        MethodNode m = MethodBuilder.create()
                .withName(name)
                .withDesc(desc)
                .withAccess(access | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();
        m.maxStack  = maxStack;
        m.maxLocals = maxLocals;
        return m;
    }

    /**
     * Push the JVM default/zero value for a given field descriptor.
     * Used when constructing and initialising fields.
     */
    private void pushDefault(InsnListBuilder b, String desc) {
        switch (desc) {
            case "I", "Z", "B", "C", "S" -> b.iconst_0();
            case "J"                      -> b.lconst_0();
            case "F"                      -> b.fconst_0();
            case "D"                      -> b.dconst_0();
            default                       -> b.aconst_null();
        }
    }

    /** Collect unique package paths from all real classes in the context. */
    private List<String> collectPackages(ObfuscatorContext base) {
        List<String> pkgs = new ArrayList<>();
        for (JClassNode cn : base.getClasses()) {
            String pkg = cn.getPackage();
            if (!pkg.isEmpty() && !pkgs.contains(pkg)) {
                // strip trailing slash
                pkgs.add(pkg.endsWith("/") ? pkg.substring(0, pkg.length() - 1) : pkg);
            }
        }
        return pkgs;
    }

    /**
     * Collect non-final, non-interface, non-enum real classes that could
     * plausibly be extended by a fake subclass.
     */
    private List<String> collectEligibleSupers(ObfuscatorContext base) {
        List<String> supers = new ArrayList<>();
        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())     continue;
            if (cn.isInterface())  continue;
            if (cn.isEnum())       continue;
            if (cn.isAnnotation()) continue;
            if (cn.isFinal())      continue;
            supers.add(cn.name);
        }
        return supers;
    }

    /** Extract the simple class name (after last '/') from an internal name. */
    private String simpleNameOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash >= 0 ? internalName.substring(slash + 1) : internalName;
    }

    private static InsnList makeLconst0() {
        InsnList il = new InsnList();
        il.add(new InsnNode(LCONST_0));
        return il;
    }

    private static InsnList makeFconst0() {
        InsnList il = new InsnList();
        il.add(new InsnNode(FCONST_0));
        return il;
    }

    private static InsnList makeDconst0() {
        InsnList il = new InsnList();
        il.add(new InsnNode(DCONST_0));
        return il;
    }
}
