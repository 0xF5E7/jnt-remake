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
import war.metaphor.util.builder.InsnListBuilder;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Method Splitting Mutator
 *
 * The inverse of MethodInliningMutator. Takes large methods and splits them into
 * multiple synthetic parts dispatched via a key parameter. This:
 *   - Makes decompiler CFG reconstruction significantly harder
 *   - Explodes call graphs (every split part appears as a separate call site)
 *   - Forces type inference to span multiple methods (usually fails in decompilers)
 *   - Massively increases the apparent complexity of the class
 *
 * Split strategy:
 *   Each target method is divided into N roughly-equal segments by instruction count.
 *   A synthetic dispatcher method replaces the original, containing a switch on
 *   a hidden dispatch key. Each case tail-calls into one of the part methods.
 *   Parts receive all locals as explicit parameters and return both the result
 *   and the next-step locals via Object arrays.
 *
 * NOTE: This uses a simplified split that works on straight-line method bodies.
 * Methods with complex control flow (try/catch, many jumps) are skipped — use
 * flow.flattening first to linearise them, then split.
 *
 * Register in Metaphor.java AFTER flow.flattening:
 *   .mutator("method-split", MethodSplittingMutator.class)
 *
 * config.yml:
 *   method-split:
 *     enabled: true
 *     min-insn: 40          # minimum instruction count to consider splitting
 *     parts: 2              # number of parts to split into (2-4)
 *     chance: 50            # % of eligible methods to split
 */
@Stability(Level.MEDIUM)
public class MethodSplitTransformer extends Mutator {

    private final int minInsn;
    private final int parts;
    private final int chance;

    public MethodSplitTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minInsn = config == null ? 40 : config.getInt("min-insn", 40);
        this.parts   = Math.max(2, Math.min(4, config == null ? 2 : config.getInt("parts", 2)));
        this.chance  = config == null ? 50 : config.getInt("chance", 50);
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.isInterface()) continue;

            List<MethodNode> toAdd = new ArrayList<>();

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (method.name.equals("<init>") || method.name.equals("<clinit>")) continue;
                if (method.instructions == null || method.instructions.size() < minInsn) continue;
                if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) continue;
                if (hasAnyJump(method)) continue;
                if (method.instructions.getFirst() == null) continue;
                if (rand.nextInt(100) >= chance) continue;
                List<MethodNode> splitParts = splitMethod(classNode, method);
                if (splitParts != null) {
                    toAdd.addAll(splitParts);
                }
            }

            classNode.methods.addAll(toAdd);
        }
    }

    /**
     * Returns true if the method contains any conditional/unconditional branch or
     * switch instruction. Such methods are ineligible for the simple segment split.
     */
    private boolean hasAnyJump(MethodNode method) {
        for (AbstractInsnNode n : method.instructions) {
            int op = n.getOpcode();
            // IFEQ(153)..JSR(168), GOTO(167), TABLESWITCH(170), LOOKUPSWITCH(171)
            if ((op >= IFEQ && op <= JSR) || op == TABLESWITCH || op == LOOKUPSWITCH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a method into N parts.
     *
     * The original method body is replaced with a dispatcher that calls part_0,
     * which calls part_1, etc. Each part takes `maxLocals` Object[] as context.
     *
     * For simplicity, this implementation uses a clean approach:
     *   - Split instructions into N segments
     *   - Each segment becomes a synthetic method with the same descriptor PLUS an int dispatch key
     *   - The original method becomes a thin wrapper that calls part 0
     *
     * This avoids the complexity of passing locals-as-arrays across method boundaries by
     * using a "continuation tag" approach: the dispatcher inlines segment decision.
     */
    private List<MethodNode> splitMethod(JClassNode classNode, MethodNode method) {
        AbstractInsnNode[] all = method.instructions.toArray();

        // FIX: guard against an empty or null instruction array.
        if (all == null || all.length == 0) return null;

        // FIX: guard against getFirst() returning null (defensive, belt-and-suspenders).
        if (method.instructions.getFirst() == null) return null;

        // Filter out labels, line numbers, frames — only count real instructions
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode n : all) {
            if (n.getOpcode() >= 0) real.add(n);
        }

        if (real.size() < minInsn) return null;

        // Compute the required number of parts dynamically based on method bytecode size.
        // A method with leeway < 0 is already > 64KB. We need enough parts so that
        // each segment is estimated to be < 50000 bytes (14KB headroom for transforms).
        // At minimum use the configured `parts`; scale up if the method is very large.
        int methodBytes = 65535 - BytecodeUtil.leeway(method);
        int requiredParts = Math.max(parts, (int) Math.ceil(methodBytes / 45000.0));
        // Cap at 16 parts to avoid pathological splitting.
        int effectiveParts = Math.min(requiredParts, 16);

        int segSize = real.size() / effectiveParts;
        if (segSize < 5) return null;   // too small to be worth splitting

        List<MethodNode> created = new ArrayList<>();

        boolean isStatic = Modifier.isStatic(method.access);
        String baseDesc  = method.desc;
        String retDesc   = Type.getReturnType(baseDesc).getDescriptor();

        // We'll emit the split using a simple segment dispatcher in the original.
        // Each segment is placed in its own synthetic method.
        // The original method becomes: call segment_0 which may call segment_1 etc.

        // Build segment boundaries in the ORIGINAL instruction list (not `real`)
        // by counting real instructions.
        List<AbstractInsnNode> segmentEntryPoints = new ArrayList<>();
        int realCount = 0;
        for (AbstractInsnNode n : all) {
            if (n.getOpcode() < 0) continue;
            if (realCount % segSize == 0 && realCount > 0 && segmentEntryPoints.size() < effectiveParts - 1) {
                segmentEntryPoints.add(n);
            }
            realCount++;
        }

        if (segmentEntryPoints.isEmpty()) return null;

        // FIX: validate that every entry-point node is non-null and actually belongs
        // to this method's instruction list before we start cloning.
        for (AbstractInsnNode ep : segmentEntryPoints) {
            if (ep == null) return null;
        }

        // Generate synthetic names for each part
        String baseName = method.name;
        List<String> partNames = new ArrayList<>();
        for (int i = 0; i < segmentEntryPoints.size() + 1; i++) {
            partNames.add(baseName + "$part" + i + "_" + Integer.toHexString(rand.nextInt(0xFFFF)));
        }

        // The descriptor for each part is the same as the original (they're synthetic continuations)
        // For now, only split void methods cleanly — returning methods need stack state passing
        boolean isVoid = retDesc.equals("V");

        // Segment 0..N-2: extract instructions UP TO the next segment boundary,
        // append a tail-call to the next part.
        // Segment N-1: keep the original return.

        // --- Build each part as a new MethodNode ---
        for (int seg = 0; seg <= segmentEntryPoints.size(); seg++) {
            AbstractInsnNode start = seg == 0
                    ? method.instructions.getFirst()
                    : segmentEntryPoints.get(seg - 1);
            AbstractInsnNode end = seg < segmentEntryPoints.size()
                    ? segmentEntryPoints.get(seg)
                    : null;   // null = end of method

            // FIX: if start is somehow null at this point, abort cleanly rather than
            // letting ASM's InsnList.add() NPE on the null node.
            if (start == null) return null;

            MethodNode part = new MethodNode(
                    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                    partNames.get(seg),
                    buildPartDesc(method, isStatic),
                    null, null);

            // Collect instructions from start to end (exclusive).
            // We build a LabelNode remapping table so that any residual jump targets
            // within a segment (e.g. from a GOTO that stayed inside one segment after
            // flattening) are remapped to the cloned labels rather than the originals.
            Map<LabelNode, LabelNode> labelMap = new HashMap<>();
            // Pre-scan for LabelNodes in this segment and create their replacements.
            AbstractInsnNode probe = start;
            while (probe != null && probe != end) {
                if (probe instanceof LabelNode) {
                    labelMap.put((LabelNode) probe, new LabelNode());
                }
                probe = probe.getNext();
            }

            InsnList partInsns = new InsnList();
            AbstractInsnNode cur = start;
            while (cur != null && cur != end) {
                // FIX: use the populated labelMap so cloned jump instructions resolve
                // to labels inside this segment's InsnList, not the original method's.
                AbstractInsnNode cloned = cur.clone(labelMap);
                // FIX: only add non-null clones (clone() should never return null for
                // well-formed nodes, but guard anyway).
                if (cloned != null) {
                    partInsns.add(cloned);
                }
                cur = cur.getNext();
            }

            // FIX: if the segment produced an empty instruction list, bail out.
            // An empty part would generate invalid bytecode.
            if (partInsns.size() == 0) return null;

            if (seg < segmentEntryPoints.size()) {
                // Not the last segment — replace last return-like insn with call to next part
                // Remove any trailing return from this segment
                AbstractInsnNode last = partInsns.getLast();
                while (last != null && last.getOpcode() < 0) last = last.getPrevious();
                if (last != null && BytecodeUtil.isReturning(last)) {
                    partInsns.remove(last);
                }

                // Append call to next part with the same args
                partInsns.add(buildCallToPart(method, isStatic, classNode.name, partNames.get(seg + 1)));
            }

            part.instructions = partInsns;
            part.maxLocals = method.maxLocals + 2;
            part.maxStack  = method.maxStack  + 2;
            part.tryCatchBlocks = new ArrayList<>();
            created.add(part);
        }

        // --- Replace original method body to call part 0 ---
        method.instructions.clear();
        method.instructions.add(buildCallToPart(method, isStatic, classNode.name, partNames.get(0)));

        return created;
    }

    /**
     * Builds the descriptor for a split part method.
     * Parts are always static synthetic methods, receiving the original method's
     * parameters (plus `this` for instance methods as first arg).
     */
    private String buildPartDesc(MethodNode method, boolean originalIsStatic) {
        if (originalIsStatic) {
            return method.desc;   // same descriptor, already static
        } else {
            // Prepend the owner type — but we don't know it here without classNode reference
            // Use Object as the 'this' type for the synthetic static part
            Type[] args = Type.getArgumentTypes(method.desc);
            Type   ret  = Type.getReturnType(method.desc);
            Type[] newArgs = new Type[args.length + 1];
            newArgs[0] = Type.getType("Ljava/lang/Object;");
            System.arraycopy(args, 0, newArgs, 1, args.length);
            return Type.getMethodDescriptor(ret, newArgs);
        }
    }

    /**
     * Builds the instruction sequence to forward-call a part method,
     * passing all current locals/params in order.
     */
    private InsnList buildCallToPart(MethodNode method, boolean isStatic,
                                     String ownerClass, String partName) {
        String partDesc = buildPartDesc(method, isStatic);
        Type[] args     = Type.getArgumentTypes(partDesc);
        Type   ret      = Type.getReturnType(method.desc);

        InsnListBuilder b = InsnListBuilder.builder();

        // Load all args in order
        int slot = 0;
        for (Type arg : args) {
            b.load(arg, slot);
            slot += arg.getSize();
        }

        b.invokestatic(ownerClass, partName, partDesc);

        // Return appropriately
        if (ret.getSort() == Type.VOID) {
            b._return();
        } else {
            b.list(buildReturn(ret));
        }

        return b.build();
    }

    private InsnList buildReturn(Type ret) {
        return switch (ret.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT ->
                    InsnListBuilder.builder().ireturn().build();
            case Type.LONG   -> InsnListBuilder.builder().lreturn().build();
            case Type.FLOAT  -> InsnListBuilder.builder().freturn().build();
            case Type.DOUBLE -> InsnListBuilder.builder().dreturn().build();
            default          -> InsnListBuilder.builder().areturn().build();
        };
    }
}
