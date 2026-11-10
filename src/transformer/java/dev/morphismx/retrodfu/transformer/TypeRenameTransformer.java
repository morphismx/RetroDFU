package dev.morphismx.retrodfu.transformer;

import net.lenni0451.classtransform.transformer.IBytecodeTransformer;
import net.lenni0451.classtransform.utils.mappings.MapRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.util.Arrays;

/**
 * Whole-type renames applied to every input class ahead of the injection
 * transformers: every slf4j reference becomes a reference to our replacement.
 * Named through the config's {@code bytecodeTransformers} list and
 * instantiated by {@code ClassPatcher}, so unlike the {@code @CTransformer}
 * classes it is a live object that must keep a public constructor.
 *
 * <p>The renames are pure renames — a replacement has the same kind as the
 * original, so opcodes are not rewritten. That stays legal only because our
 * {@code Logger} mirrors slf4j's interface shape.</p>
 */
public final class TypeRenameTransformer implements IBytecodeTransformer {

    private final MapRemapper remapper = new MapRemapper();

    public TypeRenameTransformer() {
        remapper.addClassMapping("org/slf4j/Logger",
                "dev/morphismx/retrodfu/slf4j/Logger");
        remapper.addClassMapping("org/slf4j/LoggerFactory",
                "dev/morphismx/retrodfu/slf4j/LoggerFactory");
    }

    @Override
    public byte[] transform(String className, byte[] bytecode, boolean frames) {
        ClassReader reader = new ClassReader(bytecode);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        byte[] out = writer.toByteArray();
        return Arrays.equals(out, bytecode) ? null : out;
    }
}
