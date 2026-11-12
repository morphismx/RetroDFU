package net.morphismmc.retrodfu.fastutil;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * Compatibility implementations for fastutil calls that postdate
 * fastutil 7.1.0. The ClassTransform transformers forward the redirected call
 * sites here.
 */
public final class FastutilCompat {

    private FastutilCompat() {}

    /**
     * Mirrors fastutil 8.5's {@code toList()} collector. It collects in
     * encounter order, so wrapping the stream's array yields the same list.
     */
    public static IntArrayList toList(IntStream stream) {
        return IntArrayList.wrap(stream.toArray());
    }

    /**
     * {@code long} counterpart of {@link #toList(IntStream)}.
     */
    public static LongArrayList toList(LongStream stream) {
        return LongArrayList.wrap(stream.toArray());
    }

    /**
     * Mirrors fastutil 8's {@code intStream()}. 7.1.0 has no
     * {@code IntSpliterator} and its {@code IntIterator} is no
     * {@code PrimitiveIterator}, so one is adapted; laziness, order and size
     * are preserved.
     */
    public static IntStream intStream(IntList list) {
        IntIterator it = list.iterator();
        PrimitiveIterator.OfInt adapter = new PrimitiveIterator.OfInt() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public int nextInt() {
                return it.nextInt();
            }
        };
        return StreamSupport.intStream(
                Spliterators.spliterator(
                        adapter,
                        list.size(),
                        Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL),
                false);
    }

    /**
     * {@code long} counterpart of {@link #intStream}.
     */
    public static LongStream longStream(LongList list) {
        LongIterator it = list.iterator();
        PrimitiveIterator.OfLong adapter = new PrimitiveIterator.OfLong() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public long nextLong() {
                return it.nextLong();
            }
        };
        return StreamSupport.longStream(
                Spliterators.spliterator(
                        adapter,
                        list.size(),
                        Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL),
                false);
    }

    /**
     * Mirrors fastutil 8's {@code computeIfAbsent(int, Int2ObjectFunction)}: a
     * present key (or a stored non-{@code defaultReturnValue} value) returns as
     * is; a function that does not define the key yields the default uncalled;
     * otherwise the computed value is stored, {@code null} included — unlike
     * {@code Map.computeIfAbsent}. 7.1.0 names the function method
     * {@code get}, not {@code apply}.
     *
     * <p>No locking, as in fastutil: atomicity comes from the map.
     * {@code Int2ObjectMaps.synchronize} callers hold its monitor (the
     * transformer handlers do).
     */
    public static <V> V computeIfAbsent(
            Int2ObjectMap<V> map, int key, Int2ObjectFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
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

    /**
     * {@code long} counterpart of
     * {@link #computeIfAbsent(Int2ObjectMap, int, Int2ObjectFunction)}.
     */
    public static <V> V computeIfAbsent(
            Long2ObjectMap<V> map, long key, Long2ObjectFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
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

    /**
     * See {@link #defines(Int2ObjectFunction, int)}.
     */
    private static boolean defines(Long2ObjectFunction<?> function, long key) {
        return function.getClass().isSynthetic() || function.containsKey(key);
    }

    /**
     * Mirrors fastutil 8's {@code getOrDefault}. It does one lookup unless the
     * value equals {@code defaultReturnValue}; only then does
     * {@code containsKey} tell a stored default from an absent key.
     */
    public static <K> int getOrDefault(Object2IntMap<K> map, Object key, int defaultValue) {
        int v;
        return (v = map.getInt(key)) != map.defaultReturnValue() || map.containsKey(key) ? v : defaultValue;
    }

    /**
     * Mirrors fastutil 8's {@code fastIterable}: on a {@code FastEntrySet} both
     * {@code iterator()} and {@code forEach} run on {@code fastIterator()},
     * which reuses one entry instance; 7.1.0 has no {@code fastForEach}, so
     * {@code forEach} loops it manually.
     */
    public static <K, V> ObjectIterable<Object2ObjectMap.Entry<K, V>> fastIterable(Object2ObjectMap<K, V> map) {
        ObjectSet<Object2ObjectMap.Entry<K, V>> entries = map.object2ObjectEntrySet();
        if (entries instanceof Object2ObjectMap.FastEntrySet<K, V> fast) {
            return new ObjectIterable<>() {
                @Override
                public ObjectIterator<Object2ObjectMap.Entry<K, V>> iterator() {
                    return fast.fastIterator();
                }

                @Override
                public void forEach(Consumer<? super Object2ObjectMap.Entry<K, V>> consumer) {
                    ObjectIterator<Object2ObjectMap.Entry<K, V>> it = fast.fastIterator();
                    while (it.hasNext()) {
                        consumer.accept(it.next());
                    }
                }
            };
        }
        return entries;
    }
}
