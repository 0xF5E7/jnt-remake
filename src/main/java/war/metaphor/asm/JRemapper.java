package war.metaphor.asm;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import java.util.Map;

public class JRemapper extends Remapper {

    private final Map<String, String> mapping;

    public JRemapper(Map<String, String> mapping) {
        this.mapping = mapping;
    }

    @Override
    public String mapMethodDesc(String methodDescriptor) {
        try {
            return super.mapMethodDesc(methodDescriptor);
        } catch (Exception e) {
            return methodDescriptor;
        }
    }

    @Override
    public String mapSignature(String signature, boolean typeSignature) {
        try {
            return super.mapSignature(signature, typeSignature);
        } catch (Exception e) {
            return signature;
        }
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        String remappedName = this.map(owner + '.' + name + descriptor);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapAnnotationAttributeName(String descriptor, String name) {
        descriptor = Type.getType(descriptor).getInternalName();
        String remappedName = this.softMap(descriptor + '.' + name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        String remappedName = this.map(owner + '.' + name + descriptor);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String map(String key) {
        String result = this.mapping.get(key);
        if (result != null) return result;

        // Fallback: handle cases where package path may differ or be partially stripped
        for (Map.Entry<String, String> entry : this.mapping.entrySet()) {
            if (entry.getKey().equals(key) || entry.getKey().endsWith("/" + key)) {
                return entry.getValue();
            }
        }

        return null;
    }

    @Override
    public String mapDesc(String descriptor) {
        try {
            return super.mapDesc(descriptor);
        } catch (Exception e) {
            return descriptor;
        }
    }

    private String softMap(String s) {
        for (String s1 : this.mapping.keySet()) {
            if (s1.contains(s)) {
                return this.mapping.get(s1);
            }
        }
        return null;
    }
}
