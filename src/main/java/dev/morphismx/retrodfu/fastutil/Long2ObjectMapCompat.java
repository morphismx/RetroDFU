package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.util.Objects;

/**
 * {@code @Redirect} handlers for {@code Long2ObjectMap} calls that postdate
 * fastutil 7.1.0.
 */
public final class Long2ObjectMapCompat {

    private Long2ObjectMapCompat() {
    }

    /**
     * {@code long} counterpart of {@link Int2ObjectMapCompat#computeIfAbsent}.
     */
    @Redirect(Long2ObjectMap.class)
    public static <V> V computeIfAbsent(Long2ObjectMap<V> map, long key,
                                        Long2ObjectFunction<? extends V> mappingFunction) {
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
     * See {@link Int2ObjectMapCompat#defines}.
     */
    private static boolean defines(Long2ObjectFunction<?> function, long key) {
        return function.getClass().isSynthetic() || function.containsKey(key);
    }
}
