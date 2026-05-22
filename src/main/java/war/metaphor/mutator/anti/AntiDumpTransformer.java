package war.metaphor.mutator.anti;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.builder.ClassBuilder;
import war.metaphor.util.builder.InsnListBuilder;
import war.metaphor.util.builder.MethodBuilder;

import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Anti-Dump Transformer
 *
 * Injects a synthetic guard class that detects at runtime whether the JVM is
 * being instrumented or monitored by heap-dumping / memory-analysis tools.
 *
 * Detection signals:
 *
 *   attach-listener  — Scans live thread names for "Attach Listener".
 *                      This thread is spawned whenever jmap / jcmd / VisualVM
 *                      connects to the JVM via the Attach API.
 *
 *   dump-args        — Scans JVM input arguments for flags associated with
 *                      heap dumping:
 *                        -XX:+HeapDumpOnOutOfMemoryError
 *                        -XX:HeapDumpPath
 *                        -agentlib:hprof
 *
 *   dump-agents      — Checks sun.java.command for known profiler / dump agent
 *                      JAR names:
 *                        jmx, jvisualvm, jmc, jprofiler, yourkit,
 *                        hprof, javaagent, heapdump
 *
 *   dump-props       — Checks for system properties set by known dump tools:
 *                        com.yourkit, com.jprofiler, org.jvmmonitor,
 *                        netbeans.profiler, sun.jvmstat
 *
 * Reaction (configured via `reaction`):
 *   exit  — System.exit(-1)   (default)
 *   throw — throw new RuntimeException()
 *
 * Registration in Metaphor.java:
 *   .mutator("anti-dump", AntiDumpMutator.class)
 *
 * config.yml:
 *   anti-dump:
 *     enabled: true
 *     reaction: exit
 *     check-attach: true
 *     check-args: true
 *     check-agents: true
 *     check-props: true
 *     injection-chance: 20
 */
@Stability(Level.HIGH)
public class AntiDumpTransformer extends Mutator {

    private static final String GUARD_CLASS = "dev/ark/guard/AntiDump";
    private static final String FLAG_FIELD  = "v";
    private static final int    VERSION     = V11;

    private final String  reaction;
    private final boolean checkAttach;
    private final boolean checkArgs;
    private final boolean checkAgents;
    private final boolean checkProps;
    private final int     injectionChance;

    /** JVM argument substrings that indicate heap-dump configuration. */
    private static final List<String> DUMP_ARGS = List.of(
        "heapdump", "hprof", "HeapDumpOnOutOfMemoryError", "HeapDumpPath"
    );

    /** Substrings of known dump / profiler agent JAR names. */
    private static final List<String> DUMP_AGENT_NAMES = List.of(
        "jprofiler", "yourkit", "jvisualvm", "jmc", "hprof",
        "heapdump", "javamelody", "jvmmonitor", "async-profiler"
    );

    /** System property prefixes set by known profiling / dump tools. */
    private static final List<String> DUMP_PROPS = List.of(
        "com.yourkit",
        "com.jprofiler",
        "org.jvmmonitor",
        "netbeans.profiler",
        "sun.jvmstat.monitor"
    );

    /** Thread name substrings that indicate Attach API usage. */
    private static final List<String> DUMP_THREADS = List.of(
        "Attach Listener",
        "Signal Dispatcher"       // appears alongside Attach Listener in jmap sessions
    );

    public AntiDumpTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.reaction        = config == null ? "exit"  : config.getString("reaction", "exit");
        this.checkAttach     = config == null || config.getBoolean("check-attach", true);
        this.checkArgs       = config == null || config.getBoolean("check-args", true);
        this.checkAgents     = config == null || config.getBoolean("check-agents", true);
        this.checkProps      = config == null || config.getBoolean("check-props", true);
        this.injectionChance = config == null ? 20 : config.getInt("injection-chance", 20);
    }

    @Override
    public void run(ObfuscatorContext base) {
        base.getClasses().add(buildGuardClass());

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt()) continue;
            if (cn.name.equals(GUARD_CLASS)) continue;

            for (MethodNode mn : cn.methods) {
                if (cn.isExempt(mn)) continue;
                if (Modifier.isAbstract(mn.access)) continue;
                if (mn.name.equals("<clinit>")) continue;
                if (mn.instructions.size() == 0) continue;
                if (rand.nextInt(100) >= injectionChance) continue;

                mn.instructions.insert(
                    InsnListBuilder.builder()
                        .invokestatic(GUARD_CLASS, "check", "()V")
                        .build()
                );
            }
        }
    }

    // ── Guard class ───────────────────────────────────────────────────────────

    private JClassNode buildGuardClass() {
        JClassNode cls = ClassBuilder.create()
                .withName(GUARD_CLASS)
                .withSuperName("java/lang/Object")
                .withAccess(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC)
                .build();
        cls.version = VERSION;

        cls.fields.add(new FieldNode(
            ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE | ACC_SYNTHETIC,
            FLAG_FIELD, "Z", null, null));

        MethodNode clinit = MethodBuilder.create()
                .withName("<clinit>").withDesc("()V").withAccess(ACC_STATIC)
                .withInstructions(InsnListBuilder.builder()
                    .iconst_0().putstatic(GUARD_CLASS, FLAG_FIELD, "Z")
                    ._return().build())
                .build();
        clinit.maxLocals = 0; clinit.maxStack = 1;
        cls.methods.add(clinit);

        cls.methods.add(buildCheckMethod());
        cls.methods.add(buildReactMethod());
        return cls;
    }

    // ── check() ───────────────────────────────────────────────────────────────

    private MethodNode buildCheckMethod() {
        InsnListBuilder b   = InsnListBuilder.builder();
        LabelNode        end = new LabelNode();

        b.getstatic(GUARD_CLASS, FLAG_FIELD, "Z").ifne(end)
         .iconst_1().putstatic(GUARD_CLASS, FLAG_FIELD, "Z");

        if (checkAttach) b.list(buildAttachListenerCheck());
        if (checkArgs)   b.list(buildDumpArgCheck());
        if (checkAgents) b.list(buildAgentNameCheck());
        if (checkProps)  b.list(buildPropCheck());

        b.label(end)._return();

        MethodNode m = MethodBuilder.create()
                .withName("check").withDesc("()V")
                .withAccess(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build()).build();
        m.maxLocals = 4; m.maxStack = 4;
        return m;
    }

    private InsnList buildAttachListenerCheck() {
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd   = new LabelNode();

        InsnListBuilder b = InsnListBuilder.builder()
            .invokestatic("java/lang/Thread", "getAllStackTraces", "()Ljava/util/Map;")
            .invokeinterface("java/util/Map", "keySet", "()Ljava/util/Set;")
            .invokeinterface("java/util/Set", "iterator", "()Ljava/util/Iterator;")
            .astore(0)
            .label(loopStart)
            .aload(0)
            .invokeinterface("java/util/Iterator", "hasNext", "()Z")
            .ifeq(loopEnd)
            .aload(0)
            .invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
            .checkcast("java/lang/Thread")
            .invokevirtual("java/lang/Thread", "getName", "()Ljava/lang/String;")
            .astore(1);

        for (String threadName : DUMP_THREADS) {
            LabelNode skip = new LabelNode();
            b.aload(1).constant(threadName)
             .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
             .ifeq(skip)
             .invokestatic(GUARD_CLASS, "react", "()V")
             .label(skip);
        }

        b._goto(loopStart).label(loopEnd);
        return b.build();
    }

    private InsnList buildDumpArgCheck() {
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd   = new LabelNode();

        InsnListBuilder b = InsnListBuilder.builder()
            .invokestatic("java/lang/management/ManagementFactory", "getRuntimeMXBean",
                          "()Ljava/lang/management/RuntimeMXBean;")
            .invokeinterface("java/lang/management/RuntimeMXBean", "getInputArguments",
                             "()Ljava/util/List;")
            .invokeinterface("java/util/List", "iterator", "()Ljava/util/Iterator;")
            .astore(0)
            .label(loopStart)
            .aload(0)
            .invokeinterface("java/util/Iterator", "hasNext", "()Z")
            .ifeq(loopEnd)
            .aload(0)
            .invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
            .checkcast("java/lang/String")
            .invokevirtual("java/lang/String", "toLowerCase", "()Ljava/lang/String;")
            .astore(1);

        for (String arg : DUMP_ARGS) {
            LabelNode skip = new LabelNode();
            b.aload(1).constant(arg.toLowerCase())
             .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
             .ifeq(skip)
             .invokestatic(GUARD_CLASS, "react", "()V")
             .label(skip);
        }

        b._goto(loopStart).label(loopEnd);
        return b.build();
    }

    private InsnList buildAgentNameCheck() {
        InsnListBuilder b = InsnListBuilder.builder()
            .constant("sun.java.command").constant("")
            .invokestatic("java/lang/System", "getProperty",
                          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
            .invokevirtual("java/lang/String", "toLowerCase", "()Ljava/lang/String;")
            .astore(0);

        for (String name : DUMP_AGENT_NAMES) {
            LabelNode skip = new LabelNode();
            b.aload(0).constant(name)
             .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
             .ifeq(skip)
             .invokestatic(GUARD_CLASS, "react", "()V")
             .label(skip);
        }
        return b.build();
    }

    private InsnList buildPropCheck() {
        InsnListBuilder b = InsnListBuilder.builder();
        for (String prop : DUMP_PROPS) {
            LabelNode skip = new LabelNode();
            b.constant(prop)
             .invokestatic("java/lang/System", "getProperty",
                           "(Ljava/lang/String;)Ljava/lang/String;")
             .ifnull(skip)
             .invokestatic(GUARD_CLASS, "react", "()V")
             .label(skip);
        }
        return b.build();
    }

    // ── react() ───────────────────────────────────────────────────────────────

    private MethodNode buildReactMethod() {
        InsnListBuilder b = InsnListBuilder.builder();
        if ("throw".equals(reaction)) {
            b._new("java/lang/RuntimeException").dup().constant("")
             .invokespecial("java/lang/RuntimeException", "<init>",
                            "(Ljava/lang/String;)V").athrow();
        } else {
            b.constant(-1).invokestatic("java/lang/System", "exit", "(I)V");
        }
        b._return();

        MethodNode m = MethodBuilder.create()
                .withName("react").withDesc("()V")
                .withAccess(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build()).build();
        m.maxLocals = 0; m.maxStack = 3;
        return m;
    }
}
