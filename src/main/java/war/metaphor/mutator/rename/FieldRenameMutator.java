package war.metaphor.mutator.rename;

import org.objectweb.asm.tree.FieldNode;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.utility.mapping.Mapping;
import war.jnt.utility.mapping.impl.MemberIdentity;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.MappingMutator;
import war.metaphor.tree.ClassField;
import war.metaphor.tree.Hierarchy;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/*
TODO:   Could be faster, I dont think we need very STRICT hierarchy checks for fields, but I did it just in case
*/
@Stability(Level.HIGH)
public class FieldRenameMutator extends MappingMutator {

    private final Dictionary.Mode mode;
    private final String          prefix;
    private final int             length;

    public FieldRenameMutator(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.mode   = Dictionary.Mode.of(config == null ? null : config.getString("dictionary", "random"));
        this.prefix = config == null ? "" : config.getString("prefix", "");
        this.length = config == null ? 1  : config.getInt("length", 1);
    }

    @Override
    public void run(ObfuscatorContext base) {
        Map<String, String> mapping = new HashMap<>();

        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;

            Set<JClassNode> classTree = Hierarchy.INSTANCE.getClassHierarchy(classNode);
            classTree.add(classNode);

            for (FieldNode field : classNode.fields) {
                if (classNode.isExempt(field)) continue;

                ClassField self = ClassField.of(classNode, field);
                if (mapping.containsKey(self.toString())) continue;

                Set<ClassField> fieldTree = Hierarchy.INSTANCE.getFieldHierarchy(self);
                if (!canRenameField(fieldTree)) continue;

                String newName = null;

                for (JClassNode node : classTree) {
                    String id = node.name + "." + field.name + field.desc;
                    if (mapping.containsKey(id)) {
                        newName = mapping.get(id);
                    }
                }

                if (newName == null) {
                    newName = Dictionary.gen(length, Purpose.FIELD, mode, prefix);
                }

                for (JClassNode node : classTree) {
                    String id = node.name + "." + field.name + field.desc;
                    mapping.put(id, newName);
                }

                base.getRepository().add(new Mapping(
                        new MemberIdentity(".f_same " + classNode.name, field.name, ""),
                        new MemberIdentity(classNode.name, newName, "")
                ));
            }
        }

        map(base, mapping);
    }
}
