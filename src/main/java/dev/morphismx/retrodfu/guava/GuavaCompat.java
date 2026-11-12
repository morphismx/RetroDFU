package dev.morphismx.retrodfu.guava;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * Compatibility implementations for guava members newer than the game's
 * guava 17 baseline; the transformers redirect the matching call sites here.
 */
public final class GuavaCompat {

    private static final MethodHandle IS_SUPERTYPE_OF = findIsSupertypeOf();

    private GuavaCompat() {}

    /**
     * Mirrors guava's {@code ImmutableList.builderWithExpectedSize}. The size
     * is only a capacity hint, so a plain builder is equivalent; a negative
     * hint is still rejected, as guava does.
     */
    public static <E> ImmutableList.Builder<E> listBuilderWithExpectedSize(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize cannot be negative but was: " + expectedSize);
        }
        return ImmutableList.builder();
    }

    /**
     * Mirrors guava's {@code ImmutableMap.builderWithExpectedSize}, with the
     * same reasoning as {@link #listBuilderWithExpectedSize}.
     */
    public static <K, V> ImmutableMap.Builder<K, V> mapBuilderWithExpectedSize(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize cannot be negative but was: " + expectedSize);
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
     * Mirrors guava's {@code toImmutableMap} collector, which rejects duplicate
     * keys. Entries are gathered into a {@code LinkedHashMap} that throws on a
     * duplicate, then copied.
     */
    public static <T, K, V> Collector<T, ?, ImmutableMap<K, V>> toImmutableMap(
            Function<? super T, ? extends K> keyFunction, Function<? super T, ? extends V> valueFunction) {
        return Collector.of(
                LinkedHashMap<K, V>::new,
                (map, t) -> putOrThrow(map, keyFunction.apply(t), valueFunction.apply(t)),
                (a, b) -> {
                    b.forEach((k, v) -> putOrThrow(a, k, v));
                    return a;
                },
                ImmutableMap::copyOf);
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

    /**
     * Memoizes {@code delegate} in a supplier that implements both guava's and
     * {@code java.util.function.Supplier}.
     *
     * <p>guava 21's {@link Supplier} extends the JDK one, which DFU (compiled
     * against guava 32) relies on; guava 17 has no such bridge, so its own
     * memoizer fails the cast at the use site.
     */
    public static <T> Supplier<T> memoize(Supplier<T> delegate) {
        if (delegate instanceof MemoizingSupplier) {
            return delegate;
        }
        return new MemoizingSupplier<>(delegate);
    }

    /**
     * Calls whichever subtype check this guava provides: guava 17 has
     * {@code isAssignableFrom}, 19 renamed it to {@code isSupertypeOf}, and
     * 21 removed the old name. The method is resolved once, in a
     * {@link MethodHandle} that the JIT can inline.
     */
    public static boolean isSupertypeOf(TypeToken<?> type, TypeToken<?> other) {
        try {
            return (boolean) IS_SUPERTYPE_OF.invokeExact(type, other);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UnsupportedOperationException("TypeToken subtype check failed", t);
        }
    }

    private static MethodHandle findIsSupertypeOf() {
        for (String name : new String[] {"isAssignableFrom", "isSupertypeOf"}) {
            try {
                return MethodHandles.publicLookup()
                        .findVirtual(TypeToken.class, name, MethodType.methodType(boolean.class, TypeToken.class));
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
            }
        }
        throw new ExceptionInInitializerError("no TypeToken subtype check found on this guava");
    }

    private static final class MemoizingSupplier<T> implements Supplier<T>, java.util.function.Supplier<T> {
        // Cleared after the first get, like guava's own memoizer: doubles as
        // the initialized flag and releases the delegate for collection.
        private volatile Supplier<T> delegate;
        private T value;

        MemoizingSupplier(Supplier<T> delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public T get() {
            if (delegate != null) {
                synchronized (this) {
                    if (delegate != null) {
                        value = delegate.get();
                        delegate = null;
                    }
                }
            }
            return value;
        }

        @Override
        public String toString() {
            Supplier<T> d = delegate;
            return "Suppliers.memoize(" + (d == null ? "<supplier that returned " + value + ">" : d) + ")";
        }
    }
}
