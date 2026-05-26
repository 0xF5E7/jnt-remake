package war.metaphor.mutator.rename;

import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * LocalVariableRenameTransformer
 *
 * <p>Renames every entry in the Local Variable Table (LVT) of every eligible
 * method.  The LVT is a debug-only structure that maps bytecode slot indices
 * to human-readable names seen in decompiled output — renaming it makes
 * decompiled code significantly harder to read without touching any actual
 * bytecode instructions.
 *
 * <p>This mutator is intentionally lightweight: it only writes to
 * {@link LocalVariableNode#name} fields and never reorders, adds, or removes
 * instructions.  It is therefore safe to run at any point in the pipeline,
 * though it should be placed <em>before</em> the {@code strip} mutator
 * (which nulls {@code method.localVariables} entirely) if you want the
 * renamed table to survive into the output JAR.
 *
 * <h3>Slot-stable renaming</h3>
 * The JVM allows a single slot index to have multiple LVT entries with
 * non-overlapping live ranges (e.g. a loop variable reused across iterations,
 * or two different variables sharing a slot after the first goes out of
 * scope).  This mutator memoises the generated name per slot per method so
 * every LVT entry that maps to the same slot receives the same new name,
 * exactly matching how a decompiler reconstructs local variable names.
 *
 * <h3>Config</h3>
 * <pre>
 * renamer.local:
 *   enabled:    true
 *   dictionary: unicode   # same modes as class/method/field renamers
 *   length:     5         # base character count for the generated name
 *   prefix:     ""        # optional fixed prefix (e.g. "lv_")
 *   skip-this:  true      # keep the implicit 'this' parameter readable
 * </pre>
 *
 * <h3>Placement recommendation</h3>
 * <pre>
 * order:
 *   - renamer.class
 *   - renamer.method
 *   - renamer.field
 *   - renamer.local   ← here, before strip
 *   - strip
 * </pre>
 */
@Stability(Level.VERY_HIGH)
public class LocalVariableRenameTransformer extends Mutator {

    private static final Logger logger = Logger.INSTANCE;

    private final Dictionary.Mode mode;
    private final String prefix;
    private final int length;
    private final boolean skipThis;

    // ── constructor ───────────────────────────────────────────────────────────

    public LocalVariableRenameTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.mode     = Dictionary.Mode.of(config == null ? null : config.getString("dictionary", "random"));
        this.prefix   = config == null ? "" : config.getString("prefix", "");
        this.length   = config == null ? 5 : config.getInt("length", 5);
        this.skipThis = config == null || config.getBoolean("skip-this", true);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int renamedSlots   = 0;  // unique slot→name mappings created
        int renamedEntries = 0;  // total LVT entries written (≥ renamedSlots)
        int skippedMethods = 0;

        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method))           { skippedMethods++; continue; }
                if (Modifier.isAbstract(method.access))   { skippedMethods++; continue; }
                if (Modifier.isNative(method.access))     { skippedMethods++; continue; }
                if (method.localVariables == null
                        || method.localVariables.isEmpty()) { skippedMethods++; continue; }
                if (method.signature != null
                        && method.signature.equals("bsm::jnt:excluded")) {
                    skippedMethods++;
                    continue;
                }

                boolean isStatic = Modifier.isStatic(method.access);
                Map<Integer, String> slotNames = new HashMap<>();

                for (LocalVariableNode lv : method.localVariables) {
                    if (skipThis && !isStatic && lv.index == 0) continue;

                    if (!slotNames.containsKey(lv.index)) {
                        String newName = Dictionary.gen(length, Purpose.GENERIC, mode, prefix);
                        slotNames.put(lv.index, newName);
                        renamedSlots++;
                    }

                    lv.name = slotNames.get(lv.index);
                    renamedEntries++;
                }
            }
        }

        logger.logln(
                war.jnt.dash.Level.INFO,
                Origin.METAPHOR,
                String.format(
                        "LocalVariableRenamer: renamed %d slots across %d LVT entries (%d methods skipped)",
                        renamedSlots, renamedEntries, skippedMethods
                )
        );
    }
}
