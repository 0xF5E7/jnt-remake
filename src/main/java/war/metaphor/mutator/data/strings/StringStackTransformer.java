package war.metaphor.mutator.data.strings;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.util.ArrayList;
import java.util.List;


 /* Configuration notes:
 *   - max-length  : strings longer than this are skipped (default 64).
 *                   Very long strings produce huge method bodies and risk
 *                   hitting the 64 KB bytecode-per-method JVM limit.
 *   - min-length  : strings shorter than this are skipped (default 2).
 *                   One-char strings barely benefit and inflate code.
 *   - xor-key     : if non-zero, each char value is XOR'd with this key
 *                   before being stored as a constant and XOR'd back at
 *                   runtime. Adds one IXOR per character but hides the
 *                   true char values from static analysis of the bytecode.
 *
 * Registration in Metaphor.java:
 *   .mutator("string.stack", StringStackMutator.class)
 *
 * config.yml  (place AFTER renaming, BEFORE flow obfuscation):
 *   string.stack:
 *     enabled: true
 *     min-length: 2
 *     max-length: 64
 *     xor-key: 0       # 0 = disabled; any int 1-65535 = per-char XOR mask
 */
@Stability(Level.HIGH)
public class StringStackTransformer extends Mutator {

    private static final String SB   = "java/lang/StringBuilder";
    private static final String SB_D = "Ljava/lang/StringBuilder;";

    private final int minLength;
    private final int maxLength;
    private final int xorKey;

    public StringStackTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minLength = config == null ? 2  : config.getInt("min-length", 2);
        this.maxLength = config == null ? 64 : config.getInt("max-length", 64);
        this.xorKey    = config == null ? 0  : config.getInt("xor-key", 0);
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt()) continue;

            for (MethodNode mn : cn.methods) {
                if (cn.isExempt(mn)) continue;
                BytecodeUtil.translateConcatenation(mn);

                processMethod(mn);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void processMethod(MethodNode mn) {
        List<AbstractInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode ain : mn.instructions) {
            if (!BytecodeUtil.isString(ain)) continue;
            String s = BytecodeUtil.getString(ain);
            if (s == null) continue;
            if (s.length() < minLength)  continue;
            if (s.length() > maxLength)  continue;
            targets.add(ain);
        }

        for (AbstractInsnNode ain : targets) {
            if (BytecodeUtil.leeway(mn) < 30_000) break;
            String s = BytecodeUtil.getString(ain);
            if (s == null) continue;
            InsnList replacement = buildStackString(s);
            if (!BytecodeUtil.hasSpace(mn, replacement)) continue;
            mn.instructions.insertBefore(ain, replacement);
            mn.instructions.remove(ain);
        }
    }

    private InsnList buildStackString(String s) {
        InsnList insns = new InsnList();

        // new StringBuilder()
        insns.add(new TypeInsnNode(NEW, SB));
        insns.add(new InsnNode(DUP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, SB, "<init>", "()V", false));

        char[] chars = s.toCharArray();

        for (char c : chars) {
            // Push the (possibly XOR'd) character value
            int stored = (xorKey != 0) ? (c ^ xorKey) : c;
            insns.add(pushInt(stored));

            // Undo the XOR at runtime
            if (xorKey != 0) {
                insns.add(pushInt(xorKey));
                insns.add(new InsnNode(IXOR));
            }

            // Cast int → char and append
            insns.add(new InsnNode(I2C));
            insns.add(new MethodInsnNode(
                INVOKEVIRTUAL, SB, "append", "(C)" + SB_D, false));
        }

        // StringBuilder → String
        insns.add(new MethodInsnNode(
            INVOKEVIRTUAL, SB, "toString", "()Ljava/lang/String;", false));

        return insns;
    }

    private AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(ICONST_0 + value);   // ICONST_M1 = ICONST_0 + (-1) = 2
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(SIPUSH, value);
        } else {
            return new LdcInsnNode(value);
        }
    }
}
