package war.jnt.core.code.impl;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import war.jnt.cache.Cache;
import war.jnt.core.code.UnitContext;
import war.jnt.core.vm.EnumVMOperation;
import war.jnt.core.vm.TempJumpVM;
import war.jnt.fusebox.impl.Internal;
import war.jnt.innercache.InnerCache;

public class ArithmeticUnit implements Opcodes {
    public static void process(final InnerCache ic, InsnNode insn, UnitContext ctx, TempJumpVM tjvm) {
        String ae = ic.FindClass("java/lang/ArithmeticException");

        // ── Division-by-zero guards ───────────────────────────────────────────
        switch (insn.getOpcode()) {
            case IREM, IDIV -> ctx.fmtAppend(
                    "\tif (stack[%s].i == 0) { (*env)->ThrowNew(env, %s, \"integer division by zero\"); goto %s; }\n",
                    ctx.getTracker().dump(), ae, ctx.handlerLabel);
            case LREM, LDIV -> ctx.fmtAppend(
                    "\tif (stack[%s].j == 0) { (*env)->ThrowNew(env, %s, \"long division by zero\"); goto %s; }\n",
                    ctx.getTracker().dump(), ae, ctx.handlerLabel);
        }

        switch (insn.getOpcode()) {

            // ── int (32-bit) — routed through the VM ─────────────────────────
            case IADD -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.ADD));
            }
            case ISUB -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.SUBTRACT));
            }
            case IMUL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.MULTIPLY));
            }
            case IDIV -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.DIVIDE));
            }
            case IREM -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.REMAINDER));
            }
            case ISHL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.SHIFT_LEFT));
            }
            case ISHR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.SHIFT_RIGHT));
            }
            case IUSHR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.USHIFT_RIGHT));
            }
            case IAND -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.AND));
            }
            case IOR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.OR));
            }
            case IXOR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getCode(a, b, p, EnumVMOperation.XOR));
            }
            case INEG -> {
                String a = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.i = -%s.i;\n", p, a));
            }

            // ── long (64-bit) — now routed through the VM ────────────────────
            case LADD -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LADD));
            }
            case LSUB -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LSUBTRACT));
            }
            case LMUL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LMULTIPLY));
            }
            case LDIV -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LDIVIDE));
            }
            case LREM -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LREMAINDER));
            }
            case LXOR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LXOR));
            }
            case LAND -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LAND));
            }
            case LOR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LOR));
            }
            case LSHL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LSHIFT_LEFT));
            }
            case LSHR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LSHIFT_RIGHT));
            }
            case LUSHR -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(tjvm.getLongCode(a, b, p, EnumVMOperation.LUSHIFT_RIGHT));
            }
            case LNEG -> {
                String a = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.j = -%s.j;\n", p, a));
            }

            // ── float (32-bit) — no VM, plain C ──────────────────────────────
            case FADD -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.f = %s.f + %s.f;\n", p, b, a));
            }
            case FSUB -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.f = %s.f - %s.f;\n", p, b, a));
            }
            case FMUL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.f = %s.f * %s.f;\n", p, b, a));
            }
            case FDIV -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.f = %s.f / %s.f;\n", p, b, a));
            }
            case FNEG -> {
                String a = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.f = -%s.f;\n", p, a));
            }
            case FREM -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("\t%s.f = fmod(%s.f, %s.f);\n", p, b, a);
            }

            // ── double (64-bit) — no VM, plain C ─────────────────────────────
            case DADD -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.d = %s.d + %s.d;\n", p, b, a));
            }
            case DSUB -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.d = %s.d - %s.d;\n", p, b, a));
            }
            case DMUL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.d = %s.d * %s.d;\n", p, b, a));
            }
            case DDIV -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.d = %s.d / %s.d;\n", p, b, a));
            }
            case DNEG -> {
                String a = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.getBuilder().append(String.format("\t%s.d = -%s.d;\n", p, a));
            }
            case DREM -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("\t%s.d = fmod(%s.d, %s.d);\n", p, b, a);
            }

            // ── comparison opcodes ────────────────────────────────────────────
            case DCMPL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("/* DCMPL */\n");
                ctx.fmtAppend("""
                    \tif (isnan(%s.d) || isnan(%s.d)) {
                    \t\t%s.i = -1;
                    \t} else if (%s.d > %s.d) {
                    \t\t%s.i = 1;
                    \t} else if (%s.d < %s.d) {
                    \t\t%s.i = -1;
                    \t} else {
                    \t\t%s.i = 0;
                    \t}
                    """, a, b, p, b, a, p, b, a, p, p);
            }
            case DCMPG -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("/* DCMPG */\n");
                ctx.fmtAppend("""
                    \tif (isnan(%s.d) || isnan(%s.d)) {
                    \t\t%s.i = 1;
                    \t} else if (%s.d > %s.d) {
                    \t\t%s.i = 1;
                    \t} else if (%s.d < %s.d) {
                    \t\t%s.i = -1;
                    \t} else {
                    \t\t%s.i = 0;
                    \t}
                    """, a, b, p, b, a, p, b, a, p, p);
            }
            case LCMP -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("/* LCMP */\n");
                ctx.fmtAppend("""
                    \t%s.i = (%s.j > %s.j) ? 1
                    \t\t: (%s.j < %s.j) ? -1
                    \t\t: 0;
                    """, p, b, a, b, a);
            }
            case FCMPL -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("/* FCMPL */\n");
                ctx.fmtAppend("""
                    \tif (isnan(%s.f) || isnan(%s.f)) {
                    \t\t%s.i = -1;
                    \t} else if (%s.f > %s.f) {
                    \t\t%s.i = 1;
                    \t} else if (%s.f < %s.f) {
                    \t\t%s.i = -1;
                    \t} else {
                    \t\t%s.i = 0;
                    \t}
                    """, a, b, p, b, a, p, b, a, p, p);
            }
            case FCMPG -> {
                String a = Internal.computePop(ctx.getTracker());
                String b = Internal.computePop(ctx.getTracker());
                String p = Internal.computePush(ctx.getTracker());
                ctx.fmtAppend("/* FCMPG */\n");
                ctx.fmtAppend("""
                    \tif (isnan(%s.f) || isnan(%s.f)) {
                    \t\t%s.i = 1;
                    \t} else if (%s.f > %s.f) {
                    \t\t%s.i = 1;
                    \t} else if (%s.f < %s.f) {
                    \t\t%s.i = -1;
                    \t} else {
                    \t\t%s.i = 0;
                    \t}
                    """, a, b, p, b, a, p, b, a, p, p);
            }
            case ARRAYLENGTH -> {
                String popped = Internal.computePop(ctx.getTracker());
                String pushed  = Internal.computePush(ctx.getTracker());
                final String npe = ic.FindClass("java/lang/NullPointerException");
                ctx.fmtAppend("\tif (%s.l == NULL) { (*env)->ThrowNew(env, %s, \"null array for arraylength\"); goto %s; }\n",
                        popped, npe, ctx.handlerLabel);
                ctx.fmtAppend("""
                        \t%s.i = (*env)->GetArrayLength(env, (jarray) %s.l);
                        """, pushed, popped);
            }
            default -> throw new IllegalStateException("Unexpected value: " + insn.getOpcode());
        }
    }
}
