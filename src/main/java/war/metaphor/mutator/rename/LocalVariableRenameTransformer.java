package war.metaphor.mutator.rename;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.dash.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Stability(Level.VERY_HIGH)
public class LocalVariableRenameTransformer extends Mutator {

    private static final Logger logger = Logger.INSTANCE;

    private final Dictionary.Mode mode;
    private final String prefix;
    private final int length;
    private final boolean skipThis;

    public LocalVariableRenameTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.mode     = Dictionary.Mode.of(config == null ? null : config.getString("dictionary", "random"));
        this.prefix   = config == null ? "" : config.getString("prefix", "");
        this.length   = config == null ? 5 : config.getInt("length", 5);
        this.skipThis = config == null || config.getBoolean("skip-this", true);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int renamedSlots   = 0;
        int renamedEntries = 0;
        int synthesized    = 0;

        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (Modifier.isNative(method.access)) continue;
                if (method.signature != null && method.signature.equals("bsm::jnt:excluded")) continue;
                if (method.instructions == null || method.instructions.size() == 0) continue;

                boolean isStatic = Modifier.isStatic(method.access);

                if (method.localVariables == null || method.localVariables.isEmpty()) {
                    synthesizeLVT(method);
                    synthesized++;
                }

                if (method.localVariables == null || method.localVariables.isEmpty()) continue;

                Map<Integer, String> slotNames = new HashMap<>();

                for (LocalVariableNode lv : method.localVariables) {
                    if (skipThis && !isStatic && lv.index == 0) continue;

                    if (!slotNames.containsKey(lv.index)) {
                        slotNames.put(lv.index, Dictionary.gen(length, Purpose.GENERIC, mode, prefix));
                        renamedSlots++;
                    }

                    lv.name = slotNames.get(lv.index);
                    renamedEntries++;
                }
            }
        }

        logger.logln(Level.INFO, Origin.METAPHOR,
            String.format("LocalVariableRenamer: renamed %d slots across %d entries (%d synthetic)",
                renamedSlots, renamedEntries, synthesized));
    }

    private void synthesizeLVT(MethodNode method) {
        Map<Integer, Integer> slotOpcodes = new LinkedHashMap<>();

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var) {
                slotOpcodes.putIfAbsent(var.var, var.getOpcode());
            } else if (insn instanceof IincInsnNode iinc) {
                slotOpcodes.putIfAbsent(iinc.var, ILOAD);
            }
        }

        if (slotOpcodes.isEmpty()) return;

        LabelNode start = new LabelNode();
        LabelNode end   = new LabelNode();
        method.instructions.insert(start);
        method.instructions.add(end);

        if (method.localVariables == null) method.localVariables = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : slotOpcodes.entrySet()) {
            method.localVariables.add(new LocalVariableNode(
                "var" + entry.getKey(),
                opcodeToDesc(entry.getValue()),
                null, start, end,
                entry.getKey()
            ));
        }
    }

    private String opcodeToDesc(int opcode) {
        if (opcode >= ISTORE && opcode <= ASTORE) opcode -= (ISTORE - ILOAD);
        return switch (opcode) {
            case ILOAD -> "I";
            case LLOAD -> "J";
            case FLOAD -> "F";
            case DLOAD -> "D";
            default    -> "Ljava/lang/Object;";
        };
    }
}
