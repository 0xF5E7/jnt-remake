package war.metaphor.mutator.split;

import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.annotate.Warning;
import war.jnt.dash.Ansi;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;

import java.util.Collections;

/**
 * Shuffle Mutator
 *
 * Randomly reorders fields and methods within each class. This disrupts
 * decompilers and analysis tools that rely on member declaration order
 * (e.g. diff-based comparisons, sequential field/method indexing heuristics).
 *
 * WARNING: Can break code that uses reflection to access members by index
 * (e.g. getDeclaredFields()[0]). If your target JAR does this, exempt those
 * classes in config.yml.
 *
 * Register in Metaphor.java (e.g. early in the pipeline, before renaming):
 *   .mutator("member-shuffle", ShuffleMutator.class)
 *
 * config.yml:
 *   mutators:
 *     metaphor:
 *       order:
 *         - member-shuffle
 *       transformers:
 *         member-shuffle:
 *           enabled: true
 *           fields: true    # shuffle fields  (default: true)
 *           methods: true   # shuffle methods (default: true)
 */
@Stability(Level.MEDIUM)
@Warning("MemberShuffleTransformer may break reflection that relies on member declaration order (e.g. getDeclaredFields()[0]). Exempt affected classes if needed.")
public class MemberShuffleTransformer extends Mutator {

    private final boolean shuffleFields;
    private final boolean shuffleMethods;

    private final Logger logger = Logger.INSTANCE;

    public MemberShuffleTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.shuffleFields  = config == null || config.getBoolean("fields",  true);
        this.shuffleMethods = config == null || config.getBoolean("methods", true);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int fieldCount  = 0;
        int methodCount = 0;

        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;

            if (shuffleFields && !classNode.fields.isEmpty()) {
                Collections.shuffle(classNode.fields, rand);
                fieldCount += classNode.fields.size();
            }

            if (shuffleMethods && !classNode.methods.isEmpty()) {
                Collections.shuffle(classNode.methods, rand);
                methodCount += classNode.methods.size();
            }
        }

        logger.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                String.format("ShuffleMutator: shuffled %s fields and %s methods across all classes",
                        new Ansi().c(Ansi.Color.WHITE).s(String.valueOf(fieldCount)),
                        new Ansi().c(Ansi.Color.WHITE).s(String.valueOf(methodCount))));
    }
}

