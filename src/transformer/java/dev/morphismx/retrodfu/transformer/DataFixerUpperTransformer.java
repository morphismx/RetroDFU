package dev.morphismx.retrodfu.transformer;

import dev.morphismx.retrodfu.fastutil.FastutilCompat;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects {@code DataFixerUpper.getRule}'s {@code Long2ObjectMap} call onto
 * the compat implementation. The handler is an instance method because
 * {@code getRule} is.
 */
@CTransformer(name = "com.mojang.datafixers.DataFixerUpper")
abstract class DataFixerUpperTransformer {

    @CRedirect(method = "getRule", target = @CTarget(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;computeIfAbsent(JLit/unimi/dsi/fastutil/longs/Long2ObjectFunction;)Ljava/lang/Object;"))
    Object computeIfAbsent(Long2ObjectMap<Object> map, long key,
                           Long2ObjectFunction<Object> mappingFunction) {
        // rules is a Long2ObjectMaps.synchronize wrapper, which locks on the
        // wrapper itself, so the compound get-compute-put runs under that same
        // monitor here.
        synchronized (map) {
            return FastutilCompat.computeIfAbsent(map, key, mappingFunction);
        }
    }
}
