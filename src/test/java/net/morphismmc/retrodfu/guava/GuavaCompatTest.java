package net.morphismmc.retrodfu.guava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

public class GuavaCompatTest {

    @Test
    public void listBuilderWithExpectedSizeBuilds() {
        assertEquals(
                Arrays.asList(1, 2, 3),
                GuavaCompat.listBuilderWithExpectedSize(3).add(1, 2, 3).build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void listBuilderWithExpectedSizeRejectsNegative() {
        GuavaCompat.listBuilderWithExpectedSize(-1);
    }

    @Test
    public void mapBuilderWithExpectedSizeBuilds() {
        assertEquals(
                ImmutableMap.of("a", 1),
                GuavaCompat.<String, Integer>mapBuilderWithExpectedSize(1)
                        .put("a", 1)
                        .build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void mapBuilderWithExpectedSizeRejectsNegative() {
        GuavaCompat.mapBuilderWithExpectedSize(-1);
    }

    @Test
    public void buildKeepingLastWithoutDuplicates() {
        assertEquals(
                ImmutableMap.of("a", 1, "b", 2),
                GuavaCompat.buildKeepingLast(
                        ImmutableMap.<String, Integer>builder().put("a", 1).put("b", 2)));
    }

    @Test
    public void buildKeepingLastKeepsLastValueAndFirstPosition() {
        ImmutableMap<String, Integer> map = GuavaCompat.buildKeepingLast(
                ImmutableMap.<String, Integer>builder().put("a", 1).put("b", 2).put("a", 3));
        assertEquals(ImmutableMap.of("a", 3, "b", 2), map);
        // ImmutableMap iterates in insertion order: the overwritten key holds
        // its first position, matching guava's own buildKeepingLast.
        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(map.keySet()));
    }

    @Test
    public void toImmutableMapCollects() {
        Map<String, Integer> map =
                Stream.of("a", "bb", "ccc").collect(GuavaCompat.toImmutableMap(s -> s, String::length));
        assertEquals(ImmutableMap.of("a", 1, "bb", 2, "ccc", 3), map);
    }

    @Test(expected = IllegalArgumentException.class)
    public void toImmutableMapRejectsDuplicateKeys() {
        Stream.of("a", "b").collect(GuavaCompat.toImmutableMap(s -> 1, s -> s));
    }

    @Test(expected = NullPointerException.class)
    public void toImmutableMapRejectsNullKeys() {
        Stream.of("a").collect(GuavaCompat.toImmutableMap(s -> null, s -> s));
    }

    @Test(expected = NullPointerException.class)
    public void toImmutableMapRejectsNullValues() {
        Stream.of("a").collect(GuavaCompat.toImmutableMap(s -> s, s -> null));
    }

    @Test
    public void toImmutableMapCombinesParallelChunks() {
        Map<Integer, Integer> map =
                IntStream.range(0, 1000).boxed().parallel().collect(GuavaCompat.toImmutableMap(i -> i, i -> i * 2));
        assertEquals(1000, map.size());
        assertEquals(Integer.valueOf(1998), map.get(999));
    }

    @Test
    public void memoizeIsAlsoAJdkSupplier() {
        // DFU is compiled against a guava whose Supplier extends the JDK's,
        // so it casts the memoized result to that type.
        assertTrue(GuavaCompat.memoize(() -> 1) instanceof java.util.function.Supplier);
    }

    @Test
    public void memoizeRetriesAfterTheDelegateThrows() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Integer> supplier = GuavaCompat.memoize(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("first call fails");
            return 7;
        });
        try {
            supplier.get();
            org.junit.Assert.fail("expected the first call to throw");
        } catch (IllegalStateException expected) {
            // guava's own memoizer does not cache a failure either
        }
        assertEquals(Integer.valueOf(7), supplier.get());
        assertEquals(2, calls.get());
    }

    @Test
    public void memoizeIsLazyAndCallsDelegateOnce() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Integer> supplier = GuavaCompat.memoize(calls::incrementAndGet);
        assertEquals(0, calls.get());
        assertEquals(Integer.valueOf(1), supplier.get());
        assertEquals(Integer.valueOf(1), supplier.get());
        assertEquals(1, calls.get());
    }

    @Test
    public void memoizeSkipsAlreadyMemoized() {
        Supplier<Integer> once = GuavaCompat.memoize(() -> 1);
        assertSame(once, GuavaCompat.memoize(once));
    }

    @Test
    public void isSupertypeOfMatchesTypeToken() {
        TypeToken<Object> object = TypeToken.of(Object.class);
        TypeToken<String> string = TypeToken.of(String.class);
        assertTrue(GuavaCompat.isSupertypeOf(object, string));
        assertTrue(GuavaCompat.isSupertypeOf(string, string));
        assertFalse(GuavaCompat.isSupertypeOf(string, object));
    }
}
