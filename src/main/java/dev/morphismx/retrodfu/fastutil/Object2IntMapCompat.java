package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * {@code @Redirect} handlers for {@code Object2IntMap} calls that postdate
 * fastutil 7.1.0.
 */
public final class Object2IntMapCompat {

    private Object2IntMapCompat() {
    }

    /**
     * Mirrors fastutil 8's {@code getOrDefault}. It does one lookup unless the
     * value equals {@code defaultReturnValue}; only then does
     * {@code containsKey} tell a stored default from an absent key.
     */
    @Redirect(Object2IntMap.class)
    public static <K> int getOrDefault(Object2IntMap<K> map, Object key, int defaultValue) {
        int v;
        return (v = map.getInt(key)) != map.defaultReturnValue() || map.containsKey(key)
                ? v : defaultValue;
    }
}
