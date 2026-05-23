package war.jnt.core.vm;

import war.jnt.core.vm.math.IMath;
import war.jnt.core.vm.math.impl.ParityBasedMath;
import war.jnt.core.vm.math.impl.SwitchBasedMath;
import war.metaphor.util.Chance;
import war.metaphor.util.interfaces.IRandom;

import java.util.*;

public class TempJumpVM implements IRandom {
    private final Map<EnumVMOperation, ArrayList<IMath>> opToMathMap = new HashMap<>();
    private final StringBuilder builder;
    public final Map<EnumVMOperation, Integer> opToIntMap = new HashMap<>();
    public int vmLabelCount = 0;

    private final boolean enabled;

    public TempJumpVM(StringBuilder builder, boolean vm, int chance) {
        this.builder = builder;
        this.enabled = vm;

        if (!enabled) return;

        for (EnumVMOperation value : EnumVMOperation.values()) {
            int v;
            do {
                v = RANDOM.nextInt(255);
            } while (opToIntMap.containsValue(v));
            opToIntMap.put(value, v);

            ArrayList<IMath> math = new ArrayList<>();
            if (Chance.chance(chance)) {
                for (int i = 0; i < RANDOM.nextInt(5) + 5; i++) {
                    math.add(switch (RANDOM.nextInt(2)) {
                        case 0 -> new ParityBasedMath();
                        case 1 -> new SwitchBasedMath();
                        default -> throw new IllegalStateException("Unexpected value");
                    });
                }
            }
            opToMathMap.put(value, math);
        }
    }

    public void insertSetupCode() {
        builder.append("\t/* this is for the native vm */\n");

        ArrayList<String> yes = new ArrayList<>(List.of(
                "\tjlong output = 0;\n",
                "\tjlong arg1 = 0;\n",
                "\tjlong arg2 = 0;\n",
                "\tunsigned char mode = 0;\n",
                "\tint id = 0;\n"
        ));
        Collections.shuffle(yes);
        for (String s : yes) builder.append(s);
    }

    // ── int operation dispatch ────────────────────────────────────────────────

    public String getCode(String computedA, String computedB, String computedPush, EnumVMOperation op) {
        if (!enabled) {
            return String.format("""
                    \targ1 = %s.i;
                    \targ2 = %s.i;
                    \tasm volatile ("" ::: "memory");
                    %s
                    \t%s.i = output;
                    """, computedB, computedA, getMathCode(op).replace("\t\t\t", "\t"), computedPush);
        }

        String output = String.format("""
                        \t/* vm call (%s) */
                        \tmode = %d;
                        \targ1 = %s.i;
                        \targ2 = %s.i;
                        \tid = %d;
                        \tgoto vmout;
                        id%d:
                        %s
                        """, op.name(), opToIntMap.get(op), computedB, computedA,
                vmLabelCount, vmLabelCount, getReversedMath(computedPush + ".i", op));

        vmLabelCount++;
        return output;
    }

    // ── long operation dispatch ───────────────────────────────────────────────

    /**
     * Like {@link #getCode} but loads/stores the {@code .j} (jlong) field
     * instead of {@code .i} (jint).
     */
    public String getLongCode(String computedA, String computedB, String computedPush, EnumVMOperation op) {
        if (!enabled) {
            return String.format("""
                    \targ1 = %s.j;
                    \targ2 = %s.j;
                    \tasm volatile ("" ::: "memory");
                    %s
                    \t%s.j = output;
                    """, computedB, computedA, getMathCode(op).replace("\t\t\t", "\t"), computedPush);
        }

        String output = String.format("""
                        \t/* vm call (%s) */
                        \tmode = %d;
                        \targ1 = %s.j;
                        \targ2 = %s.j;
                        \tid = %d;
                        \tgoto vmout;
                        id%d:
                        %s
                        """, op.name(), opToIntMap.get(op), computedB, computedA,
                vmLabelCount, vmLabelCount, getReversedMath(computedPush + ".j", op));

        vmLabelCount++;
        return output;
    }

    public String getReversedMath(String assignTarget, EnumVMOperation op) {
        StringBuilder sb = new StringBuilder();
        for (IMath iMath : opToMathMap.get(op).reversed()) {
            sb.append(iMath.reverse(1));
        }
        if (assignTarget == null) return sb.toString();
        return String.format("%s\t%s = output;", sb, assignTarget);
    }

    /** @deprecated Use {@link #getReversedMath(String, EnumVMOperation)} */
    @Deprecated
    public String getReversedMath(String computedPush, EnumVMOperation op) {
        if (computedPush == null) return getReversedMath((String) null, op);
        return getReversedMath(computedPush, op);
    }

    public void buildVM() {
        if (!enabled) return;

        builder.append("""
                vmout:
                \tswitch(*(volatile unsigned char *)&mode)
                \t{
                """);

        for (EnumVMOperation value : EnumVMOperation.values()) {
            builder.append(String.format("""
                    \t\tcase %d:
                    \t\t\tasm volatile ("" ::: "memory");
                    %s
                    %s
                    \t\t\tbreak;
                    """, opToIntMap.get(value), getMathCode(value), getComputation(value)));
        }

        builder.append("\t}\n");

        builder.append("""
                \tswitch(*(volatile int *)&id)
                \t{
                """);

        for (int i = 0; i < vmLabelCount; i++) {
            builder.append(String.format("""
                \t\tcase %d:
                \t\t\tgoto id%d;
                """, i, i));
        }

        builder.append("""
                \t\tdefault:
                \t\t\tgoto vmout;
                \t}
                """);
    }

    private String getComputation(EnumVMOperation value) {
        StringBuilder sb = new StringBuilder();
        for (IMath iMath : opToMathMap.get(value)) {
            sb.append(iMath.compute(3));
        }
        return sb.toString();
    }

    public String getMathCode(EnumVMOperation value) {
        return switch (value) {
            // ── int (32-bit) SSE2 operations ─────────────────────────────────
            case ADD -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_add_epi32(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case SUBTRACT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_sub_epi32(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case MULTIPLY -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_mullo_epi32(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case DIVIDE -> """
            \t\t\t{
            \t\t\t\tjint a = *(volatile jint *)&arg1;
            \t\t\t\tjint b = *(volatile jint *)&arg2;
            \t\t\t\tif (b == 0) output = 0;
            \t\t\t\telse if (a == 0x80000000 && b == -1) output = a;
            \t\t\t\telse output = a / b;
            \t\t\t}
            """;
            case REMAINDER -> """
            \t\t\t{
            \t\t\t\tjint a = *(volatile jint *)&arg1;
            \t\t\t\tjint b = *(volatile jint *)&arg2;
            \t\t\t\tif (b == 0) output = 0;
            \t\t\t\telse if (a == 0x80000000 && b == -1) output = 0;
            \t\t\t\telse output = a % b;
            \t\t\t}
            """;
            case SHIFT_LEFT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i sc = _mm_cvtsi32_si128(*(volatile jint *)&arg2 & 31);
            \t\t\t\t__m128i t1 = _mm_sll_epi32(va, sc);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case SHIFT_RIGHT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i sc = _mm_cvtsi32_si128(*(volatile jint *)&arg2 & 31);
            \t\t\t\t__m128i t1 = _mm_sra_epi32(va, sc);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case USHIFT_RIGHT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i sc = _mm_cvtsi32_si128(*(volatile jint *)&arg2 & 31);
            \t\t\t\t__m128i t1 = _mm_srl_epi32(va, sc);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case AND -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_and_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case OR -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_or_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;
            case XOR -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set1_epi32((jint)(*(volatile juint *)&arg1));
            \t\t\t\t__m128i vb = _mm_set1_epi32((jint)(*(volatile juint *)&arg2));
            \t\t\t\t__m128i t1 = _mm_xor_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si32(t1);
            \t\t\t}
            """;

            // ── long (64-bit) operations ──────────────────────────────────────
            // SSE2 is used where available; plain C is used for divide/shift-right
            // (no portable 64-bit arithmetic-shift or divide intrinsic in SSE2).
            case LADD -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i vb = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg2));
            \t\t\t\t__m128i t1 = _mm_add_epi64(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LSUBTRACT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i vb = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg2));
            \t\t\t\t__m128i t1 = _mm_sub_epi64(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LMULTIPLY -> """
            \t\t\t{
            \t\t\t\t/* No portable SSE2 64-bit multiply; use plain C */
            \t\t\t\tjlong a = *(volatile jlong *)&arg1;
            \t\t\t\tjlong b = *(volatile jlong *)&arg2;
            \t\t\t\toutput = (jlong)((julong)a * (julong)b);
            \t\t\t}
            """;
            case LDIVIDE -> """
            \t\t\t{
            \t\t\t\tjlong a = *(volatile jlong *)&arg1;
            \t\t\t\tjlong b = *(volatile jlong *)&arg2;
            \t\t\t\tif (b == 0) output = 0;
            \t\t\t\telse if (a == (jlong)0x8000000000000000LL && b == -1LL) output = a;
            \t\t\t\telse output = a / b;
            \t\t\t}
            """;
            case LREMAINDER -> """
            \t\t\t{
            \t\t\t\tjlong a = *(volatile jlong *)&arg1;
            \t\t\t\tjlong b = *(volatile jlong *)&arg2;
            \t\t\t\tif (b == 0) output = 0;
            \t\t\t\telse if (a == (jlong)0x8000000000000000LL && b == -1LL) output = 0;
            \t\t\t\telse output = a % b;
            \t\t\t}
            """;
            case LXOR -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i vb = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg2));
            \t\t\t\t__m128i t1 = _mm_xor_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LAND -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i vb = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg2));
            \t\t\t\t__m128i t1 = _mm_and_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LOR -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i vb = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg2));
            \t\t\t\t__m128i t1 = _mm_or_si128(va, vb);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LSHIFT_LEFT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i sc = _mm_cvtsi32_si128(*(volatile jint *)&arg2 & 63);
            \t\t\t\t__m128i t1 = _mm_sll_epi64(va, sc);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
            case LSHIFT_RIGHT -> """
            \t\t\t{
            \t\t\t\t/* Arithmetic right shift: SSE2 _mm_sra_epi64 not available; use C */
            \t\t\t\tjlong a = *(volatile jlong *)&arg1;
            \t\t\t\tjint  s = *(volatile jint  *)&arg2 & 63;
            \t\t\t\toutput  = a >> s;
            \t\t\t}
            """;
            case LUSHIFT_RIGHT -> """
            \t\t\t{
            \t\t\t\t__m128i va = _mm_set_epi64x(0, (jlong)(*(volatile julong *)&arg1));
            \t\t\t\t__m128i sc = _mm_cvtsi32_si128(*(volatile jint *)&arg2 & 63);
            \t\t\t\t__m128i t1 = _mm_srl_epi64(va, sc);
            \t\t\t\toutput = _mm_cvtsi128_si64(t1);
            \t\t\t}
            """;
        };
    }

    public void makeValue(int orig) {
        if (!enabled) {
            builder.append("\t/* direct assignment */\n");
            builder.append("\toutput = ").append(orig).append(";\n");
            return;
        }

        int val1 = RANDOM.nextInt(200);
        int val2;
        EnumVMOperation op;

        if (val1 > orig) {
            op   = EnumVMOperation.SUBTRACT;
            val2 = val1 - orig;
        } else if (val1 < orig) {
            op   = EnumVMOperation.ADD;
            val2 = orig - val1;
        } else {
            op   = EnumVMOperation.MULTIPLY;
            val2 = 1;
        }

        builder.append(String.format("""
                        \t/* vm call (%s) */
                        \tmode = %d;
                        \targ1 = %s;
                        \targ2 = %s;
                        \tid = %d;
                        \tgoto vmout;
                        id%d:
                        %s
                        """, op.name(), opToIntMap.get(op), val1, val2,
                vmLabelCount, vmLabelCount, getReversedMath((String) null, op)));

        vmLabelCount++;
    }
}
