package war.metaphor.mutator.virtualization;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Chance;
import war.metaphor.util.asm.BytecodeUtil;

public class VirtualizingTransformer extends Mutator {

    private final int chance;

    public VirtualizingTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance = config != null ? config.getInt("chance", 100) : 100;
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode jcn : base.getClasses()) {
            for (MethodNode method : jcn.methods) {
                int leeway = BytecodeUtil.leeway(method);
                for (AbstractInsnNode ain : method.instructions.toArray()) {
                    if (leeway < 30000) break;
                    if (!BytecodeUtil.isInteger(ain)) continue;
                    if (!Chance.chance(chance)) continue;
                    int value = BytecodeUtil.getInteger(ain);
                    int stack = method.maxLocals++;
                    int sp    = method.maxLocals++;
                    int op    = method.maxLocals++;
                    int pushOpcode = selectPushOpcode(value);
                    VirtualMachine vm = createVm(value, stack, sp, op, pushOpcode);
                    var list = new InsnList();
                    list.add(BytecodeUtil.makeInteger(pushOpcode));
                    list.add(new VarInsnNode(ISTORE, op));
                    list.add(vm.generate());
                    method.instructions.insertBefore(ain, list);
                    method.instructions.remove(ain);
                    leeway = BytecodeUtil.leeway(method);
                }
            }
        }
    }

    private static int selectPushOpcode(int value) {
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return VirtualMachine.PUSH8;
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return VirtualMachine.PUSH16;
        } else {
            return VirtualMachine.PUSH32;
        }
    }

    @NotNull
    private static VirtualMachine createVm(int value, int stack, int sp, int op, int pushOpcode) {
        byte[] integer = new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };

        VirtualMachineInterface vmi = new VirtualMachineInterface(stack, sp, op);
        return new VirtualMachine(vmi, integer, pushOpcode);
    }
}
