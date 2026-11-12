package net.morphismmc.retrodfu;

import com.google.common.base.Supplier;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.guava.GuavaCompat;

/**
 * Redirects {@code Codec$RecursiveCodec}'s {@code Suppliers.memoize} call onto
 * the compat implementation — an instance method because {@code <init>} is;
 * it runs after the super constructor, so {@code this} is initialized.
 */
@CTransformer(name = "com.mojang.serialization.Codec$RecursiveCodec")
abstract class RecursiveCodecTransformer {

    @CRedirect(method = "<init>", target = @CTarget(value = "INVOKE", target = Targets.MEMOIZE))
    Supplier<Object> memoize(Supplier<Object> supplier) {
        return GuavaCompat.memoize(supplier);
    }
}
