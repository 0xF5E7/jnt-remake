package war.metaphor.mutator.data.strings.poly2.init;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import war.jnt.base64.Base64;
import war.metaphor.mutator.data.strings.poly2.decryptionMethod.DecryptionMethod;
import war.metaphor.mutator.data.strings.poly2.decryptionMethod.args.AbstractDecryptionMethodArgument;
import war.metaphor.util.Pair;
import war.metaphor.util.asm.BytecodeUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.lang.Math;

public class Initializer implements Opcodes
{
    public final InsnList code;

    public Initializer(final DecryptionMethod parent)
    {
        code = new InsnList();

        final ArrayList<byte[]> encrypted = new ArrayList<>();
        for (final Map.Entry<String, Pair<AbstractDecryptionMethodArgument, Object>[]> entry : parent.cachedStrings.entrySet())
        {
            encrypted.add(parent.encrypt(entry));
        }

        int targetLength = 0;
        for (final byte[] sBytes : encrypted)
        {
            targetLength += sBytes.length + 2;
        }

        final byte[] bytes = new byte[targetLength];

        int idx = 0;
        for (final byte[] sBytes : encrypted)
        {
            final int len = sBytes.length ^ parent.initXorKey;
            bytes[idx++] = (byte) (len >> 8);
            bytes[idx++] = (byte) (len & 0xFF);
            for (final byte sByte : sBytes)
            {
                bytes[idx++] = sByte;
            }
        }

        final String encoded = new String(Base64.encode(bytes));
        final int CHUNK = 32767;
        if (encoded.length() <= CHUNK) {
            // Fast path: fits in one constant.
            code.add(new LdcInsnNode(encoded));
        } else {
            // Slow path: build via StringBuilder.
            final String SB  = "java/lang/StringBuilder";
            final String SBD = "Ljava/lang/StringBuilder;";
            code.add(new TypeInsnNode(NEW, SB));
            code.add(new InsnNode(DUP));
            code.add(new MethodInsnNode(INVOKESPECIAL, SB, "<init>", "()V", false));
            for (int start = 0; start < encoded.length(); start += CHUNK) {
                String chunk = encoded.substring(start, Math.min(start + CHUNK, encoded.length()));
                code.add(new LdcInsnNode(chunk));
                code.add(new MethodInsnNode(INVOKEVIRTUAL, SB, "append",
                        "(Ljava/lang/String;)" + SBD, false));
            }
            code.add(new MethodInsnNode(INVOKEVIRTUAL, SB, "toString",
                    "()Ljava/lang/String;", false));
        }
        code.add(new FieldInsnNode(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B"));
        code.add(new MethodInsnNode(INVOKESTATIC, parent.libPath + "/base64/Base64", "decode", "([B)[B"));
        code.add(new FieldInsnNode(PUTSTATIC, parent.parent.name, parent.initField.name, parent.initField.desc));
        code.add(BytecodeUtil.makeInteger(parent.cachedStrings.size()));
        code.add(new TypeInsnNode(ANEWARRAY, "[B"));
        code.add(new FieldInsnNode(PUTSTATIC, parent.parent.name, parent.cacheField.name, parent.cacheField.desc));
    }
}
