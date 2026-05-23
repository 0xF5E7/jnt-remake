package war.metaphor.mutator.flow;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Dead Code Injection mutator.
 *
 * Inserts blocks of unreachable but bytecode-verifier-valid code into methods.
 * The injected blocks are placed immediately after RETURN/ATHROW instructions —
 * positions the JVM verifier (class version 50+) ignores entirely. From the
 * perspective of a decompiler that reconstructs from raw bytecode without full
 * CFG analysis, these blocks look like real conditional paths, producing
 * phantom branches, phantom local variables, and phantom exception paths in
 * the decompiled output.
 *
 * Each dead block is chosen randomly from a pool of templates:
 *   FAKE_CALL     — builds a string and calls a void method that never fires
 *   FAKE_ARITH    — a chain of arithmetic operations that ends in POP
 *   FAKE_FIELD    — reads a static field and discards it
 *   FAKE_ARRAY    — allocates a small array and queries its length
 *   FAKE_EXCEPTION — constructs an exception object and immediately POPs it
 *
 * Config:
 *   injections-per-method: (int, default 3) — max dead blocks per method
 *   chance:                (int, default 80) — % chance to inject at any site
 */
@Stability(Level.VERY_HIGH)
public class DeadCodeInjectorTransformer extends Mutator {

    private static final int TEMPLATE_COUNT = 5;

    private final int maxInjections;
    private final int chance;

    public DeadCodeInjectorTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.maxInjections = Math.max(1, config.getInt("injections-per-method", 3));
        this.chance        = Math.max(1, Math.min(config.getInt("chance", 80), 100));
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (BytecodeUtil.leeway(method) < 30000) continue;

                processMethod(method);
            }
        }
    }

    private void processMethod(MethodNode method) {
        // Collect insertion sites: instructions immediately after any RETURN or ATHROW
        List<AbstractInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            int op = insn.getOpcode();
            boolean isExit = (op >= IRETURN && op <= RETURN) || op == ATHROW;
            if (!isExit) continue;
            AbstractInsnNode next = insn.getNext();
            // Only insert between an exit and its following label (or end of list)
            // — this guarantees the block is truly unreachable.
            if (next == null || next instanceof LabelNode) {
                sites.add(insn);
            }
        }

        if (sites.isEmpty()) return;

        int injected = 0;
        for (AbstractInsnNode site : sites) {
            if (injected >= maxInjections) break;
            if (rand.nextInt(100) >= chance) continue;
            if (BytecodeUtil.leeway(method) < 10000) break;

            InsnList block = buildDeadBlock(method);
            method.instructions.insert(site, block);
            injected++;
        }
    }

    /**
     * Produces a self-contained dead block. The block begins and ends cleanly:
     * it does not rely on any stack state (stack is empty at every dead-code
     * entry point) and does not leave anything on the stack.
     */
    private InsnList buildDeadBlock(MethodNode method) {
        int template = rand.nextInt(TEMPLATE_COUNT);
        return switch (template) {
            case 0 -> deadFakeCall(method);
            case 1 -> deadFakeArith(method);
            case 2 -> deadFakeField();
            case 3 -> deadFakeArray();
            default -> deadFakeException();
        };
    }

    // ─── Template 0: fake StringBuilder.toString() call ─────────────────────
    private InsnList deadFakeCall(MethodNode method) {
        InsnList out = new InsnList();
        int var = method.maxLocals++;
        method.maxStack = Math.max(method.maxStack, method.maxLocals + 3);

        // new StringBuilder("dead" + random_ldc) .toString()  → stored, never used
        out.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        out.add(new InsnNode(DUP));
        out.add(new LdcInsnNode("_" + Integer.toHexString(rand.nextInt())));
        out.add(new MethodInsnNode(INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        // append a random integer to make it look like real formatting code
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xFFFF)));
        out.add(new MethodInsnNode(INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        out.add(new MethodInsnNode(INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        out.add(new VarInsnNode(ASTORE, var));
        return out;
    }

    // ─── Template 1: fake arithmetic chain ──────────────────────────────────
    private InsnList deadFakeArith(MethodNode method) {
        InsnList out = new InsnList();
        method.maxStack = Math.max(method.maxStack, 4);

        int a = rand.nextInt(0x7FFF) + 1;
        int b = rand.nextInt(0x7FFF) + 1;

        out.add(BytecodeUtil.makeInteger(a));
        out.add(BytecodeUtil.makeInteger(b));
        out.add(new InsnNode(IMUL));
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xFF) + 1));
        out.add(new InsnNode(IREM));
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xF) + 1));
        out.add(new InsnNode(ISHL));
        out.add(new InsnNode(POP));
        return out;
    }

    // ─── Template 2: fake System property read ──────────────────────────────
    private InsnList deadFakeField() {
        InsnList out = new InsnList();
        // Read System.lineSeparator() and discard — a plausible-looking system call
        out.add(new MethodInsnNode(INVOKESTATIC,
                "java/lang/System", "lineSeparator", "()Ljava/lang/String;", false));
        out.add(new InsnNode(POP));
        return out;
    }

    // ─── Template 3: fake array allocation and length read ──────────────────
    private InsnList deadFakeArray() {
        InsnList out = new InsnList();
        int size = rand.nextInt(8) + 2;
        out.add(BytecodeUtil.makeInteger(size));
        out.add(new IntInsnNode(NEWARRAY, T_INT));
        out.add(new InsnNode(ARRAYLENGTH));
        out.add(new InsnNode(POP));
        return out;
    }

    // ─── Template 4: construct an exception object, never throw it ──────────
    private InsnList deadFakeException() {
        InsnList out = new InsnList();
        String[] types = {
            "java/lang/RuntimeException",
            "java/lang/IllegalStateException",
            "java/lang/IllegalArgumentException",
            "java/lang/UnsupportedOperationException"
        };
        String type = types[rand.nextInt(types.length)];
        // new XxxException("dead") — appears to be error-handling code
        out.add(new TypeInsnNode(NEW, type));
        out.add(new InsnNode(DUP));
        out.add(new LdcInsnNode("err_" + Integer.toHexString(rand.nextInt())));
        out.add(new MethodInsnNode(INVOKESPECIAL,
                type, "<init>", "(Ljava/lang/String;)V", false));
        out.add(new InsnNode(POP));
        return out;
    }
}
