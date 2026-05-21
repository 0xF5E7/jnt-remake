package war.metaphor.mutator.flow;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.analysis.graph.Block;
import war.metaphor.analysis.graph.ControlFlowGraph;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Chance;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * BogusExceptionHandlerMutator — flow.bogus-exceptions
 *
 * Injects bogus try/catch blocks whose handler body can NEVER be reached at
 * runtime, but which confuse decompiler control-flow reconstruction,
 * type-inference engines, and symbolic-execution tools.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * THREE INJECTION STRATEGIES (chosen per-site at random):
 *
 *  1. UNSATISFIABLE_GUARD  (most common)
 *     Wraps a block of real instructions in a try region guarded by a
 *     catch(ExceptionType). The handler begins with an opaque predicate that
 *     provably never holds (e.g. comparing a value to itself with !=), then
 *     jumps back to the real flow. The JVM verifier accepts it; decompilers
 *     emit a phantom catch branch.
 *
 *       try {
 *           <real code>
 *       } catch (ArithmeticException e) {    // registered in tryCatchBlocks
 *           if (0xDEAD != 0xDEAD) {          // always false — dead block
 *               throw new RuntimeException(); // unreachable but typed
 *           }
 *           // fall off handler into a GOTO back to real flow
 *       }
 *
 *  2. PHANTOM_HANDLER_CHAIN
 *     Inserts a try{ACONST_NULL; ATHROW} catch(Throwable) block, but the
 *     handler body is a chain of useless computations (string hashing, int
 *     arithmetic, dead stores) that terminate in ATHROW of a new exception.
 *     The real code path never enters the try region because it is entered
 *     via a GOTO that skips over it — decompilers cannot prove this.
 *
 *  3. NESTED_MULTI_CATCH
 *     Wraps a subset of real instructions in a try covered by MULTIPLE
 *     catch types (RuntimeException, Error, Throwable) where each handler
 *     references a different dead-code stub. Decompilers that reconstruct
 *     multi-catch produce deeply nested, unreadable output.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * Handler body dead-code styles (rotated per injection):
 *
 *  A. THROW_NEW      — new RuntimeException("msg"); ATHROW
 *  B. RETURN_STUB    — appropriate typed constant + xRETURN
 *     (only used when the method return type allows a compile-safe stub)
 *  C. INFINITE_LOOP  — GOTO self (unreachable infinite loop label)
 *  D. HASH_CHAIN     — string.hashCode() cascade + POP + GOTO real flow
 *     (looks like a legitimate computation to decompilers)
 *
 * ──────────────────────────────────────────────────────────────────────────
 * SAFETY:
 *  - Frame analysis is run first; only sites with an empty operand stack are
 *    targeted (matching TrapEdgeMutator's proven approach).
 *  - <init> methods are skipped before the super() call because the JVM
 *    verifier rejects exception handlers that span an uninitialised 'this'.
 *  - Methods already at/near the 64 KB bytecode limit are skipped per the
 *    standard BytecodeUtil.leeway() guard.
 *  - Each inserted try/catch block is added to method.tryCatchBlocks at
 *    the FRONT (index 0) so nested orderings remain valid.
 *  - Abstract and native methods are skipped.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * config.yml:
 *
 *   flow.bogus-exceptions:
 *     enabled: true
 *     chance: 40          # % of eligible sites to inject (default 40)
 *     min-block-size: 3   # min real instructions to wrap (default 3)
 *     max-injections: 8   # max injections per method (default 8)
 *     strategies: [unsatisfiable, phantom, nested]   # strategies to use
 *
 * Registration in Metaphor.java:
 *   .mutator("flow.bogus-exceptions", BogusExceptionHandlerMutator.class)
 *
 * Recommended order: after flow.flattening, before string.light.
 *
 * @author jnt
 */
@Stability(Level.HIGH)
public class BogusExceptionHandlerMutator extends Mutator {

    // ── exception types rotated across handler injections ──────────────────
    private static final String[] EXCEPTION_TYPES = {
            "java/lang/RuntimeException",
            "java/lang/ArithmeticException",
            "java/lang/ArrayIndexOutOfBoundsException",
            "java/lang/NullPointerException",
            "java/lang/ClassCastException",
            "java/lang/IllegalStateException",
            "java/lang/IllegalArgumentException",
            "java/lang/UnsupportedOperationException",
    };

    private static final String[] RUNTIME_MESSAGES = {
            "Unexpected state",
            "Internal error",
            "Invalid operation",
            "Assertion failed",
            "Illegal access",
            "Constraint violated",
    };

    // ── strategy tags ───────────────────────────────────────────────────────
    private enum Strategy { UNSATISFIABLE, PHANTOM, NESTED }

    // ── dead-code body styles ───────────────────────────────────────────────
    private enum HandlerStyle { THROW_NEW, INFINITE_LOOP, HASH_CHAIN }

    // ── config ──────────────────────────────────────────────────────────────
    private final double chance;
    private final int minBlockSize;
    private final int maxInjections;
    private final List<Strategy> strategies;

    public BogusExceptionHandlerMutator(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance       = config == null ? 40 : config.getDouble("chance", 40);
        this.minBlockSize = config == null ? 3  : config.getInt("min-block-size", 3);
        this.maxInjections= config == null ? 8  : config.getInt("max-injections", 8);

        List<String> rawStrategies = config == null ? Collections.emptyList()
                : config.getStringList("strategies");
        List<Strategy> parsed = new ArrayList<>();
        for (String s : rawStrategies) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "unsatisfiable" -> parsed.add(Strategy.UNSATISFIABLE);
                case "phantom"       -> parsed.add(Strategy.PHANTOM);
                case "nested"        -> parsed.add(Strategy.NESTED);
            }
        }
        // default: all three
        this.strategies = parsed.isEmpty()
                ? List.of(Strategy.UNSATISFIABLE, Strategy.PHANTOM, Strategy.NESTED)
                : Collections.unmodifiableList(parsed);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Entry
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.isInterface()) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (Modifier.isNative(method.access)) continue;
                if (method.instructions == null || method.instructions.size() < 4) continue;

                // Quick leeway guard before running the heavier CFG analysis
                if (BytecodeUtil.leeway(method) < 30000) continue;

                processMethod(classNode, method);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Per-method processing
    // ═══════════════════════════════════════════════════════════════════════

    private void processMethod(JClassNode classNode, MethodNode method) {
        ControlFlowGraph graph = new ControlFlowGraph(classNode, method);
        if (!graph.compute()) return;

        Map<AbstractInsnNode, Frame<BasicValue>> frames = graph.getFrames();
        if (frames == null || frames.isEmpty()) return;

        // Find the first instruction AFTER super() / this() in <init> so we
        // never create exception ranges that span an uninitialised 'this'.
        int superCallIndex = findSuperCallIndex(method);

        List<Block> eligibleBlocks = new ArrayList<>();
        for (Block block : graph.getBlocks()) {
            if (block.isTrapHandler()) continue;
            if (block.isCarrying()) continue;
            if (block.getInstructions() == null) continue;

            // count real (non-label, non-line) instructions in the block
            long realInsns = block.getInstructions().stream()
                    .filter(i -> !(i instanceof LabelNode)
                             && !(i instanceof LineNumberNode)
                             && !(i instanceof FrameNode))
                    .count();
            if (realInsns < minBlockSize) continue;

            // first instruction of block must be reachable (frame != null)
            // and must have an EMPTY stack so we can safely wrap it
            AbstractInsnNode first = firstReal(block.getInstructions());
            if (first == null) continue;
            Frame<BasicValue> frame = frames.get(first);
            if (frame == null || frame.getStackSize() != 0) continue;

            // skip block if it falls inside the <init> pre-super region
            if (first.index < superCallIndex) continue;

            eligibleBlocks.add(block);
        }

        if (eligibleBlocks.isEmpty()) return;

        Collections.shuffle(eligibleBlocks, rand);

        int injected = 0;
        int strategyIndex = rand.nextInt(strategies.size());

        for (Block block : eligibleBlocks) {
            if (injected >= maxInjections) break;
            if (BytecodeUtil.leeway(method) < 20000) break;
            if (!Chance.chance(chance)) continue;

            Strategy strategy = strategies.get(strategyIndex % strategies.size());
            strategyIndex++;

            boolean ok = switch (strategy) {
                case UNSATISFIABLE -> injectUnsatisfiable(method, block, frames);
                case PHANTOM       -> injectPhantom(method, block);
                case NESTED        -> injectNested(method, block, frames);
            };

            if (ok) injected++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 1 — UNSATISFIABLE GUARD
    //
    // Wraps a block of real code in a try/catch. The handler has an opaque
    // guard that is provably false, then GOTOs back to the real fall-through.
    // ═══════════════════════════════════════════════════════════════════════

    private boolean injectUnsatisfiable(MethodNode method, Block block,
                                        Map<AbstractInsnNode, Frame<BasicValue>> frames) {
        List<AbstractInsnNode> insns = block.getInstructions();
        AbstractInsnNode first = firstReal(insns);
        AbstractInsnNode last  = lastWrappable(insns);
        if (first == null || last == null || first == last) return false;

        // Labels bounding the try region
        LabelNode tryStart  = new LabelNode();
        LabelNode tryEnd    = new LabelNode();
        LabelNode handlerLbl= new LabelNode();
        LabelNode realFall  = new LabelNode();   // where real control continues

        String exType  = pickException();
        HandlerStyle hs = pickHandlerStyle();

        // ── try-start label before first real instruction ───────────────────
        method.instructions.insertBefore(first, tryStart);

        // ── try-end label + real-fall label after the last wrappable insn ───
        method.instructions.insert(last, realFall);
        method.instructions.insert(last, tryEnd);

        // ── handler body ────────────────────────────────────────────────────
        // The handler is appended after realFall so it is textually after the
        // normal code path. A GOTO at the end of the handler jumps back.
        InsnList handler = new InsnList();
        handler.add(handlerLbl);
        handler.add(new InsnNode(POP));              // pop the exception object

        // opaque guard: (int ^ int) != 0  →  always false
        int magic = rand.nextInt();
        handler.add(BytecodeUtil.makeInteger(magic));
        handler.add(BytecodeUtil.makeInteger(magic));
        handler.add(new InsnNode(IXOR));             // always 0
        LabelNode skipDead = new LabelNode();
        handler.add(new JumpInsnNode(IFEQ, skipDead)); // always taken → skip dead body

        // dead body between the guard and skipDead
        handler.add(buildDeadBody(hs, method));

        handler.add(skipDead);
        handler.add(new JumpInsnNode(GOTO, realFall));

        method.instructions.add(handler);

        // ── register the try/catch ───────────────────────────────────────────
        method.tryCatchBlocks.add(0, new TryCatchBlockNode(tryStart, tryEnd, handlerLbl, exType));

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 2 — PHANTOM HANDLER
    //
    // Inserts a self-contained phantom try{throw null} catch(T){dead} block
    // that is entered via GOTO-skip so real control never touches it.
    // Decompilers see an apparent throw/catch at that location.
    // ═══════════════════════════════════════════════════════════════════════

    private boolean injectPhantom(MethodNode method, Block block) {
        AbstractInsnNode anchor = firstReal(block.getInstructions());
        if (anchor == null) return false;

        LabelNode skip      = new LabelNode();
        LabelNode tryStart  = new LabelNode();
        LabelNode tryEnd    = new LabelNode();
        LabelNode handlerLbl= new LabelNode();

        String exType = pickException();
        HandlerStyle hs = pickHandlerStyle();

        InsnList phantom = new InsnList();

        // Real path: jump over the phantom block entirely
        phantom.add(new JumpInsnNode(GOTO, skip));

        // Phantom try body: push null and throw → handler catches it
        phantom.add(tryStart);
        phantom.add(new InsnNode(ACONST_NULL));
        phantom.add(new InsnNode(ATHROW));
        phantom.add(tryEnd);

        // Handler body: dead computation, then ATHROW to keep types happy
        phantom.add(handlerLbl);
        phantom.add(new InsnNode(POP));
        phantom.add(buildDeadBody(hs, method));

        // skip label: real execution resumes here
        phantom.add(skip);

        method.instructions.insertBefore(anchor, phantom);
        method.tryCatchBlocks.add(0, new TryCatchBlockNode(tryStart, tryEnd, handlerLbl, exType));

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 3 — NESTED MULTI-CATCH
    //
    // Wraps a block under three catch types with separate handler stubs.
    // Produces deeply nested catch trees in decompiler output.
    // ═══════════════════════════════════════════════════════════════════════

    private boolean injectNested(MethodNode method, Block block,
                                 Map<AbstractInsnNode, Frame<BasicValue>> frames) {
        List<AbstractInsnNode> insns = block.getInstructions();
        AbstractInsnNode first = firstReal(insns);
        AbstractInsnNode last  = lastWrappable(insns);
        if (first == null || last == null || first == last) return false;

        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd   = new LabelNode();
        LabelNode realFall = new LabelNode();

        // pick 3 different exception types
        String[] exTypes = pickDistinctExceptions(3);
        LabelNode[] handlerLbls = { new LabelNode(), new LabelNode(), new LabelNode() };

        method.instructions.insertBefore(first, tryStart);
        method.instructions.insert(last, realFall);
        method.instructions.insert(last, tryEnd);

        // Each handler: pop exception, dead body, GOTO realFall
        for (int i = 0; i < 3; i++) {
            HandlerStyle hs = HandlerStyle.values()[i % HandlerStyle.values().length];
            InsnList h = new InsnList();
            h.add(handlerLbls[i]);
            h.add(new InsnNode(POP));
            h.add(buildDeadBody(hs, method));
            h.add(new JumpInsnNode(GOTO, realFall));
            method.instructions.add(h);

            // insert outermost first so nesting is innermost→outermost
            method.tryCatchBlocks.add(0,
                    new TryCatchBlockNode(tryStart, tryEnd, handlerLbls[i], exTypes[i]));
        }

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dead handler body builders
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds a dead-code body for a handler that will never execute at runtime.
     * All styles are type-safe (the JVM verifier accepts them).
     */
    private InsnList buildDeadBody(HandlerStyle style, MethodNode method) {
        return switch (style) {
            case THROW_NEW    -> buildThrowNew();
            case INFINITE_LOOP-> buildInfiniteLoop();
            case HASH_CHAIN   -> buildHashChain(method);
        };
    }

    /**
     * A — THROW_NEW
     * new SomeException("message"); ATHROW
     * The simplest terminating dead body. The JVM verifier is happy because
     * ATHROW is a valid terminator for any handler block type.
     */
    private InsnList buildThrowNew() {
        InsnList il = new InsnList();
        String exType = pickException();
        String msg    = RUNTIME_MESSAGES[rand.nextInt(RUNTIME_MESSAGES.length)];

        il.add(new TypeInsnNode(NEW, exType));
        il.add(new InsnNode(DUP));
        il.add(new LdcInsnNode(msg));
        il.add(new MethodInsnNode(INVOKESPECIAL, exType, "<init>",
                "(Ljava/lang/String;)V", false));
        il.add(new InsnNode(ATHROW));
        return il;
    }

    /**
     * C — INFINITE_LOOP
     * A label that GOTOs itself — unreachable infinite loop.
     * Decompilers render this as while(true){} inside an unreachable catch.
     * The JVM verifier accepts it because the block has no live successors.
     */
    private InsnList buildInfiniteLoop() {
        InsnList il = new InsnList();
        LabelNode self = new LabelNode();
        il.add(self);
        il.add(new JumpInsnNode(GOTO, self));
        return il;
    }

    /**
     * D — HASH_CHAIN
     * A realistic-looking computation: load several string constants, call
     * hashCode() on each, XOR them together, POP the result, ATHROW.
     * Decompilers render this as plausible-looking logic inside the catch.
     * Terminated with ATHROW of a new exception so the block has a valid
     * type-safe exit.
     */
    private InsnList buildHashChain(MethodNode method) {
        InsnList il = new InsnList();

        // 2-4 string hash operations chained together
        int chainLen = 2 + rand.nextInt(3);
        for (int i = 0; i < chainLen; i++) {
            il.add(new LdcInsnNode(randomString()));
            il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String",
                    "hashCode", "()I", false));
            if (i > 0) il.add(new InsnNode(IXOR));
        }
        il.add(new InsnNode(POP));

        // optional: store a random int into a fresh local (more realistic)
        int local = method.maxLocals++;
        il.add(BytecodeUtil.makeInteger(rand.nextInt()));
        il.add(new VarInsnNode(ISTORE, local));

        // terminate: throw new RuntimeException
        il.add(new TypeInsnNode(NEW, "java/lang/RuntimeException"));
        il.add(new InsnNode(DUP));
        il.add(new LdcInsnNode("Unreachable"));
        il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(Ljava/lang/String;)V", false));
        il.add(new InsnNode(ATHROW));

        return il;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** First non-pseudo instruction in a block's instruction list. */
    private AbstractInsnNode firstReal(List<AbstractInsnNode> insns) {
        if (insns == null) return null;
        for (AbstractInsnNode i : insns)
            if (!(i instanceof LabelNode)
                    && !(i instanceof LineNumberNode)
                    && !(i instanceof FrameNode))
                return i;
        return null;
    }

    /**
     * Last instruction we are allowed to include in a try region.
     * We stop before any return/throw to avoid wrapping the method's own
     * exit points — that would require adjusting their successor frames.
     */
    private AbstractInsnNode lastWrappable(List<AbstractInsnNode> insns) {
        if (insns == null || insns.isEmpty()) return null;
        AbstractInsnNode result = null;
        for (AbstractInsnNode i : insns) {
            if (i instanceof LabelNode || i instanceof LineNumberNode || i instanceof FrameNode)
                continue;
            int op = i.getOpcode();
            if (op == ATHROW || BytecodeUtil.isReturning(i)) break;
            result = i;
        }
        return result;
    }

    /**
     * Finds the index (in the instruction list) of the first instruction
     * AFTER the super() or this() call in an <init> method.
     * Returns 0 for all other methods (no restriction).
     */
    private int findSuperCallIndex(MethodNode method) {
        if (!method.name.equals("<init>")) return 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode m) {
                if (m.getOpcode() == INVOKESPECIAL && m.name.equals("<init>")) {
                    // first insn AFTER the super call
                    AbstractInsnNode next = insn.getNext();
                    return next != null ? next.index : Integer.MAX_VALUE;
                }
            }
        }
        return Integer.MAX_VALUE; // no super call found — skip entire method
    }

    private String pickException() {
        return EXCEPTION_TYPES[rand.nextInt(EXCEPTION_TYPES.length)];
    }

    private String[] pickDistinctExceptions(int n) {
        List<String> pool = new ArrayList<>(Arrays.asList(EXCEPTION_TYPES));
        Collections.shuffle(pool, rand);
        return pool.subList(0, Math.min(n, pool.size())).toArray(new String[0]);
    }

    private HandlerStyle pickHandlerStyle() {
        HandlerStyle[] styles = HandlerStyle.values();
        return styles[rand.nextInt(styles.length)];
    }

    /** Random-looking string constant for hash chain bodies. */
    private String randomString() {
        int len = 4 + rand.nextInt(12);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append((char) ('a' + rand.nextInt(26)));
        return sb.toString();
    }
}
