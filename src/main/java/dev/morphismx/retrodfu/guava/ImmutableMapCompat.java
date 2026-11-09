package dev.morphismx.retrodfu.guava;

import com.google.common.collect.ImmutableMap;
import dev.morphismx.retrodfu.annotation.Redirect;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * {@code @Redirect} handlers for {@code ImmutableMap} calls that postdate the
 * game's guava 17 baseline.
 */
public final class ImmutableMapCompat {

    private ImmutableMapCompat() {
    }

    /**
     * Mirrors guava's {@code builderWithExpectedSize}. The size is only a
     * capacity hint, so a plain builder is equivalent; a negative hint is still
     * rejected, as guava does.
     */
    @Redirect(ImmutableMap.class)
    public static <K, V> ImmutableMap.Builder<K, V> builderWithExpectedSize(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException(
                    "expectedSize cannot be negative but was: " + expectedSize);
        }
        return ImmutableMap.builder();
    }

    /**
     * Mirrors guava 31's {@code buildKeepingLast}, which keeps the last value
     * for a duplicate key where older guava's {@code build()} throws.
     *
     * <p>{@code build()} is tried first, since without duplicates the result is
     * identical. On a duplicate, the builder's private entry array is replayed
     * into a {@code LinkedHashMap}: later puts overwrite the value and the first
     * insertion order is kept.
     */
    @Redirect(ImmutableMap.Builder.class)
    public static <K, V> ImmutableMap<K, V> buildKeepingLast(ImmutableMap.Builder<K, V> builder) {
        try {
            return builder.build();
        } catch (IllegalArgumentException duplicates) {
            return buildKeepingLastFallback(builder, duplicates);
        }
    }

    /**
     * Replays the builder's private entry array so later puts win while first
     * positions hold. Relies on the {@code entries}/{@code size} field names
     * guava 17 and 21 share; any other layout degrades to
     * {@link UnsupportedOperationException}.
     */
    @SuppressWarnings("unchecked")
    private static <K, V> ImmutableMap<K, V> buildKeepingLastFallback(
            ImmutableMap.Builder<K, V> builder, IllegalArgumentException cause) {
        try {
            Field entriesField = ImmutableMap.Builder.class.getDeclaredField("entries");
            Field sizeField = ImmutableMap.Builder.class.getDeclaredField("size");
            entriesField.setAccessible(true);
            sizeField.setAccessible(true);
            Object[] entries = (Object[]) entriesField.get(builder);
            int size = sizeField.getInt(builder);
            Map<K, V> lastWins = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                Map.Entry<K, V> e = (Map.Entry<K, V>) entries[i];
                lastWins.put(e.getKey(), e.getValue());
            }
            return ImmutableMap.copyOf(lastWins);
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException e) {
            throw new UnsupportedOperationException(
                    "cannot emulate ImmutableMap.Builder.buildKeepingLast() on this guava", e);
        }
    }

    /**
     * Handlers {@code of6} to {@code of10} stand in for the {@code of()}
     * overloads that guava 31 added; the game's guava stops at five entries.
     * They collect the pairs into a {@code LinkedHashMap}, rejecting
     * duplicates as guava does, then call {@code copyOf}.
     *
     * <p>Only the upstream test tree makes such calls, so a never-matched
     * warning for these on the main jar is expected.
     */
    @Redirect(value = ImmutableMap.class, method = "of")
    public static <K, V> ImmutableMap<K, V> of6(Object k1, Object v1, Object k2, Object v2,
                                                Object k3, Object v3, Object k4, Object v4,
                                                Object k5, Object v5, Object k6, Object v6) {
        return ofPairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    @Redirect(value = ImmutableMap.class, method = "of")
    public static <K, V> ImmutableMap<K, V> of7(Object k1, Object v1, Object k2, Object v2,
                                                Object k3, Object v3, Object k4, Object v4,
                                                Object k5, Object v5, Object k6, Object v6,
                                                Object k7, Object v7) {
        return ofPairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    @Redirect(value = ImmutableMap.class, method = "of")
    public static <K, V> ImmutableMap<K, V> of8(Object k1, Object v1, Object k2, Object v2,
                                                Object k3, Object v3, Object k4, Object v4,
                                                Object k5, Object v5, Object k6, Object v6,
                                                Object k7, Object v7, Object k8, Object v8) {
        return ofPairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    @Redirect(value = ImmutableMap.class, method = "of")
    public static <K, V> ImmutableMap<K, V> of9(Object k1, Object v1, Object k2, Object v2,
                                                Object k3, Object v3, Object k4, Object v4,
                                                Object k5, Object v5, Object k6, Object v6,
                                                Object k7, Object v7, Object k8, Object v8,
                                                Object k9, Object v9) {
        return ofPairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    @Redirect(value = ImmutableMap.class, method = "of")
    public static <K, V> ImmutableMap<K, V> of10(Object k1, Object v1, Object k2, Object v2,
                                                 Object k3, Object v3, Object k4, Object v4,
                                                 Object k5, Object v5, Object k6, Object v6,
                                                 Object k7, Object v7, Object k8, Object v8,
                                                 Object k9, Object v9, Object k10, Object v10) {
        return ofPairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
    }

    /**
     * Mirrors guava's {@code toImmutableMap} collector, which rejects duplicate
     * keys. Entries are gathered into a {@code LinkedHashMap} that throws on a
     * duplicate, then copied.
     */
    @Redirect(ImmutableMap.class)
    public static <T, K, V> Collector<T, ?, ImmutableMap<K, V>> toImmutableMap(
            Function<? super T, ? extends K> keyFunction,
            Function<? super T, ? extends V> valueFunction) {
        return Collector.of(
                LinkedHashMap<K, V>::new,
                (map, t) -> putOrThrow(map, keyFunction.apply(t), valueFunction.apply(t)),
                (a, b) -> {
                    b.forEach((k, v) -> putOrThrow(a, k, v));
                    return a;
                },
                ImmutableMap::copyOf);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> ImmutableMap<K, V> ofPairs(Object... kv) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            putOrThrow(map, (K) kv[i], (V) kv[i + 1]);
        }
        return ImmutableMap.copyOf(map);
    }

    /**
     * Puts an entry the way guava would: null keys and values throw
     * {@link NullPointerException}, duplicate keys throw
     * {@link IllegalArgumentException}.
     */
    private static <K, V> void putOrThrow(Map<K, V> map, K key, V value) {
        if (key == null) {
            throw new NullPointerException("null key in entry: null=" + value);
        }
        if (value == null) {
            throw new NullPointerException("null value in entry: " + key + "=null");
        }
        if (map.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("duplicate key: " + key);
        }
    }
}
