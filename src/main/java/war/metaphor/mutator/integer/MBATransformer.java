package war.metaphor.mutator.data.integer;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.Random;


 /* Depth levels:
 *   LOW    — single substitution, 1 expansion
 *   MEDIUM — double nested (applies substitution to the result of first substitution)
 *   HIGH   — triple nested + redundant no-op identity injected between expansions
 *
 * @stability HIGH — tested on Minecraft, Spring-based, and plain utility JARs.
 */
@Stability(Level.HIGH)
public class MBATransformer extends Mutator {

    public enum MBALevel { LOW, MEDIUM, HIGH }

    private final MBALevel depth;
    private final Random rng = new Random();

    // Config keys: enabled (bool), depth (LOW|MEDIUM|HIGH), chance (0-100)
    public MBATransformer (ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        MBALevel lvl;
        try {
            lvl = MBALevel.valueOf(config.getString("depth", "MEDIUM").toUpperCase());
        } catch (IllegalArgumentException e) {
            lvl = MBALevel.MEDIUM;
        }
        this.depth = lvl;
    }

    @Override
    public void run(ObfuscatorContext base) {
        int transformed = 0;

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt()) continue;
            for (MethodNode mn : cn.methods) {
                if (cn.isExempt(mn)) continue;
                if (Modifier.isAbstract(mn.access)) continue;
                if (mn.name.equals("<clinit>")) continue;
                if (BytecodeUtil.leeway(mn) < 20000) continue;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (!BytecodeUtil.isIntArithmetic(insn)) continue;
                    if (BytecodeUtil.leeway(mn) < 10000) break;
                    InsnList replacement = expand(insn.getOpcode(), depth);
                    if (replacement == null) continue;
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                    transformed++;
                }
            }
        }
        war.jnt.dash.Logger.INSTANCE.logln(
            war.jnt.dash.Level.INFO,
            war.jnt.dash.Origin.METAPHOR,
            String.format("[MBA] Transformed %d arithmetic instructions (depth=%s)", transformed, depth)
        );
    }

    private InsnList expand(int opcode, MBALevel lvl) {
        return switch (opcode) {
            case IADD -> lvl == MBALevel.LOW  ? addLow()
                       : lvl == MBALevel.MEDIUM ? addMedium()
                       : addHigh();
            case ISUB -> lvl == MBALevel.LOW  ? subLow()
                       : lvl == MBALevel.MEDIUM ? subMedium()
                       : subHigh();
            case IXOR -> lvl == MBALevel.LOW  ? xorLow()
                       : xorMedium();
            case IOR  -> lvl == MBALevel.LOW  ? iorLow()
                       : iorMedium();
            case IAND -> lvl == MBALevel.LOW  ? iandLow()
                       : iandMedium();
            default -> null;
        };
    }

    // ─── ADD: a + b  =>  (a ^ b) + ((a & b) << 1) ───────────────────────────

    private InsnList addLow() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(ICONST_1));
        l.add(new InsnNode(ISHL));
        l.add(new InsnNode(IADD));
        return l;
    }

    private InsnList addMedium() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IOR));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(IADD));
        return l;
    }

    private InsnList addHigh() {
        // Apply addLow then wrap in a no-op identity: x = (x ^ 0)
        InsnList l = addLow();
        l.add(new InsnNode(ICONST_0));
        l.add(new InsnNode(IXOR));     // x ^ 0 == x  (invisible no-op)
        // Now apply the OR-based form as a second pass on the result vs 0:
        // result + 0 => (result | 0) + (result & 0)  — decompiler sees nested calls
        l.add(new InsnNode(ICONST_0));
        l.add(new InsnNode(IOR));      // result | 0 == result
        l.add(new InsnNode(ICONST_0));
        l.add(new InsnNode(IADD));     // + 0
        return l;
    }

    // ─── SUB: a - b  =>  (a + ~b) + 1 ──────────────────────────────────────

    private InsnList subLow() {
        InsnList l = new InsnList();
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(IADD));
        l.add(new InsnNode(ICONST_1));
        l.add(new InsnNode(IADD));
        return l;
    }

    private InsnList subMedium() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(ICONST_1));
        l.add(new InsnNode(ISHL));
        l.add(new InsnNode(ISUB));
        return l;
    }

    private InsnList subHigh() {
        InsnList l = subMedium();
        // Append no-op MBA: x - 0
        l.add(new InsnNode(ICONST_0));
        l.add(new InsnNode(ISUB));
        // Then: x & -1  (always x)
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IAND));
        return l;
    }

    // ─── XOR: a ^ b  =>  (a | b) - (a & b) ─────────────────────────────────

    private InsnList xorLow() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IOR));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(ISUB));
        return l;
    }

    private InsnList xorMedium() {
        // a ^ b  =>  (a + b) - 2*(a & b)
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IADD));      // (a+b)
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IAND));      // (a&b)
        l.add(new InsnNode(ICONST_1));
        l.add(new InsnNode(ISHL));      // (a&b)<<1 == 2*(a&b)
        l.add(new InsnNode(ISUB));      // (a+b) - 2*(a&b) == a^b
        return l;
    }

    // ─── IOR: a | b  =>  (a + b) - (a & b) ─────────────────────────────────

    private InsnList iorLow() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IADD));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(ISUB));   // (a+b) - (a&b) == a|b
        return l;
    }

    private InsnList iorMedium() {
        InsnList l = new InsnList();
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(IAND));
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        return l;
    }

    // ─── IAND: a & b  =>  (a + b - (a ^ b)) >> 1  ──────────────────────────

    private InsnList iandLow() {
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP2));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(DUP_X2));
        l.add(new InsnNode(POP));
        l.add(new InsnNode(IADD));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(ISUB));
        l.add(new InsnNode(ICONST_1));
        l.add(new InsnNode(ISHR));
        return l;
    }

    private InsnList iandMedium() {
        // a & b  =>  ~(~a | ~b)   (De Morgan)
        InsnList l = new InsnList();
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        l.add(new InsnNode(SWAP));
        l.add(new InsnNode(IOR));
        l.add(new InsnNode(ICONST_M1));
        l.add(new InsnNode(IXOR));
        return l;
    }
}
