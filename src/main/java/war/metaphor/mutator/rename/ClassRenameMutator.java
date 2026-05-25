package war.metaphor.mutator.rename;

import war.configuration.ConfigurationSection;
import war.jar.JarResource;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.utility.mapping.Mapping;
import war.jnt.utility.mapping.impl.ClassIdentity;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.MappingMutator;
import war.metaphor.resources.FabricModResource;
import war.metaphor.resources.IResource;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@Stability(Level.HIGH)
public class ClassRenameMutator extends MappingMutator {

    private final Dictionary.Mode mode;
    private final String          prefix;
    private final int             length;

    public ClassRenameMutator(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.mode   = Dictionary.Mode.of(config == null ? null : config.getString("dictionary", "random"));
        this.prefix = config == null ? "" : config.getString("prefix", "");
        this.length = config == null ? 2  : config.getInt("length", 2);
    }

    @Override
    public void run(ObfuscatorContext base) {
        Map<String, String> mapping = new HashMap<>();

        List<JClassNode> classesList = new ArrayList<>(base.getClasses());
        Collections.shuffle(classesList);

        for (JClassNode classNode : classesList) {
            if (classNode.isExempt()) continue;

            String newName = Dictionary.gen(length, Purpose.CLASS, mode, prefix);

            String oldName = classNode.name;

            mapping.put(oldName, newName);

            base.getRepository().add(new Mapping(
                    new ClassIdentity(oldName),
                    new ClassIdentity(newName)
            ));
        }

        map(base, mapping);

        // Store the rename map on the context so JClassNode.compute() can
        // re-apply it inside fallback bytes (references in method bodies).
        base.getClassRenameMap().putAll(mapping);

        Manifest manifest = base.getManifest();
        if (manifest != null) {
            Attributes attrs = manifest.getMainAttributes();
            attrs.replaceAll((_, val) -> {
                if (val instanceof String strVal) {
                    // Manifest Main-Class uses dot-notation (e.g. me.exeos.jnicx.Main).
                    // Our mapping keys use slash-notation (e.g. me/exeos/jnicx/Main).
                    // Normalise to slashes for the lookup, then convert the result back to dots.
                    String slashForm = strVal.replace('.', '/');
                    String renamed   = mapping.get(slashForm);
                    if (renamed != null) {
                        // Convert the new slash-form name back to dot-notation for the manifest.
                        return renamed.replace('/', '.');
                    }
                }
                return val;
            });
        }

        Map<String, IResource> resourceHandlers = new HashMap<>();
        resourceHandlers.put("fabric.mod.json", new FabricModResource());

        for (JarResource resource : base.getResources()) {
            if (resourceHandlers.containsKey(resource.name())) {
                IResource handler = resourceHandlers.get(resource.name());
                String contents = new String(resource.content());
                resource.setContent(handler.handle(contents, mapping).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
