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
import java.util.zip.CRC32;

/**
 * Anti-Tamper Mutator
 *
 * Protects the output JAR against post-obfuscation modification by injecting
 * a synthetic guard class that re-hashes every class file at runtime and
 * compares it to a table of CRC32 checksums baked in at obfuscation time.
 *
 * Strategy:
 *   1. At obfuscation time, compute a CRC32 checksum of the raw bytecode for
 *      every non-exempt class in the JAR.
 *   2. Build a synthetic guard class (dev/ark/guard/AntiTamper) whose
 *      <clinit> stores all those checksums in a static int[] table.
 *   3. The guard's verify() method:
 *        a. Opens the running JAR via getProtectionDomain / CodeSource.
 *        b. Iterates every .class entry in the JAR using a ZipInputStream.
 *        c. Recomputes CRC32 of the raw bytes.
 *        d. Looks the expected checksum up in the baked-in table.
 *        e. If anything mismatches → react().
 *   4. verify() is inserted at the top of a random sample of eligible methods
 *      (controlled by injection-chance).
 *   5. A `verified` flag ensures the check only runs once per JVM lifetime.
 *
 * Reactions (configured via `reaction`):
 *   exit  — System.exit(-1)          (default)
 *   throw — throw new RuntimeException()
 *
 * Registration in Metaphor.java:
 *   .mutator("anti-tamper", AntiTamperMutator.class)
 *
 * config.yml:
 *   anti-tamper:
 *     enabled: true
 *     reaction: exit          # exit | throw
 *     injection-chance: 20    # % of eligible methods to inject the guard call
 *
 * Placement in order:
 *   Put anti-tamper AFTER all renaming / obfuscation mutators so the checksums
 *   capture the final transformed bytecode, not the input bytecode.
 *   Put it BEFORE strip / inlining so the guard class itself is not inlined away.
 *
 *   Recommended order tail:
 *     ... string / flow / number mutators ...
 *     - anti-tamper   ← here
 *     - strip
 *     - watermark
 */
@Stability(Level.HIGH)
public class AntiTamperTransformer extends Mutator {

    // ── constants ────────────────────────────────────────────────────────────

    /** Internal name of the generated guard class. */
    private static final String GUARD_CLASS   = "dev/ark/guard/AntiTamper";

    /** Name of the static CRC32 table field inside the guard class. */
    private static final String TABLE_FIELD   = "t";

    /** Name of the "already verified" flag field. */
    private static final String FLAG_FIELD    = "v";

    /** Class-file version to emit for the guard class (Java 11). */
    private static final int    CLASS_VERSION = V11;

    // ── config ───────────────────────────────────────────────────────────────

    private final String  reaction;
    private final int     injectionChance;

    // ─────────────────────────────────────────────────────────────────────────

    public AntiTamperTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.reaction        = config == null ? "exit" : config.getString("reaction", "exit");
        this.injectionChance = config == null ? 20     : config.getInt("injection-chance", 20);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {

        // ── Step 1: compute CRC32 checksums of all class bytecodes ────────────
        //
        // We iterate the live in-memory class nodes, serialise each to bytes
        // via ClassWriter, and hash them.  This means the checksum covers
        // whatever transformations have already run — so anti-tamper should
        // be ordered AFTER all obfuscation mutators.

        // Collect (internalName → crc32) pairs for non-exempt, non-guard classes.
        java.util.List<long[]> entries = new java.util.ArrayList<>();   // [0]=nameHash [1]=crc

        CRC32 crc32 = new CRC32();

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())                    continue;
            if (cn.name.equals(GUARD_CLASS))      continue;

            // Serialise the class node back to raw bytes
            byte[] bytes = toBytes(cn);
            if (bytes == null) continue;

            crc32.reset();
            crc32.update(bytes);
            long crc = crc32.getValue();

            // Key: hashCode of the internal class name (e.g. "me/exeos/Main")
            long nameHash = (long) cn.name.hashCode();

            entries.add(new long[]{ nameHash, crc });
        }

        // ── Step 2: build and inject the guard class ──────────────────────────

        base.getClasses().add(buildGuardClass(entries));

        // ── Step 3: inject verify() call into selected methods ────────────────

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())                  continue;
            if (cn.name.equals(GUARD_CLASS))    continue;

            for (MethodNode mn : cn.methods) {
                if (cn.isExempt(mn))                        continue;
                if (Modifier.isAbstract(mn.access))         continue;
                if (mn.name.equals("<clinit>"))             continue;
                if (mn.instructions.size() == 0)            continue;
                if (rand.nextInt(100) >= injectionChance)   continue;

                mn.instructions.insert(
                    InsnListBuilder.builder()
                        .invokestatic(GUARD_CLASS, "verify", "()V")
                        .build()
                );
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard class construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the synthetic AntiTamper guard class.
     *
     * Generated structure (pseudo-Java):
     *
     *   final class AntiTamper {
     *
     *     // Parallel arrays: nameHashes[i] paired with crcs[i]
     *     private static final long[] nameHashes;   // field "t"  (names)
     *     private static final long[] crcs;         // field "c"  (checksums)
     *     private static volatile boolean verified; // field "v"
     *
     *     static {
     *         nameHashes = new long[]{ ... };
     *         crcs       = new long[]{ ... };
     *         verified   = false;
     *     }
     *
     *     public static void verify() { ... }
     *     private static void react() { System.exit(-1); }
     *   }
     */
    private JClassNode buildGuardClass(java.util.List<long[]> entries) {

        JClassNode cls = ClassBuilder.create()
                .withName(GUARD_CLASS)
                .withSuperName("java/lang/Object")
                .withAccess(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC)
                .build();
        cls.version = CLASS_VERSION;

        // ── fields ────────────────────────────────────────────────────────────

        // long[] t  — name hashes
        FieldNode nameHashField = new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC,
                "t", "[J", null, null);
        cls.fields.add(nameHashField);

        // long[] c  — crc32 values
        FieldNode crcField = new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC,
                "c", "[J", null, null);
        cls.fields.add(crcField);

        // volatile boolean v  — already-verified flag
        FieldNode verifiedField = new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE | ACC_SYNTHETIC,
                FLAG_FIELD, "Z", null, null);
        cls.fields.add(verifiedField);

        // ── <clinit> ──────────────────────────────────────────────────────────

        cls.methods.add(buildClinit(entries));

        // ── verify() ─────────────────────────────────────────────────────────

        cls.methods.add(buildVerifyMethod(entries.size()));

        // ── react() ──────────────────────────────────────────────────────────

        cls.methods.add(buildReactMethod());

        return cls;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <clinit>  — populates the parallel long[] tables
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Equivalent Java:
     *
     *   static {
     *     verified   = false;
     *     long[] nh  = new long[N];
     *     long[] cr  = new long[N];
     *     nh[0] = <nameHash0>;  cr[0] = <crc0>;
     *     nh[1] = <nameHash1>;  cr[1] = <crc1>;
     *     ...
     *     t = nh;
     *     c = cr;
     *   }
     */
    private MethodNode buildClinit(java.util.List<long[]> entries) {

        int n = entries.size();

        InsnListBuilder b = InsnListBuilder.builder();

        // verified = false
        b.iconst_0()
         .putstatic(GUARD_CLASS, FLAG_FIELD, "Z");

        // nameHash array
        b.ldc((long) n)
         .invokestatic("java/lang/reflect/Array", "newInstance",
                       "(Ljava/lang/Class;I)Ljava/lang/Object;");
        // simpler: just use NEWARRAY T_LONG
        // Let's emit it directly
        b.list(makeNewLongArray(n));
        b.astore(0);  // local 0 = nameHash array

        // crc array
        b.list(makeNewLongArray(n));
        b.astore(1);  // local 1 = crc array

        for (int i = 0; i < n; i++) {
            long nameHash = entries.get(i)[0];
            long crc      = entries.get(i)[1];

            // nameHash array store
            b.aload(0)
             .constant(i)
             .constant(nameHash)
             .list(makeLastore());

            // crc array store
            b.aload(1)
             .constant(i)
             .constant(crc)
             .list(makeLastore());
        }

        // t = nameHash array;  c = crc array
        b.aload(0).putstatic(GUARD_CLASS, "t", "[J");
        b.aload(1).putstatic(GUARD_CLASS, "c", "[J");

        b._return();

        MethodNode clinit = MethodBuilder.create()
                .withName("<clinit>")
                .withDesc("()V")
                .withAccess(ACC_STATIC)
                .withInstructions(b.build())
                .build();
        clinit.maxLocals = 2;
        clinit.maxStack  = 5;
        return clinit;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // verify()
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Equivalent Java:
     *
     *   public static void verify() {
     *     if (verified) return;
     *     verified = true;
     *
     *     try {
     *       // Locate the running JAR
     *       URL jarUrl = AntiTamper.class
     *                       .getProtectionDomain()
     *                       .getCodeSource()
     *                       .getLocation();
     *       ZipInputStream zis = new ZipInputStream(jarUrl.openStream());
     *
     *       CRC32 crc32 = new CRC32();
     *       ZipEntry entry;
     *
     *       while ((entry = zis.getNextEntry()) != null) {
     *         String name = entry.getName();
     *         if (!name.endsWith(".class")) continue;
     *
     *         // Strip ".class" suffix and convert path to internal name
     *         String internalName = name.substring(0, name.length() - 6);
     *         int nameHash = internalName.hashCode();
     *
     *         // Read raw bytes of this entry
     *         byte[] buf = zis.readAllBytes();
     *
     *         // Find expected CRC in our table
     *         long expected = -1L;
     *         for (int i = 0; i < t.length; i++) {
     *           if (t[i] == (long) nameHash) { expected = c[i]; break; }
     *         }
     *         if (expected == -1L) continue;  // not a tracked class, skip
     *
     *         // Verify
     *         crc32.reset();
     *         crc32.update(buf);
     *         if (crc32.getValue() != expected) react();
     *       }
     *       zis.close();
     *     } catch (Exception e) {
     *       // If we can't read the JAR, fail safe by reacting
     *       react();
     *     }
     *   }
     *
     * Local variable layout:
     *   0 = ZipInputStream zis
     *   1 = CRC32 crc32
     *   2 = ZipEntry entry
     *   3 = String name
     *   4 = String internalName
     *   5 = int nameHash
     *   6 = byte[] buf
     *   7 = long expected (occupies 7+8)
     *   9 = int i (loop counter)
     */
    private MethodNode buildVerifyMethod(int tableSize) {

        InsnListBuilder b  = InsnListBuilder.builder();
        LabelNode earlyRet = new LabelNode();
        LabelNode loopTop  = new LabelNode();
        LabelNode loopEnd  = new LabelNode();
        LabelNode notClass = new LabelNode();
        LabelNode tableLoop= new LabelNode();
        LabelNode tableEnd = new LabelNode();
        LabelNode tableHit = new LabelNode();
        LabelNode skipEntry= new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd   = new LabelNode();
        LabelNode catchBlock = new LabelNode();

        // if (verified) return;
        b.getstatic(GUARD_CLASS, FLAG_FIELD, "Z")
         .ifne(earlyRet)
         .iconst_1()
         .putstatic(GUARD_CLASS, FLAG_FIELD, "Z");

        // ── try block start ───────────────────────────────────────────────────
        b.label(tryStart);

        // ZipInputStream zis = new ZipInputStream(
        //     AntiTamper.class.getProtectionDomain().getCodeSource().getLocation().openStream())
        b._new("java/util/zip/ZipInputStream")
         .dup()
         .ldc(org.objectweb.asm.Type.getType("L" + GUARD_CLASS + ";"))
         .invokevirtual("java/lang/Class", "getProtectionDomain",
                        "()Ljava/security/ProtectionDomain;")
         .invokevirtual("java/security/ProtectionDomain", "getCodeSource",
                        "()Ljava/security/CodeSource;")
         .invokevirtual("java/security/CodeSource", "getLocation",
                        "()Ljava/net/URL;")
         .invokevirtual("java/net/URL", "openStream",
                        "()Ljava/io/InputStream;")
         .invokespecial("java/util/zip/ZipInputStream", "<init>",
                        "(Ljava/io/InputStream;)V")
         .astore(0);  // local 0 = zis

        // CRC32 crc32 = new CRC32();
        b._new("java/util/zip/CRC32")
         .dup()
         .invokespecial("java/util/zip/CRC32", "<init>", "()V")
         .astore(1);  // local 1 = crc32

        // ── outer loop: while ((entry = zis.getNextEntry()) != null) ─────────
        b.label(loopTop)
         .aload(0)
         .invokevirtual("java/util/zip/ZipInputStream", "getNextEntry",
                        "()Ljava/util/zip/ZipEntry;")
         .astore(2);  // local 2 = entry

        b.aload(2)
         .ifnull(loopEnd);

        // String name = entry.getName();
        b.aload(2)
         .invokevirtual("java/util/zip/ZipEntry", "getName", "()Ljava/lang/String;")
         .astore(3);  // local 3 = name

        // if (!name.endsWith(".class")) continue;
        b.aload(3)
         .constant(".class")
         .invokevirtual("java/lang/String", "endsWith", "(Ljava/lang/String;)Z")
         .ifeq(notClass);

        // String internalName = name.substring(0, name.length() - 6);
        b.aload(3)
         .iconst_0()
         .aload(3)
         .invokevirtual("java/lang/String", "length", "()I")
         .constant(6)
         .list(makeIsub())
         .invokevirtual("java/lang/String", "substring", "(II)Ljava/lang/String;")
         .astore(4);  // local 4 = internalName

        // int nameHash = internalName.hashCode();
        b.aload(4)
         .invokevirtual("java/lang/String", "hashCode", "()I")
         .istore(5);  // local 5 = nameHash

        // byte[] buf = zis.readAllBytes();
        b.aload(0)
         .invokevirtual("java/io/InputStream", "readAllBytes", "()[B")
         .astore(6);  // local 6 = buf

        // long expected = -1L;
        b.constant(-1L)
         .list(makeLstore(7));  // local 7+8 = expected

        // table scan: for (int i = 0; i < t.length; i++)
        b.iconst_0()
         .istore(9);  // local 9 = i

        b.label(tableLoop)
         .iload(9)
         .getstatic(GUARD_CLASS, "t", "[J")
         .arraylength()
         .list(makeIcmpge(tableEnd));

        // if (t[i] == (long) nameHash) { expected = c[i]; break; }
        b.getstatic(GUARD_CLASS, "t", "[J")
         .iload(9)
         .list(makeLaload())
         .iload(5)
         .list(makeI2L())
         .list(makeLcmpeq(tableHit));

        // i++; goto tableLoop
        b.list(makeIinc(9, 1))
         ._goto(tableLoop);

        b.label(tableHit)
         .getstatic(GUARD_CLASS, "c", "[J")
         .iload(9)
         .list(makeLaload())
         .list(makeLstore(7));  // expected = c[i]

        b.label(tableEnd);

        // if (expected == -1L) continue; (skip untracked entries)
        b.list(makeLload(7))
         .constant(-1L)
         .list(makeLcmpeq(notClass));

        // crc32.reset()
        b.aload(1)
         .invokevirtual("java/util/zip/CRC32", "reset", "()V");

        // crc32.update(buf)
        b.aload(1)
         .aload(6)
         .invokevirtual("java/util/zip/CRC32", "update", "([B)V");

        // if (crc32.getValue() != expected) react()
        b.aload(1)
         .invokevirtual("java/util/zip/CRC32", "getValue", "()J")
         .list(makeLload(7))
         .list(makeLcmpeq(notClass));

        // mismatch → react
        b.invokestatic(GUARD_CLASS, "react", "()V");

        b.label(notClass)
         ._goto(loopTop);

        b.label(loopEnd)
         .aload(0)
         .invokevirtual("java/util/zip/ZipInputStream", "close", "()V");

        // ── try block end / catch ─────────────────────────────────────────────
        b.label(tryEnd)
         ._goto(earlyRet);

        b.label(catchBlock)
         .pop()
         .invokestatic(GUARD_CLASS, "react", "()V");

        b.label(earlyRet)
         ._return();

        MethodNode verify = MethodBuilder.create()
                .withName("verify")
                .withDesc("()V")
                .withAccess(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();

        verify.maxLocals = 12;
        verify.maxStack  = 6;

        // attach the try/catch covering the whole body
        verify.tryCatchBlocks = new java.util.ArrayList<>();
        verify.tryCatchBlocks.add(
            new TryCatchBlockNode(tryStart, tryEnd, catchBlock, "java/lang/Exception")
        );

        return verify;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // react()
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildReactMethod() {
        InsnListBuilder b = InsnListBuilder.builder();

        if ("throw".equals(reaction)) {
            b._new("java/lang/RuntimeException")
             .dup()
             .constant("")
             .invokespecial("java/lang/RuntimeException", "<init>",
                            "(Ljava/lang/String;)V")
             .athrow();
        } else {
            // Default: exit
            b.constant(-1)
             .invokestatic("java/lang/System", "exit", "(I)V");
        }
        b._return();

        MethodNode react = MethodBuilder.create()
                .withName("react")
                .withDesc("()V")
                .withAccess(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();
        react.maxLocals = 0;
        react.maxStack  = 3;
        return react;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bytecode helpers  (raw InsnList snippets for ops InsnListBuilder lacks)
    // ─────────────────────────────────────────────────────────────────────────

    /** new long[n] */
    private InsnList makeNewLongArray(int n) {
        InsnList l = new InsnList();
        l.add(new LdcInsnNode(n));
        l.add(new IntInsnNode(NEWARRAY, T_LONG));
        return l;
    }

    /** lastore */
    private InsnList makeLastore() {
        InsnList l = new InsnList();
        l.add(new InsnNode(LASTORE));
        return l;
    }

    /** laload */
    private InsnList makeLaload() {
        InsnList l = new InsnList();
        l.add(new InsnNode(LALOAD));
        return l;
    }

    /** lstore <var> */
    private InsnList makeLstore(int var) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(LSTORE, var));
        return l;
    }

    /** lload <var> */
    private InsnList makeLload(int var) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(LLOAD, var));
        return l;
    }

    /** isub */
    private InsnList makeIsub() {
        InsnList l = new InsnList();
        l.add(new InsnNode(ISUB));
        return l;
    }

    /** i2l */
    private InsnList makeI2L() {
        InsnList l = new InsnList();
        l.add(new InsnNode(I2L));
        return l;
    }

    /** iinc <var> <incr> */
    private InsnList makeIinc(int var, int incr) {
        InsnList l = new InsnList();
        l.add(new IincInsnNode(var, incr));
        return l;
    }

    /**
     * lcmp + ifeq <label>  (jump to label if two longs on stack are EQUAL)
     * Stack before: ..., long, long
     * Stack after:  ...
     */
    private InsnList makeLcmpeq(LabelNode target) {
        InsnList l = new InsnList();
        l.add(new InsnNode(LCMP));
        l.add(new JumpInsnNode(IFEQ, target));
        return l;
    }

    /**
     * if_icmpge <label>  (jump if top-of-stack int >= second int)
     */
    private InsnList makeIcmpge(LabelNode target) {
        InsnList l = new InsnList();
        l.add(new JumpInsnNode(IF_ICMPGE, target));
        return l;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialise a JClassNode back to raw bytes
    // ─────────────────────────────────────────────────────────────────────────

    private byte[] toBytes(JClassNode cn) {
        try {
            org.objectweb.asm.ClassWriter cw =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
