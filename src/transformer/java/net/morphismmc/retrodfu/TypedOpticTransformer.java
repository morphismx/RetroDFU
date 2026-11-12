package net.morphismmc.retrodfu;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.guava.GuavaCompat;

/**
 * Redirects {@code TypedOptic}'s newer-guava calls onto the compat
 * implementations — {@code private static} for the static
 * {@code lambda$instanceOf$0}, an instance method for {@code compose}.
 */
@CTransformer(name = "com.mojang.datafixers.TypedOptic")
abstract class TypedOpticTransformer {

    @CRedirect(method = "lambda$instanceOf$0", target = @CTarget(value = "INVOKE", target = Targets.IS_SUPERTYPE_OF))
    private static boolean isSupertypeOf(TypeToken<Object> type, TypeToken<Object> other) {
        return GuavaCompat.isSupertypeOf(type, other);
    }

    @CRedirect(
            method = "compose",
            target =
                    @CTarget(
                            value = "INVOKE",
                            target =
                                    "Lcom/google/common/collect/ImmutableList;builderWithExpectedSize(I)Lcom/google/common/collect/ImmutableList$Builder;"))
    ImmutableList.Builder<Object> builderWithExpectedSize(int expectedSize) {
        return GuavaCompat.listBuilderWithExpectedSize(expectedSize);
    }
}
