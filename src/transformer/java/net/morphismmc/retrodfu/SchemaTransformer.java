package net.morphismmc.retrodfu;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.fastutil.FastutilCompat;

/**
 * Redirects {@code Schema}'s {@code Object2IntMap.getOrDefault} calls onto the
 * compat implementation — an instance method because the targeted methods
 * are.
 */
@CTransformer(name = "com.mojang.datafixers.schemas.Schema")
abstract class SchemaTransformer {

    @CRedirect(
            method = {"buildTypes", "id"},
            target =
                    @CTarget(
                            value = "INVOKE",
                            target = "Lit/unimi/dsi/fastutil/objects/Object2IntMap;getOrDefault(Ljava/lang/Object;I)I"))
    int getOrDefault(Object2IntMap<Object> map, Object key, int defaultValue) {
        return FastutilCompat.getOrDefault(map, key, defaultValue);
    }
}
