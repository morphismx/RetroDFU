package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.util.Objects;

/**
 * {@code @Redirect} handlers for {@code Int2ObjectMap} calls that postdate
 * fastutil 7.1.0.
 */
public final class Int2ObjectMapCompat {

    private Int2ObjectMapCompat() {
    }

    /**
     * Mirrors fastutil 8's {@code computeIfAbsent(int, Int2ObjectFunction)}.
     *
     * <p>A present key, or a stored value other than {@code defaultReturnValue},
     * is returned as is. A function that does not define the key yields the
     * default without being called. Otherwise the computed value is stored,
     * even if {@code null} (unlike {@code Map.computeIfAbsent}). fastutil 7.1.0
     * names the function method {@code get}, not {@code apply}.
     *
     * <p>DFU calls this on {@code Int2ObjectMaps.synchronize} wrappers, which
     * run the whole get-compute-put under one monitor (the map itself by
     * default). Synchronizing on the map reproduces that atomicity and stays
     * reentrant for recursive computes.
     */
    @Redirect(Int2ObjectMap.class)
    public static <V> V computeIfAbsent(Int2ObjectMap<V> map, int key,
                                        Int2ObjectFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        synchronized (map) {
            V value = map.get(key);
            V defaultReturnValue = map.defaultReturnValue();
            if (value != defaultReturnValue || map.containsKey(key)) {
                return value;
            }
            if (!defines(mappingFunction, key)) {
                return defaultReturnValue;
            }
            value = mappingFunction.get(key);
            map.put(key, value);
            return value;
        }
    }

    /**
     * Whether {@code function} defines {@code key}.
     *
     * <p>{@code containsKey} is a default method returning {@code true} in
     * fastutil 8.5 but abstract in 7.1.0, where calling it on a lambda throws
     * {@link AbstractMethodError}. Lambda classes are synthetic, so they are
     * treated as defining every key, as the 8.5 default does.
     */
    private static boolean defines(Int2ObjectFunction<?> function, int key) {
        return function.getClass().isSynthetic() || function.containsKey(key);
    }
}
