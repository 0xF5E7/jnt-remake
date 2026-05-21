package war.metaphor.util;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;
import java.util.*;

@UtilityClass
public class Dictionary {

    private final String STRICT_CHARS = "abcdefghijklmnopqrstuvwxyz";

    private final SecureRandom rand = new SecureRandom();

    private final Set<String> usedClass = new HashSet<>();
    private final Set<String> usedField = new HashSet<>();
    private final Set<String> usedMethod = new HashSet<>();
    private final Set<String> usedGeneric = new HashSet<>();

    public String gen(int length, Purpose purpose) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < length; i++) {
            int idx = rand.nextInt(0, STRICT_CHARS.length());
            sb.append(STRICT_CHARS.charAt(idx));
        }

        if (purpose != null)
        {
            List<String> used = switch (purpose) {
                case CLASS -> usedClass;
                case FIELD -> usedField;
                case METHOD -> usedMethod;
                case GENERIC -> usedGeneric;
            };

            if (used.contains(sb.toString())) {
                return gen(++length, purpose);
            }

            used.add(sb.toString());
        }

        return sb.toString();
    }

    public void addUsed(String s, Purpose purpose) {
        List<String> used = switch (purpose) {
            case CLASS -> usedClass;
            case FIELD -> usedField;
            case METHOD -> usedMethod;
            case GENERIC -> usedGeneric;
        };

        if (used.contains(s)) return;

        used.add(s);
    }
}
