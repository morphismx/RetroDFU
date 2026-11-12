package net.morphismmc.retrodfu;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterable;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.fastutil.FastutilCompat;

/**
 * Redirects {@code TaggedChoice}'s {@code Object2ObjectMaps.fastIterable} call
 * onto the compat implementation — an instance method because
 * {@code lambda$apply$1} is.
 */
@CTransformer(name = "com.mojang.datafixers.types.templates.TaggedChoice")
abstract class TaggedChoiceTransformer {

    @CRedirect(method = "lambda$apply$1", target = @CTarget(value = "INVOKE", target = Targets.FAST_ITERABLE))
    ObjectIterable<Object2ObjectMap.Entry<Object, Object>> fastIterable(Object2ObjectMap<Object, Object> map) {
        return FastutilCompat.fastIterable(map);
    }
}
