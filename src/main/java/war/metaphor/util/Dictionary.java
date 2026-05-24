package war.metaphor.util;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;
import java.util.*;

/**
 * Dictionary — centralised name generator for all rename mutators.
 *
 * <h3>Naming modes</h3>
 * Each mode is selected via the {@code dictionary} key in the mutator's
 * config section (e.g. {@code renamer.class.dictionary: illusion}).
 *
 * <table>
 *   <tr><th>Mode</th><th>Example output</th><th>Description</th></tr>
 *   <tr><td>{@code random}   </td><td>{@code xkqbf}           </td><td>Lowercase a–z only (original behaviour, default)</td></tr>
 *   <tr><td>{@code alpha}    </td><td>{@code aB3xZ}           </td><td>Mixed-case letters + digits — maximises search-space per char</td></tr>
 *   <tr><td>{@code illusion} </td><td>{@code lIllIlIlI}       </td><td>Only the chars I, l, 1 — visually indistinguishable in most fonts</td></tr>
 *   <tr><td>{@code unicode}  </td><td>{@code \u0430\u0441\u0441\u0435}          </td><td>Cyrillic lookalikes for Latin letters — valid JVM identifiers</td></tr>
 *   <tr><td>{@code keyword}  </td><td>{@code if$do$}          </td><td>Java keywords mangled with {@code $} — confuses naive decompilers</td></tr>
 *   <tr><td>{@code counter}  </td><td>{@code a_0}, {@code a_1}       </td><td>Deterministic {@code <prefix>_<n>} — reproducible across runs</td></tr>
 * </table>
 *
 * <h3>Config example</h3>
 * <pre>
 * renamer.class:
 *   enabled: true
 *   dictionary: illusion   # mode name — see table above
 *   prefix: ""             # prepended verbatim before the generated name
 *   length: 8              # base length hint (modes may ignore or extend it)
 * </pre>
 *
 * The {@code gen(length, purpose)} overload preserves backward-compatibility
 * and always uses {@code Mode.RANDOM}.
 */
@UtilityClass
public class Dictionary {

    // ── character sets ────────────────────────────────────────────────────────

    private static final String STRICT_CHARS   = "abcdefghijklmnopqrstuvwxyz";
    private static final String ALPHA_CHARS    = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String ILLUSION_CHARS = "IlI1lIl1";   // visually indistinguishable

    /**
     * Cyrillic codepoints that look identical to the corresponding Latin
     * letters in virtually every monospace/programming font.
     * All are valid Java identifier characters (Lu/Ll Unicode categories).
     */
    private static final char[] UNICODE_LOOKALIKES = {
        '\u0430', // а  (looks like a)
        '\u0435', // е  (looks like e)
        '\u0456', // і  (looks like i)
        '\u043E', // о  (looks like o)
        '\u0440', // р  (looks like p)
        '\u0441', // с  (looks like c)
        '\u0445', // х  (looks like x)
        '\u0443', // у  (looks like y)
        '\u0392', // Β  (looks like B)
        '\u0395', // Ε  (looks like E)
        '\u0396', // Ζ  (looks like Z)
        '\u0397', // Η  (looks like H)
        '\u0399', // Ι  (looks like I)
        '\u039A', // Κ  (looks like K)
        '\u039C', // Μ  (looks like M)
        '\u039D', // Ν  (looks like N)
        '\u039F', // Ο  (looks like O)
        '\u03A1', // Ρ  (looks like P)
        '\u03A4', // Τ  (looks like T)
        '\u03A5', // Υ  (looks like Y)
        '\u03A7', // Χ  (looks like X)
    };

    /** Java reserved words — illegal as unmodified identifiers but valid with $ appended. */
    private static final String[] KEYWORDS = {
        "if", "do", "for", "int", "new", "try", "var",
        "byte", "case", "char", "else", "enum", "goto", "long", "null",
        "this", "true", "void", "false", "final", "float", "short", "super",
        "break", "catch", "class", "const", "throw", "while",
        "assert", "double", "import", "native", "return", "static", "switch",
        "throws", "boolean", "default", "extends", "finally", "package",
        "private", "abstract", "continue", "interface", "protected", "public",
        "strictfp", "volatile", "instanceof", "implements", "synchronized",
        "transient"
    };

    // ── RNG ───────────────────────────────────────────────────────────────────

    private static final SecureRandom rand = new SecureRandom();

    // ── used-name registries (one per Purpose, global across the run) ─────────

    private static final Set<String> usedClass   = new HashSet<>();
    private static final Set<String> usedField   = new HashSet<>();
    private static final Set<String> usedMethod  = new HashSet<>();
    private static final Set<String> usedGeneric = new HashSet<>();

    // per-purpose counter for Mode.COUNTER
    private static final Map<Purpose, Long> counters = new EnumMap<>(Purpose.class);

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Naming modes understood by {@link #gen(int, Purpose, Mode, String)}.
     */
    public enum Mode {
        /**
         * Lowercase a–z only.  Identical to the original hard-coded behaviour.
         * Safe for all JVM identifier positions.
         */
        RANDOM,

        /**
         * Mixed-case a–z, A–Z, 0–9 (first char is always a letter so the name
         * remains a legal identifier).  Maximises entropy per character.
         */
        ALPHA,

        /**
         * Uses only the characters {@code I}, {@code l}, and {@code 1}.
         * In most monospace/programming fonts these three glyphs are
         * indistinguishable, making the output very hard to read or copy.
         */
        ILLUSION,

        /**
         * Builds identifiers from Cyrillic and Greek characters that are
         * visually identical to Latin letters in common fonts.
         * All codepoints are valid JVM identifier characters.
         */
        UNICODE,

        /**
         * Concatenates two random Java keywords and appends {@code $} so the
         * result is a legal identifier (e.g. {@code ifdo$}, {@code trynew$}).
         * Confuses decompilers and tools that highlight keyword clashes.
         */
        KEYWORD,

        /**
         * Deterministic counter: {@code <prefix>_0}, {@code <prefix>_1}, …
         * Useful for reproducible builds or debugging.  The {@code prefix}
         * parameter is used verbatim; it defaults to {@code "a"} when blank.
         */
        COUNTER;

        /**
         * Parse a mode name case-insensitively, falling back to {@link #RANDOM}
         * for unknown values.
         */
        public static Mode of(String name) {
            if (name == null || name.isBlank()) return RANDOM;
            return switch (name.trim().toLowerCase()) {
                case "alpha"    -> ALPHA;
                case "illusion" -> ILLUSION;
                case "unicode"  -> UNICODE;
                case "keyword"  -> KEYWORD;
                case "counter"  -> COUNTER;
                default         -> RANDOM;
            };
        }
    }

    // ── generation ────────────────────────────────────────────────────────────

    /**
     * Backward-compatible overload — always uses {@link Mode#RANDOM} with no prefix.
     * All existing callers continue to work without change.
     */
    public String gen(int length, Purpose purpose) {
        return gen(length, purpose, Mode.RANDOM, "");
    }

    /**
     * Generate a unique identifier for the given {@code purpose} using the
     * specified {@code mode}.
     *
     * @param length  base length hint (exact for RANDOM/ALPHA/ILLUSION/UNICODE;
     *                ignored for KEYWORD and COUNTER)
     * @param purpose namespace bucket — prevents collisions between classes,
     *                methods, fields, and generic names
     * @param mode    naming strategy
     * @param prefix  string prepended verbatim before the generated segment
     *                (empty string = no prefix)
     */
    public String gen(int length, Purpose purpose, Mode mode, String prefix) {
        String candidate;
        Set<String> used = usedSetFor(purpose);

        do {
            candidate = prefix + generate(length, purpose, mode, prefix);
        } while (purpose != null && used.contains(candidate));

        if (purpose != null) {
            used.add(candidate);
        }

        return candidate;
    }

    /**
     * Mark a name as already in use for the given purpose so the generator
     * never re-emits it (used when seeding known names from the input JAR).
     */
    public void addUsed(String s, Purpose purpose) {
        usedSetFor(purpose).add(s);
    }

    private String generate(int length, Purpose purpose, Mode mode, String prefix) {
        return switch (mode) {
            case RANDOM    -> randomFrom(STRICT_CHARS,   Math.max(1, length));
            case ALPHA     -> randomAlpha(Math.max(1, length));
            case ILLUSION  -> randomFrom(ILLUSION_CHARS, Math.max(4, length));
            case UNICODE   -> randomUnicode(Math.max(1, length));
            case KEYWORD   -> keywordName();
            case COUNTER   -> counterName(purpose, prefix);
        };
    }

    /** Pick {@code len} random chars from {@code charset}. */
    private String randomFrom(String charset, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(charset.charAt(rand.nextInt(charset.length())));
        }
        return sb.toString();
    }

    private String randomAlpha(int len) {
        StringBuilder sb = new StringBuilder(len);
        // First char: letter only
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        sb.append(letters.charAt(rand.nextInt(letters.length())));
        for (int i = 1; i < len; i++) {
            sb.append(ALPHA_CHARS.charAt(rand.nextInt(ALPHA_CHARS.length())));
        }
        return sb.toString();
    }

    /** Pick {@code len} random Cyrillic/Greek lookalike codepoints. */
    private String randomUnicode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(UNICODE_LOOKALIKES[rand.nextInt(UNICODE_LOOKALIKES.length)]);
        }
        return sb.toString();
    }

    private String keywordName() {
        String a = KEYWORDS[rand.nextInt(KEYWORDS.length)];
        String b = KEYWORDS[rand.nextInt(KEYWORDS.length)];
        return a + b + "$";
    }
    
    private String counterName(Purpose purpose, String prefix) {
        long n = counters.merge(purpose, 0L, (old, ignored) -> old + 1) - 1;
        String p = (prefix == null || prefix.isBlank()) ? "a" : prefix;
        return p + "_" + n;
    }

    private Set<String> usedSetFor(Purpose purpose) {
        if (purpose == null) return new HashSet<>();
        return switch (purpose) {
            case CLASS   -> usedClass;
            case FIELD   -> usedField;
            case METHOD  -> usedMethod;
            case GENERIC -> usedGeneric;
        };
    }
}
