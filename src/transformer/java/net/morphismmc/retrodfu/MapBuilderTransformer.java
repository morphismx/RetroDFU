package net.morphismmc.retrodfu;

import com.google.common.collect.ImmutableMap;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.guava.GuavaCompat;

/**
 * Redirects {@code RecordBuilder$MapBuilder.build}'s {@code buildKeepingLast}
 * call onto the compat implementation — an instance method because
 * {@code build} is.
 */
@CTransformer(name = "com.mojang.serialization.RecordBuilder$MapBuilder")
abstract class MapBuilderTransformer {

    @CRedirect(method = "build", target = @CTarget(value = "INVOKE", target = Targets.BUILD_KEEPING_LAST))
    ImmutableMap<Object, Object> buildKeepingLast(ImmutableMap.Builder<Object, Object> builder) {
        return GuavaCompat.buildKeepingLast(builder);
    }
}
