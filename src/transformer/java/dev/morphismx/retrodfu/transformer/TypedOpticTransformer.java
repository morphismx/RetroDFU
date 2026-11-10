package dev.morphismx.retrodfu.transformer;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import dev.morphismx.retrodfu.guava.GuavaCompat;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects {@code TypedOptic}'s newer-guava calls onto the compat
 * implementations. {@code lambda$instanceOf$0} is static, so its handler is
 * {@code private static}; {@code compose} is an instance method.
 */
@CTransformer(name = "com.mojang.datafixers.TypedOptic")
abstract class TypedOpticTransformer {

    @CRedirect(method = "compose", target = @CTarget(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList;builderWithExpectedSize(I)Lcom/google/common/collect/ImmutableList$Builder;"))
    ImmutableList.Builder<Object> builderWithExpectedSize(int expectedSize) {
        return GuavaCompat.listBuilderWithExpectedSize(expectedSize);
    }

    @CRedirect(method = "lambda$instanceOf$0", target = @CTarget(value = "INVOKE",
            target = Targets.IS_SUPERTYPE_OF))
    private static boolean isSupertypeOf(TypeToken<Object> type, TypeToken<Object> other) {
        return GuavaCompat.isSupertypeOf(type, other);
    }
}
