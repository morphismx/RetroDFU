package net.morphismmc.retrodfu;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.morphismmc.retrodfu.fastutil.FastutilCompat;

/**
 * Redirects {@code RecursiveTypeFamily.apply}'s {@code Int2ObjectMap} call
 * onto the compat implementation — an instance method because {@code apply}
 * is.
 */
@CTransformer(name = "com.mojang.datafixers.types.families.RecursiveTypeFamily")
abstract class RecursiveTypeFamilyTransformer {

    @CRedirect(
            method = "apply",
            target =
                    @CTarget(
                            value = "INVOKE",
                            target =
                                    "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;computeIfAbsent(ILit/unimi/dsi/fastutil/ints/Int2ObjectFunction;)Ljava/lang/Object;"))
    Object computeIfAbsent(Int2ObjectMap<Object> map, int key, Int2ObjectFunction<Object> mappingFunction) {
        // types is an Int2ObjectMaps.synchronize wrapper, which locks on the
        // wrapper itself, so the compound get-compute-put runs under that same
        // monitor here.
        synchronized (map) {
            return FastutilCompat.computeIfAbsent(map, key, mappingFunction);
        }
    }
}
