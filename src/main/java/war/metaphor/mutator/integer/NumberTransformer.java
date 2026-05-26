package war.metaphor.mutator.integer;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.dash.Level;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NumberTransformer extends Mutator {

    public NumberTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int total = 0;

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt()) continue;
            if (Modifier.isInterface(cn.access)) continue;

            InsnList deobfInsns = new InsnList();

            // Count numbers in all methods first; bail if too large
            int count = 0;
            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    int op = insn.getOpcode();
                    if (op >= ICONST_M1 && op <= LDC) count++;
                }
            }
            if (count > 1000) continue;

            List<MethodNode> eligible = new ArrayList<>();
            for (MethodNode mn : cn.methods) {
                if (mn.instructions != null && mn.instructions.size() > 0)
                    eligible.add(mn);
            }

            for (MethodNode mn : eligible) {
                if (cn.isExempt(mn)) continue;
                if (Modifier.isAbstract(mn.access)) continue;

                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    int opcode = insn.getOpcode();
                    if (opcode < ICONST_M1 || opcode > LDC) continue;

                    AbstractInsnNode newInsn;

                    if (opcode <= ICONST_5) {
                        newInsn = generateInsn(cn, deobfInsns, opcode - ICONST_0);
                    } else {
                        switch (opcode) {
                            case BIPUSH:
                            case SIPUSH:
                                newInsn = generateInsn(cn, deobfInsns, ((IntInsnNode) insn).operand);
                                break;
                            case LDC: {
                                LdcInsnNode ldc = (LdcInsnNode) insn;
                                if (ldc.cst instanceof Number) {
                                    newInsn = generateInsn(cn, deobfInsns, (Number) ldc.cst);
                                } else {
                                    continue;
                                }
                                break;
                            }
                            default:
                                continue;
                        }
                    }

                    mn.instructions.set(insn, newInsn);
                    total++;
                }
            }

            if (deobfInsns.size() > 0) {
                MethodNode clinit = cn.getStaticInit();
                clinit.instructions.insert(deobfInsns);
            }
        }

        Logger.INSTANCE.logln(Level.INFO, Origin.METAPHOR, "NumberTransformer: mutated {} numbers", total);
    }

    private AbstractInsnNode generateInsn(JClassNode cn, InsnList deobfInsns, Number value) {
        String fieldName = "fuck$" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String className = value.getClass().getSimpleName();
        String desc = String.valueOf(className.charAt(0));

        switch (className) {
            case "Integer":
                deobfInsns.add(getIntPush((Integer) value));
                break;
            case "Long":
                desc = "J";
                deobfInsns.add(getLongPush((Long) value));
                break;
            case "Float":
                deobfInsns.add(getFloatPush((Float) value));
                break;
            case "Double":
                deobfInsns.add(getDoublePush((Double) value));
                break;
            default:
                throw new IllegalArgumentException("Unsupported number type: " + className);
        }

        deobfInsns.add(new FieldInsnNode(PUTSTATIC, cn.name, fieldName, desc));
        cn.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, fieldName, desc, null, null));

        return new FieldInsnNode(GETSTATIC, cn.name, fieldName, desc);
    }

    private int getRotatedInt(int value, int shiftDist) {
        return (value << shiftDist) | (value >>> -shiftDist);
    }

    private InsnList getIntPush(int value) {
        InsnList insns = new InsnList();

        if (rand.nextBoolean()) {
            insns.add(BytecodeUtil.makeInteger(Integer.reverse(value)));
            insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "reverse", "(I)I", false));
            return insns;
        }

        int shift = rand.nextInt() & 255;
        insns.add(BytecodeUtil.makeInteger(getRotatedInt(value, shift)));
        insns.add(BytecodeUtil.makeInteger(shift));
        insns.add(new InsnNode(IUSHR));
        insns.add(BytecodeUtil.makeInteger(getRotatedInt(value, shift)));
        insns.add(BytecodeUtil.makeInteger(shift));

        if (rand.nextBoolean()) {
            insns.add(new InsnNode(ICONST_M1));
            insns.add(new InsnNode(IXOR));
            insns.add(new InsnNode(ICONST_1));
            insns.add(new InsnNode(IADD));
        } else {
            insns.add(new InsnNode(INEG));
        }

        insns.add(new InsnNode(ISHL));
        insns.add(new InsnNode(IOR));

        if (rand.nextBoolean()) {
            insns.add(new LdcInsnNode(0xFFFFFFFF));
            insns.add(new InsnNode(IAND));
        }

        return insns;
    }

    private InsnList getLongPush(long value) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Long.reverse(value)));
        insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "reverse", "(J)J", false));
        return insns;
    }

    private InsnList getFloatPush(float value) {
        InsnList insns = new InsnList();
        insns.add(getIntPush(Float.floatToIntBits(value)));
        insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        return insns;
    }

    private InsnList getDoublePush(double value) {
        InsnList insns = new InsnList();
        insns.add(getLongPush(Double.doubleToLongBits(value)));
        insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
        return insns;
    }
}
