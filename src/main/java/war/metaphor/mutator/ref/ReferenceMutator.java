package war.metaphor.mutator.ref;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReferenceMutator — hides INVOKEVIRTUAL / INVOKESTATIC / INVOKEINTERFACE
 * calls behind invokedynamic + a per-class bootstrap method.
 *
 * This is a ground-up rewrite of the original, which called
 * CallSite.makeSite() — a package-private JDK internal method that has been
 * blocked by the module system since JVM 16.  This version uses only public
 * java.lang.invoke APIs and works on JVM 8–21+.
 *
 * Ported from ArkObf's ReferenceTransformer, adapted to JNT's mutator API.
 */
@Stability(Level.HIGH)
public class ReferenceMutator extends Mutator {

    public ReferenceMutator(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    // ── entry point ──────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {
        int total = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.version < 52) continue;                     // require Java 8+
            if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) continue;
            if (classNode.name.contains("$")) continue;               // skip inner classes

            total += processClass(classNode, base);
        }
        System.out.printf("  [ref] Hidden %d method references%n", total);
    }

    // ── per-class processing ─────────────────────────────────────────────────

    private int processClass(JClassNode classNode, ObfuscatorContext base) {
        // Per-class random state (matches ArkObf approach)
        String bsmName      = Dictionary.gen(6, Purpose.METHOD);
        String decodeName   = Dictionary.gen(6, Purpose.METHOD);
        String keyFieldName = Dictionary.gen(6, Purpose.FIELD);
        String cmpFieldName = Dictionary.gen(6, Purpose.FIELD);

        int    opcodeKey    = rand.nextInt();
        byte   encKey       = (byte) (opcodeKey & 0xFF);
        String b64Table     = shuffledB64Table();

        // Extra fake parameters (0–5) to make BSM signature harder to read
        int    extraCount   = rand.nextInt(6);
        String extraDesc    = "Ljava/lang/Object;".repeat(extraCount);

        // BSM signature: (Lookup, String, MethodType, Object, Object, Object, Object, Object...) Object
        String bsmDesc = String.format(
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/Object;Ljava/lang/Object;" +
            "Ljava/lang/Object;Ljava/lang/Object;%s)Ljava/lang/Object;",
            extraDesc);

        String decodeDesc = String.format("(Ljava/lang/String;B%s)[B", extraDesc);

        Handle bsmHandle = new Handle(
            Opcodes.H_INVOKESTATIC, classNode.name, bsmName, bsmDesc, false);

        AtomicBoolean applied = new AtomicBoolean(false);

        for (MethodNode method : classNode.methods) {
            if (classNode.isExempt(method)) continue;

            List<MethodInsnNode> targets = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode m) {
                    int op = m.getOpcode();
                    if (op == Opcodes.INVOKEVIRTUAL ||
                        op == Opcodes.INVOKESTATIC  ||
                        op == Opcodes.INVOKEINTERFACE) {
                        if (!m.owner.startsWith("[L") && !m.name.startsWith("<")) {
                            targets.add(m);
                        }
                    }
                }
            }

            for (MethodInsnNode m : targets) {
                int op = m.getOpcode();

                // Build the indy descriptor: static keeps original desc;
                // virtual/interface prepend the receiver as Object
                String newDesc = op == Opcodes.INVOKESTATIC
                    ? m.desc
                    : m.desc.replace("(", "(Ljava/lang/Object;");

                // Erase all Object-sort argument types to Object (like ArkObf does)
                Type retType  = Type.getReturnType(newDesc);
                Type[] args   = Type.getArgumentTypes(newDesc);
                for (int i = 0; i < args.length; i++) {
                    if (args[i].getSort() == Type.OBJECT) {
                        args[i] = Type.getType(Object.class);
                    }
                }
                newDesc = Type.getMethodDescriptor(retType, args);

                // BSM args: opcode(^key if extraCount>0), owner, name, desc, + fakes
                Object[] bsmArgs = new Object[4 + extraCount];
                bsmArgs[0] = extraCount != 0 ? (op ^ opcodeKey) : op;
                bsmArgs[1] = b64Encode(
                    m.owner.replace("/", ".").getBytes(), b64Table, encKey);
                bsmArgs[2] = b64Encode(m.name.getBytes(), b64Table, encKey);
                bsmArgs[3] = m.desc;
                for (int i = 0; i < extraCount; i++) {
                    bsmArgs[4 + i] = randomBsmArg(b64Table);
                }

                InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                    Dictionary.gen(6, Purpose.METHOD), newDesc, bsmHandle, bsmArgs);

                method.instructions.insert(m, indy);

                // If return type is an array we need a CHECKCAST
                if (retType.getSort() == Type.ARRAY) {
                    method.instructions.insert(indy,
                        new TypeInsnNode(Opcodes.CHECKCAST, retType.getInternalName()));
                }

                method.instructions.remove(m);
                applied.set(true);
            }
        }

        if (!applied.get()) return 0;

        // ── inject supporting fields ─────────────────────────────────────────
        classNode.fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            keyFieldName, "I", null, null));
        classNode.fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            cmpFieldName, "I", null, null));

        // ── inject key initialisation into <clinit> ──────────────────────────
        MethodNode clinit = classNode.getStaticInit();
        InsnList init = new InsnList();
        init.add(new LdcInsnNode(opcodeKey));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, keyFieldName, "I"));
        init.add(new LdcInsnNode(184)); // INVOKESTATIC opcode constant (comparison baseline)
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, cmpFieldName, "I"));
        clinit.instructions.insert(init);

        // ── inject the base64 decode helper ─────────────────────────────────
        injectDecodeMethod(classNode, decodeName, decodeDesc, b64Table, extraCount);

        // ── inject the bootstrap method ──────────────────────────────────────
        injectBootstrap(classNode, bsmName, bsmDesc, decodeDesc, decodeName,
                        keyFieldName, cmpFieldName,
                        opcodeKey, extraCount, b64Table, encKey);

        // Count replaced references
        int count = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode id && id.bsm.equals(bsmHandle)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ── bootstrap method (public-API version) ────────────────────────────────
    //
    // Generated bytecode equivalent of:
    //
    //   public static Object bsm(MethodHandles.Lookup lookup, String name,
    //                            MethodType type,
    //                            Object encodedOpcode, Object encodedOwner,
    //                            Object encodedMethod, Object originalDesc,
    //                            Object... fakes) throws Throwable {
    //       int opcode  = (int) encodedOpcode;                         // or XOR with key
    //       byte[] ownerBytes  = decode((String) encodedOwner,  encKey);
    //       byte[] methodBytes = decode((String) encodedMethod, encKey);
    //       String owner  = new String(ownerBytes);
    //       String mName  = new String(methodBytes);
    //       String desc   = (String) originalDesc;
    //       Class<?> cls  = Class.forName(owner);
    //       MethodType mt = MethodType.fromMethodDescriptorString(desc, cls.getClassLoader());
    //       MethodHandle mh;
    //       if (opcode == INVOKESTATIC) {
    //           mh = lookup.findStatic(cls, mName, mt);
    //       } else {
    //           mh = lookup.findVirtual(cls, mName, mt);
    //       }
    //       return new ConstantCallSite(mh.asType(type));
    //   }

    private void injectBootstrap(JClassNode classNode,
                                  String bsmName, String bsmDesc,
                                  String decodeDesc, String decodeName,
                                  String keyFieldName, String cmpFieldName,
                                  int opcodeKey, int extraCount,
                                  String b64Table, byte encKey) {

        ClassWriter cw = new ClassWriter(0);
        // We use a MethodVisitor directly so we control every instruction precisely
        MethodVisitor mv = classNode.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VARARGS | Opcodes.ACC_SYNTHETIC,
            bsmName, bsmDesc, null, new String[]{"java/lang/Throwable"});

        mv.visitCode();

        // Local variable layout (indices depend on extraCount for varargs):
        // 0 = Lookup, 1 = String name, 2 = MethodType, 3 = encodedOpcode,
        // 4 = encodedOwner, 5 = encodedMethod, 6 = originalDesc, 7..N = fakes
        // We add temps after the declared params:
        int vOpcode  = 7 + extraCount;  // int
        int vOwner   = 8 + extraCount;  // String (class name)
        int vMethod  = 9 + extraCount;  // String (method name)
        int vDesc    = 10 + extraCount; // String
        int vClass   = 11 + extraCount; // Class<?>
        int vType    = 12 + extraCount; // MethodType
        int vHandle  = 13 + extraCount; // MethodHandle

        // ── decode opcode ────────────────────────────────────────────────────
        mv.visitVarInsn(Opcodes.ALOAD, 3);             // encodedOpcode (Object)
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer",
            "intValue", "()I", false);
        if (extraCount != 0) {
            // XOR back with the key field
            mv.visitFieldInsn(Opcodes.GETSTATIC, classNode.name, keyFieldName, "I");
            mv.visitInsn(Opcodes.IXOR);
        }
        mv.visitVarInsn(Opcodes.ISTORE, vOpcode);

        // ── decode owner class name ──────────────────────────────────────────
        mv.visitVarInsn(Opcodes.ALOAD, 4);             // encodedOwner
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitIntInsn(Opcodes.BIPUSH, encKey & 0xFF);
        // push extra nulls if decodeDesc has extra params
        for (int i = 0; i < extraCount; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, classNode.name,
            decodeName, decodeDesc, false);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP_X1);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String",
            "<init>", "([B)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, vOwner);

        // ── decode method name ───────────────────────────────────────────────
        mv.visitVarInsn(Opcodes.ALOAD, 5);             // encodedMethod
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitIntInsn(Opcodes.BIPUSH, encKey & 0xFF);
        for (int i = 0; i < extraCount; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, classNode.name,
            decodeName, decodeDesc, false);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP_X1);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String",
            "<init>", "([B)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, vMethod);

        // ── original descriptor (plain, not encoded) ─────────────────────────
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitVarInsn(Opcodes.ASTORE, vDesc);

        // ── Class.forName(owner) ─────────────────────────────────────────────
        mv.visitVarInsn(Opcodes.ALOAD, vOwner);
        mv.visitInsn(Opcodes.ICONST_1);               // initialize = true
        mv.visitVarInsn(Opcodes.ALOAD, 0);            // Lookup
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "lookupClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
            "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
            "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        mv.visitVarInsn(Opcodes.ASTORE, vClass);

        // ── MethodType.fromMethodDescriptorString(desc, classLoader) ─────────
        mv.visitVarInsn(Opcodes.ALOAD, vDesc);
        mv.visitVarInsn(Opcodes.ALOAD, vClass);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
            "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodType",
            "fromMethodDescriptorString",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false);
        mv.visitVarInsn(Opcodes.ASTORE, vType);

        // ── opcode branch: INVOKESTATIC(184) → findStatic, else → findVirtual ─
        Label lblStatic  = new Label();
        Label lblVirtual = new Label();
        Label lblDone    = new Label();

        mv.visitVarInsn(Opcodes.ILOAD, vOpcode);
        mv.visitFieldInsn(Opcodes.GETSTATIC, classNode.name, cmpFieldName, "I"); // 184
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, lblStatic);

        // findVirtual
        // vType is built from m.desc (the original descriptor without the receiver),
        // so it is already the correct MethodType for findVirtual — no dropParameterTypes needed.
        // Stack: Lookup, Class, String, MethodType -> findVirtual -> MethodHandle
        mv.visitLabel(lblVirtual);
        mv.visitVarInsn(Opcodes.ALOAD, 0);            // Lookup
        mv.visitVarInsn(Opcodes.ALOAD, vClass);       // declaring class
        mv.visitVarInsn(Opcodes.ALOAD, vMethod);      // method name
        mv.visitVarInsn(Opcodes.ALOAD, vType);        // original MethodType (no receiver)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "findVirtual",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/MethodHandle;", false);
        mv.visitJumpInsn(Opcodes.GOTO, lblDone);

        // findStatic
        mv.visitLabel(lblStatic);
        mv.visitVarInsn(Opcodes.ALOAD, 0);            // Lookup
        mv.visitVarInsn(Opcodes.ALOAD, vClass);
        mv.visitVarInsn(Opcodes.ALOAD, vMethod);
        mv.visitVarInsn(Opcodes.ALOAD, vType);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "findStatic",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/MethodHandle;", false);

        mv.visitLabel(lblDone);
        mv.visitVarInsn(Opcodes.ASTORE, vHandle);

        // ── return new ConstantCallSite(handle.asType(type)) ─────────────────
        // Using ConstantCallSite — fully public, works JVM 7+
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, vHandle);
        mv.visitVarInsn(Opcodes.ALOAD, 2);            // target MethodType
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite",
            "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(8, vHandle + 1);
        mv.visitEnd();
    }

    // ── base64 decode helper (injected into each class) ──────────────────────
    // Matches the encode logic exactly so decryption works at runtime.

    private void injectDecodeMethod(JClassNode classNode,
                                     String methodName, String methodDesc,
                                     String table, int extraCount) {
        MethodVisitor mv = classNode.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VARARGS | Opcodes.ACC_SYNTHETIC,
            methodName, methodDesc, null, null);

        // This is a straight port of ArkObf's insertDecodeMethod —
        // same algorithm, same local variable layout, same branching.
        // The full bytecode visitor sequence is reproduced verbatim so
        // the decode logic is correct at runtime regardless of string content.
        mv.visitCode();
        Label l0 = new Label(), l1 = new Label(), l2 = new Label(),
              l3 = new Label(), l4 = new Label(), l5 = new Label(),
              l6 = new Label(), l7 = new Label(), l8 = new Label(),
              l9 = new Label(), l10 = new Label(), l11 = new Label(),
              l12 = new Label(), l13 = new Label(), l14 = new Label(),
              l15 = new Label(), l16 = new Label(), l17 = new Label(),
              l18 = new Label(), l19 = new Label(), l20 = new Label(),
              l21 = new Label(), l22 = new Label(), l23 = new Label(),
              l24 = new Label(), l25 = new Label();

        mv.visitLabel(l0); mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitLabel(l1); mv.visitLdcInsn(""); mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitLabel(l2); mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(l3);
        mv.visitFrame(Opcodes.F_APPEND, 3,
            new Object[]{Opcodes.INTEGER, "java/lang/String", Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, l4);
        mv.visitLabel(l5);
        mv.visitLdcInsn(table);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
        mv.visitInsn(Opcodes.I2B);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitLabel(l6);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l7);
        mv.visitLabel(l8);
        new StringBuilder(); // just to match structure
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("000000");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitJumpInsn(Opcodes.GOTO, l9);
        mv.visitLabel(l7);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer",
            "toBinaryString", "(I)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitLabel(l10);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 7);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l11);
        mv.visitLabel(l12);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitJumpInsn(Opcodes.GOTO, l13);
        mv.visitLabel(l11);
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l13);
        mv.visitLabel(l14);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitLabel(l13);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 6);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, l15);
        mv.visitLabel(l16);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitLdcInsn("0");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitJumpInsn(Opcodes.GOTO, l13);
        mv.visitLabel(l15);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitLabel(l9);
        mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, l3);
        mv.visitLabel(l4);
        mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitLdcInsn("00000000");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith",
            "(Ljava/lang/String;)Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, l17);
        mv.visitLabel(l18);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
            "(II)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitJumpInsn(Opcodes.GOTO, l4);
        mv.visitLabel(l17);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitInsn(Opcodes.IDIV);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitLabel(l19);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitLabel(l20);
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{"[B", Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, l21);
        mv.visitLabel(l22);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
            "(II)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer",
            "valueOf", "(Ljava/lang/String;I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer",
            "byteValue", "()B", false);
        mv.visitInsn(Opcodes.BASTORE);
        mv.visitLabel(l23);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.BALOAD);
        mv.visitVarInsn(Opcodes.ILOAD, 1);          // encKey param
        mv.visitInsn(Opcodes.IXOR);
        mv.visitInsn(Opcodes.I2B);
        mv.visitInsn(Opcodes.BASTORE);
        mv.visitLabel(l24);
        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(Opcodes.GOTO, l20);
        mv.visitLabel(l21);
        mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(l25);
        mv.visitMaxs(6, 6 + extraCount);
        mv.visitEnd();
    }

    // ── encoding utils ───────────────────────────────────────────────────────

    private String b64Encode(byte[] bytes, String table, byte key) {
        byte[] data = bytes.clone();
        for (int i = 0; i < data.length; i++) data[i] ^= key;

        StringBuilder sb = new StringBuilder();
        int mod = 0;
        byte prev = 0;
        for (int i = 0; i < data.length; i++) {
            mod = i % 3;
            if (mod == 0) {
                sb.append(table.charAt((data[i] >> 2) & 0x3F));
            } else if (mod == 1) {
                sb.append(table.charAt((prev << 4 | data[i] >> 4 & 0x0F) & 0x3F));
            } else {
                sb.append(table.charAt((data[i] >> 6 & 0x03 | prev << 2) & 0x3F));
                sb.append(table.charAt(data[i] & 0x3F));
            }
            prev = data[i];
        }
        if (mod == 0) {
            sb.append(table.charAt(prev << 4 & 0x3C));
            sb.append("==");
        } else if (mod == 1) {
            sb.append(table.charAt(prev << 2 & 0x3F));
            sb.append("=");
        }
        return sb.toString();
    }

    private String shuffledB64Table() {
        List<Character> chars = new ArrayList<>();
        for (char c : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-/".toCharArray()) {
            chars.add(c);
        }
        Collections.shuffle(chars, rand);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }

    private Object randomBsmArg(String table) {
        return switch (rand.nextInt(8)) {
            case 0  -> rand.nextLong();
            case 1  -> rand.nextInt();
            case 2  -> Dictionary.gen(6, Purpose.FIELD);
            case 3  -> rand.nextFloat();
            case 4  -> (double) (rand.nextFloat() * 20f);
            case 5  -> (byte) rand.nextInt(Byte.MAX_VALUE);
            case 6  -> -Math.abs(rand.nextInt());
            default -> b64Encode(Dictionary.gen(6, Purpose.FIELD).getBytes(), table, (byte) 0);
        };
    }
}
