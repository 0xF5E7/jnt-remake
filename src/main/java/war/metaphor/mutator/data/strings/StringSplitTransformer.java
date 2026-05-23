package war.metaphor.mutator.data.strings;

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
import java.util.Random;

/**
 * StringSplitMutator
 *
 * Splits string literals into multiple sub-strings and reassembles them at
 * runtime via StringBuilder. A plain LDC "Hello, World!" becomes bytecode
 * equivalent to:
 *
 *   new StringBuilder()
 *       .append("Hel")
 *       .append("lo, ")
 *       .append("Wor")
 *       .append("ld!")
 *       .toString()
 *
 * Each string is cut into [minParts..maxParts] pieces at randomised split
 * points, so every run produces a different split distribution.
 *
 * This defeats any static string search (grep, JADX, Fernflower, etc.) that
 * looks for whole-string LDC constants. Combined with string.light or
 * string.poly the individual fragments are also encrypted.
 *
 * Configuration (config.yml):
 *
 *   string.split:
 *     enabled: true
 *     min-parts: 2        # minimum number of fragments (default 2)
 *     max-parts: 5        # maximum number of fragments (default 5)
 *     min-length: 4       # skip strings shorter than this (default 4)
 *     chance: 100         # % of eligible strings to split (default 100)
 *
 * Registration in Metaphor.java:
 *   .mutator("string.split", StringSplitMutator.class)
 *
 * Recommended order: run BEFORE string.light / string.poly so the individual
 * fragments are subsequently encrypted as well.
 *
 *   order:
 *     - string.split
 *     - string.light
 *
 * @author jnt
 */
@Stability(Level.HIGH)
public class StringSplitTransformer extends Mutator {

    private static final String STRINGBUILDER = "java/lang/StringBuilder";

    private final int minParts;
    private final int maxParts;
    private final int minLength;
    private final int chance;

    public StringSplitTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minParts  = config == null ? 2   : config.getInt("min-parts",  2);
        this.maxParts  = config == null ? 5   : config.getInt("max-parts",  5);
        this.minLength = config == null ? 4   : config.getInt("min-length", 4);
        this.chance    = config == null ? 100 : config.getInt("chance",     100);
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.isInterface()) continue;

            for (MethodNode method : classNode.methods) {
                if (Modifier.isAbstract(method.access)) continue;
                if (classNode.isExempt(method)) continue;

                // Translate invokedynamic string concatenation to plain appends
                // so we see individual LDC nodes rather than indy call sites.
                BytecodeUtil.translateConcatenation(method);

                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    // Bail if the method is getting too large
                    if (BytecodeUtil.leeway(method) < 30000) break;
                    if (!BytecodeUtil.isString(insn)) continue;
                    String str = BytecodeUtil.getString(insn);
                    if (str == null || str.length() < minLength) continue;
                    // JVM constant pool cap: encoded UTF-8 must fit in 65535 bytes.
                    if (str.length() > 65535) continue;
                    // Probabilistic skip
                    if (chance < 100 && rand.nextInt(100) >= chance) continue;
                    List<String> parts = split(str);
                    // Only replace if we actually cut it into more than one piece
                    if (parts.size() < 2) continue;
                    InsnList replacement = buildAppendChain(parts);
                    if (!BytecodeUtil.hasSpace(method, replacement)) continue;
                    method.instructions.insertBefore(insn, replacement);
                    method.instructions.remove(insn);
                }
            }
        }
    }

    private List<String> split(String str) {
        int len = str.length();

        // Clamp target part count to what the string length allows
        int targetParts = minParts + (maxParts > minParts ? rand.nextInt(maxParts - minParts + 1) : 0);
        targetParts = Math.min(targetParts, len); // can't have more parts than chars

        List<String> parts = new ArrayList<>(targetParts);

        if (targetParts <= 1) {
            parts.add(str);
            return parts;
        }

        // Pick (targetParts - 1) unique split points in [1, len-1], then sort
        List<Integer> cuts = new ArrayList<>(targetParts - 1);
        int attempts = 0;
        while (cuts.size() < targetParts - 1 && attempts < 1000) {
            int cut = 1 + rand.nextInt(len - 1);
            if (!cuts.contains(cut)) cuts.add(cut);
            attempts++;
        }
        cuts.sort(Integer::compareTo);

        int prev = 0;
        for (int cut : cuts) {
            parts.add(str.substring(prev, cut));
            prev = cut;
        }
        parts.add(str.substring(prev)); // remainder

        return parts;
    }

    /**
     * Generates bytecode for:
     *
     *   new StringBuilder()
     *       .append(parts[0])
     *       .append(parts[1])
     *       ...
     *       .toString()
     *
     * The first fragment is passed to the StringBuilder constructor to avoid
     * an extra append call and keep code size smaller.
     */
    private InsnList buildAppendChain(List<String> parts) {
        InsnList il = new InsnList();

        // new StringBuilder(parts[0])
        il.add(new TypeInsnNode(NEW, STRINGBUILDER));
        il.add(new InsnNode(DUP));
        il.add(new LdcInsnNode(parts.get(0)));
        il.add(new MethodInsnNode(
                INVOKESPECIAL,
                STRINGBUILDER,
                "<init>",
                "(Ljava/lang/String;)V",
                false
        ));

        // .append(parts[i]) for i = 1..n-1
        for (int i = 1; i < parts.size(); i++) {
            il.add(new LdcInsnNode(parts.get(i)));
            il.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    STRINGBUILDER,
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false
            ));
        }

        // .toString()
        il.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                STRINGBUILDER,
                "toString",
                "()Ljava/lang/String;",
                false
        ));

        return il;
    }
}
