package war.metaphor.mutator.misc;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Variable Duplication Mutator
 *
 * For every local variable written inside a method body (excluding parameters),
 * a shadow copy of the same type is allocated at the next free slot. The two
 * slots are kept byte-for-byte in sync at all times:
 *
 *   • After every XSTORE var    → XLOAD var; XSTORE shadow  (mirror write)
 *   • After every IINC var      → ILOAD var; ISTORE shadow  (mirror increment)
 *   • At every XLOAD var        → with configurable probability the load is
 *                                 silently redirected to the shadow slot instead.
 *
 * Effect on reverse-engineering:
 *   • Doubles apparent local-variable count — decompiler name columns explode.
 *   • Breaks trivial def-use chains: a tool tracing one slot now sees two
 *     independent but equivalent paths to the same value.
 *   • Decompilers that cannot unify the two slots emit phantom variables.
 *   • Increases method bytecode size and local-variable register pressure.
 *   • Stacks cleanly with flow.flattening / string.stack / number.salt because
 *     it only touches the VAR layer, not the control-flow or stack layers.
 *
 * Safety constraints enforced:
 *   • Parameters are never duplicated (slot index < firstLocalSlot).
 *   • Abstract and native methods are skipped.
 *   • Constructors/<clinit> are skipped when skip-init is true (default).
 *   • Methods whose added instructions would exceed the 64 KB code limit
 *     (measured via BytecodeUtil.leeway) are skipped entirely.
 *   • long / double types consume two JVM slots; shadows are allocated
 *     with the same two-slot width automatically.
 *   • maxLocals and maxStack on the MethodNode are updated correctly.
 *
 * Register in Metaphor.java (after renaming, before strip):
 *   .mutator("var-duplicate", VarDuplicateMutator.class)
 *
 * config.yml:
 *   var-duplicate:
 *     enabled: true
 *     chance:    50    # 0-100 — % chance each XLOAD is redirected to the shadow
 *     min-vars:  1     # skip methods that store fewer than N distinct locals
 *     skip-init: true  # skip <init> and <clinit>
 */
@Stability(Level.HIGH)
public class VarDuplicateTransformer extends Mutator {

    /**
     * The offset between any XSTORE opcode and its corresponding XLOAD opcode.
     * ISTORE=54, ILOAD=21 → diff=33. Same delta for L, F, D, A variants.
     */
    private static final int STORE_TO_LOAD_DELTA = ISTORE - ILOAD; // 33

    private final int     chance;
    private final int     minVars;
    private final boolean skipInit;

    public VarDuplicateTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance   = config == null ? 50 : Math.max(0, Math.min(100, config.getInt("chance",    50)));
        this.minVars  = config == null ?  1 : Math.max(1,               config.getInt("min-vars",   1));
        this.skipInit = config == null || config.getBoolean("skip-init", true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt())  continue;
            if (classNode.isInterface()) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (Modifier.isNative(method.access))   continue;
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (skipInit && (method.name.equals("<init>") || method.name.equals("<clinit>"))) continue;
                if (BytecodeUtil.leeway(method) < 10_000) continue;

                processMethod(method);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core transformation
    // ─────────────────────────────────────────────────────────────────────────

    private void processMethod(MethodNode method) {

        // ── Step 1: Determine the first slot that belongs to locals (not params) ──
        int firstLocalSlot = paramSlotCount(method);
        Map<Integer, int[]> varInfo = new LinkedHashMap<>();

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && isStore(insn.getOpcode())) {
                if (var.var < firstLocalSlot) continue; // leave parameters alone
                varInfo.computeIfAbsent(var.var, k -> new int[]{ var.getOpcode(), slotWidth(var.getOpcode()) });

            } else if (insn instanceof IincInsnNode iinc) {
                if (iinc.var < firstLocalSlot) continue;
                // IINC only exists for int locals.
                varInfo.computeIfAbsent(iinc.var, k -> new int[]{ ISTORE, 1 });
            }
        }

        if (varInfo.size() < minVars) return;

        // ── Step 3: Allocate shadow slots ──────────────────────────────────────
        //
        // Shadows are laid out contiguously starting at the current maxLocals.
        // long/double shadows occupy two consecutive slots, just like the originals.
        Map<Integer, Integer> shadowOf = new HashMap<>(varInfo.size() * 2);
        int nextFreeSlot = method.maxLocals;

        for (Map.Entry<Integer, int[]> entry : varInfo.entrySet()) {
            shadowOf.put(entry.getKey(), nextFreeSlot);
            nextFreeSlot += entry.getValue()[1]; // advance by 1 (int/ref) or 2 (long/double)
        }

        method.maxLocals = nextFreeSlot;

        // The sync sequence is:  XLOAD (1 slot pushed) → XSTORE shadow (pops it).
        // For long/double both instructions operate on 2-wide values, but the JVM
        // stack ceiling already accounts for computation; just ensure we have at
        // least 2 to handle the widest type.
        method.maxStack = Math.max(method.maxStack, 2);

        // ── Step 4: Rewrite the instruction list ───────────────────────────────
        //
        // Snapshot the instruction list before modification so we iterate over
        // original nodes only — not the sync pairs we're inserting.
        AbstractInsnNode[] snapshot = method.instructions.toArray();

        for (AbstractInsnNode insn : snapshot) {
            int op = insn.getOpcode();

            if (insn instanceof VarInsnNode var) {
                Integer shadow = shadowOf.get(var.var);
                if (shadow == null) continue; // untracked var (param or not stored)

                if (isStore(op)) {
                    // ── Mirror write ─────────────────────────────────────────
                    // Original: XSTORE original
                    // Inserted after it: XLOAD original → XSTORE shadow
                    //
                    // We insert *after* the original store so the original slot
                    // is already populated before we mirror it.
                    int loadOp = op - STORE_TO_LOAD_DELTA;
                    InsnList sync = new InsnList();
                    sync.add(new VarInsnNode(loadOp, var.var));  // XLOAD original
                    sync.add(new VarInsnNode(op,     shadow));   // XSTORE shadow
                    method.instructions.insert(insn, sync);

                } else if (isLoad(op) && rand.nextInt(100) < chance) {
                    // ── Redirect load to shadow ───────────────────────────────
                    // Both slots hold identical values at this point (invariant
                    // maintained by the mirror-write above), so this is always
                    // semantically correct.
                    var.var = shadow;
                }

            } else if (insn instanceof IincInsnNode iinc) {
                Integer shadow = shadowOf.get(iinc.var);
                if (shadow == null) continue;

                // ── Mirror increment ─────────────────────────────────────────
                // Original: IINC original delta
                // Inserted after: ILOAD original → ISTORE shadow
                //
                // We do not IINC the shadow directly because IINC has a signed-byte
                // delta range of [-128, 127]. Using ILOAD/ISTORE avoids any overflow
                // concern and is always correct regardless of the delta value.
                InsnList sync = new InsnList();
                sync.add(new VarInsnNode(ILOAD,  iinc.var)); // ILOAD original
                sync.add(new VarInsnNode(ISTORE, shadow));   // ISTORE shadow
                method.instructions.insert(insn, sync);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the total number of JVM local-variable slots consumed by the
     * implicit {@code this} reference (if any) plus all explicit parameters,
     * as derived from the method descriptor.
     *
     * This is the index of the very first "true local" variable slot.
     */
    private static int paramSlotCount(MethodNode method) {
        int slots = Modifier.isStatic(method.access) ? 0 : 1; // 'this'
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            slots += arg.getSize(); // 1 for most types, 2 for long/double
        }
        return slots;
    }

    /** True for ISTORE, LSTORE, FSTORE, DSTORE, ASTORE. */
    private static boolean isStore(int op) {
        return op >= ISTORE && op <= ASTORE;
    }

    /** True for ILOAD, LLOAD, FLOAD, DLOAD, ALOAD. */
    private static boolean isLoad(int op) {
        return op >= ILOAD && op <= ALOAD;
    }

    /**
     * Returns 2 (two JVM slots) for LSTORE and DSTORE; 1 for everything else.
     * Used when allocating shadow slots so long/double shadows are wide enough.
     */
    private static int slotWidth(int storeOp) {
        return (storeOp == LSTORE || storeOp == DSTORE) ? 2 : 1;
    }
}
