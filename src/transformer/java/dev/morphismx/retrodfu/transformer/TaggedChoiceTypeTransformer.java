package dev.morphismx.retrodfu.transformer;

import dev.morphismx.retrodfu.fastutil.FastutilCompat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterable;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects {@code TaggedChoice$TaggedChoiceType}'s
 * {@code Object2ObjectMaps.fastIterable} calls onto the compat implementation.
 * The handler is an instance method because the targeted methods are.
 */
@CTransformer(name = "com.mojang.datafixers.types.templates.TaggedChoice$TaggedChoiceType")
abstract class TaggedChoiceTypeTransformer {

    @CRedirect(method = {"findTypeInChildren", "all", "updateMu", "buildTemplate"},
            target = @CTarget(value = "INVOKE",
                    target = Targets.FAST_ITERABLE))
    ObjectIterable<Object2ObjectMap.Entry<Object, Object>> fastIterable(
            Object2ObjectMap<Object, Object> map) {
        return FastutilCompat.fastIterable(map);
    }
}
