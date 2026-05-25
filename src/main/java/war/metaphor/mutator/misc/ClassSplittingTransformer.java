package war.metaphor.mutator.misc;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Class Splitting Mutator
 *
 * Moves methods that are too large to safely obfuscate (close to or over the
 * JVM's 64 KB per-method bytecode limit) out of their original class and into
 * freshly-generated synthetic companion classes.
 *
 * The original method is replaced with a thin static forwarder that delegates
 * to the moved copy. All other obfuscation passes (renaming, string encryption,
 * number obfuscation, flow obfuscation, etc.) will have already run on the
 * method body before this mutator is reached, so the moved method is fully
 * obfuscated — only the serialisation step is made safe.
 *
 * Why this works where MethodSplittingMutator cannot:
 *   MethodSplittingMutator requires methods with no jumps and no try/catch blocks.
 *   The classes that fail to serialise (LZMADecoder, JnicInputStream, Main, etc.)
 *   have complex control flow and exception handlers — they are always skipped.
 *   ClassSplittingMutator does not care about control flow; it moves the entire
 *   method body verbatim, which is always safe.
 *
 * Register in Metaphor.java as the LAST mutator before strip/optimizer:
 *   .mutator("class-split", ClassSplittingMutator.class)
 *
 * config.yml:
 *   class-split:
 *     enabled: true
 *     threshold: 48000   # move methods whose bytecode exceeds this many bytes
 *     methods-per-class: 8  # max methods placed in one companion class
 */
@Stability(Level.HIGH)
public class ClassSplittingTransformer extends Mutator {

    /** Move methods whose estimated bytecode size exceeds this threshold (bytes). */
    private final int threshold;

    /** Maximum number of methods to pack into one synthetic companion class. */
    private final int methodsPerClass;

    public ClassSplittingTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.threshold       = config == null ? 48_000 : config.getInt("threshold", 48_000);
        this.methodsPerClass = config == null ? 8       : config.getInt("methods-per-class", 8);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {
        // Collect all new companion classes we generate so we can add them to the
        // context at the end (avoid ConcurrentModificationException on base.getClasses()).
        List<JClassNode> companions = new ArrayList<>();

        // Current companion being filled.
        JClassNode currentCompanion = null;
        int        methodsInCurrent = 0;

        for (JClassNode classNode : new ArrayList<>(base.getClasses())) {
            if (classNode.isExempt())   continue;
            if (classNode.isInterface()) continue;

            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (classNode.isExempt(method))             continue;
                if (Modifier.isAbstract(method.access))     continue;
                if (Modifier.isNative(method.access))       continue;
                if (method.name.equals("<init>")
                    || method.name.equals("<clinit>"))      continue;
                if (method.instructions == null
                    || method.instructions.size() == 0)     continue;

                int size = estimatedByteSize(method);
                if (size < threshold) continue;  // small enough — leave it alone

                // Need a companion for this method.
                if (currentCompanion == null || methodsInCurrent >= methodsPerClass) {
                    currentCompanion = makeCompanionClass(classNode);
                    companions.add(currentCompanion);
                    methodsInCurrent = 0;
                }

                moveMethodToCompanion(classNode, method, currentCompanion);
                methodsInCurrent++;
            }
        }

        // Register all generated companion classes.
        for (JClassNode companion : companions) {
            base.addClass(companion);
            base.getClassCache().put(companion.name, companion);
        }
    }

    private void moveMethodToCompanion(JClassNode origin,
                                       MethodNode  method,
                                       JClassNode  companion) {

        boolean isStatic = Modifier.isStatic(method.access);
        String companionDesc;
        if (isStatic) {
            companionDesc = method.desc;
        } else {
            Type[] origArgs = Type.getArgumentTypes(method.desc);
            Type   ret      = Type.getReturnType(method.desc);
            Type[] newArgs  = new Type[origArgs.length + 1];
            newArgs[0]      = Type.getType("Ljava/lang/Object;");
            System.arraycopy(origArgs, 0, newArgs, 1, origArgs.length);
            companionDesc = Type.getMethodDescriptor(ret, newArgs);
        }

        MethodNode moved = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                method.name,
                companionDesc,
                method.signature,
                method.exceptions == null ? null
                    : method.exceptions.toArray(new String[0]));

        // Clone the full instruction list with a fresh label map.
        Map<LabelNode, LabelNode> labelMap = buildLabelMap(method);
        for (AbstractInsnNode insn : method.instructions) {
            moved.instructions.add(insn.clone(labelMap));
        }

        // Clone try/catch blocks with the remapped labels.
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                moved.tryCatchBlocks.add(new TryCatchBlockNode(
                        labelMap.getOrDefault(tcb.start,   tcb.start),
                        labelMap.getOrDefault(tcb.end,     tcb.end),
                        labelMap.getOrDefault(tcb.handler, tcb.handler),
                        tcb.type));
            }
        }

        moved.maxLocals = method.maxLocals;
        moved.maxStack  = method.maxStack;
        companion.methods.add(moved);
        method.instructions.clear();
        if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
        if (method.localVariables  != null) method.localVariables.clear();

        InsnList forwarder = buildForwarder(origin, method, isStatic,
                                            companion.name, companionDesc);
        method.instructions.add(forwarder);
        method.maxLocals = computeForwarderLocals(method.desc, isStatic);
        method.maxStack  = computeForwarderStack(method.desc, isStatic);
    }

    private InsnList buildForwarder(JClassNode  origin,
                                    MethodNode  method,
                                    boolean     isStatic,
                                    String      companionName,
                                    String      companionDesc) {
        InsnList list = new InsnList();
        Type[]   args = Type.getArgumentTypes(method.desc);
        Type     ret  = Type.getReturnType(method.desc);

        int slot = 0;

        if (!isStatic) {
            // Pass `this` as first argument (Object).
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }

        for (Type arg : args) {
            list.add(new VarInsnNode(loadOpcode(arg), slot));
            slot += arg.getSize();
        }

        list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                companionName,
                method.name,
                companionDesc,
                false));

        list.add(new InsnNode(returnOpcode(ret)));
        return list;
    }

    private JClassNode makeCompanionClass(JClassNode origin) {
        JClassNode companion = new JClassNode();
        companion.version    = origin.version;
        companion.access     = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
        companion.name       = Dictionary.gen(10, Purpose.CLASS);
        companion.superName  = "java/lang/Object";
        companion.interfaces = new ArrayList<>();
        companion.methods    = new ArrayList<>();
        companion.fields     = new ArrayList<>();
        companion.setRealName(companion.name);
        return companion;
    }

    private static Map<LabelNode, LabelNode> buildLabelMap(MethodNode method) {
        Map<LabelNode, LabelNode> map = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode ln) {
                map.put(ln, new LabelNode());
            }
        }
        // Also cover try/catch labels that may not appear in the instruction list.
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                map.computeIfAbsent(tcb.start,   k -> new LabelNode());
                map.computeIfAbsent(tcb.end,     k -> new LabelNode());
                map.computeIfAbsent(tcb.handler, k -> new LabelNode());
            }
        }
        return map;
    }

    /** Estimate the serialised bytecode size of a method using ASM's CodeSizeEvaluator. */
    private static int estimatedByteSize(MethodNode method) {
        return 65_536 - BytecodeUtil.leeway(method);
    }

    /** Returns the correct xLOAD opcode for a given type. */
    private static int loadOpcode(Type t) {
        return switch (t.getSort()) {
            case Type.LONG    -> Opcodes.LLOAD;
            case Type.FLOAT   -> Opcodes.FLOAD;
            case Type.DOUBLE  -> Opcodes.DLOAD;
            case Type.INT, Type.BOOLEAN,
                 Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.ILOAD;
            default           -> Opcodes.ALOAD;
        };
    }

    /** Returns the correct xRETURN opcode for a given return type. */
    private static int returnOpcode(Type t) {
        return switch (t.getSort()) {
            case Type.VOID    -> Opcodes.RETURN;
            case Type.LONG    -> Opcodes.LRETURN;
            case Type.FLOAT   -> Opcodes.FRETURN;
            case Type.DOUBLE  -> Opcodes.DRETURN;
            case Type.INT, Type.BOOLEAN,
                 Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.IRETURN;
            default           -> Opcodes.ARETURN;
        };
    }

    /** Minimum maxLocals needed for the forwarder stub. */
    private static int computeForwarderLocals(String desc, boolean isStatic) {
        int slots = isStatic ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(desc)) slots += arg.getSize();
        return slots;
    }

    /** Minimum maxStack needed for the forwarder stub (all args pushed at once). */
    private static int computeForwarderStack(String desc, boolean isStatic) {
        int slots = isStatic ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(desc)) slots += arg.getSize();
        return Math.max(slots, 1);
    }
}
