package dev.morphismx.retrodfu.transformer;

import com.google.common.base.Supplier;
import dev.morphismx.retrodfu.guava.GuavaCompat;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects {@code MapCodec$RecursiveMapCodec}'s {@code Suppliers.memoize} call
 * onto the compat implementation. The handler is an instance method because
 * {@code <init>} is.
 */
@CTransformer(name = "com.mojang.serialization.MapCodec$RecursiveMapCodec")
abstract class RecursiveMapCodecTransformer {

    @CRedirect(method = "<init>", target = @CTarget(value = "INVOKE",
            target = Targets.MEMOIZE))
    Supplier<Object> memoize(Supplier<Object> supplier) {
        return GuavaCompat.memoize(supplier);
    }
}
