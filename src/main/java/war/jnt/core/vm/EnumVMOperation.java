package war.jnt.core.vm;

public enum EnumVMOperation {
    // ── int (32-bit) operations ───────────────────────────────────────────────
    ADD,
    SUBTRACT,
    DIVIDE,
    MULTIPLY,
    SHIFT_LEFT,
    REMAINDER,
    XOR,
    SHIFT_RIGHT,
    AND,
    OR,
    USHIFT_RIGHT,

    // ── long (64-bit) operations ──────────────────────────────────────────────
    LADD,
    LSUBTRACT,
    LMULTIPLY,
    LDIVIDE,
    LREMAINDER,
    LXOR,
    LAND,
    LOR,
    LSHIFT_LEFT,
    LSHIFT_RIGHT,
    LUSHIFT_RIGHT
}
