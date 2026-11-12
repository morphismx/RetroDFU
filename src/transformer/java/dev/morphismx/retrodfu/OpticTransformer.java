package dev.morphismx.retrodfu;

import com.google.common.reflect.TypeToken;
import dev.morphismx.retrodfu.guava.GuavaCompat;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects {@code Optic}'s {@code TypeToken.isSupertypeOf} call onto the
 * compat implementation — {@code private static} because
 * {@code lambda$upCast$0} is static and an interface rules out a
 * package-private handler.
 */
@CTransformer(name = "com.mojang.datafixers.optics.Optic")
abstract class OpticTransformer {

    @CRedirect(method = "lambda$upCast$0", target = @CTarget(value = "INVOKE", target = Targets.IS_SUPERTYPE_OF))
    private static boolean isSupertypeOf(TypeToken<Object> type, TypeToken<Object> other) {
        return GuavaCompat.isSupertypeOf(type, other);
    }
}
