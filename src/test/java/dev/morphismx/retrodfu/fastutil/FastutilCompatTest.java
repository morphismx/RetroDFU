package dev.morphismx.retrodfu.fastutil;

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectFunction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterable;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.junit.Test;

import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FastutilCompatTest {

    @Test
    public void toListIntCollectsInOrder() {
        assertEquals(new IntArrayList(new int[]{1, 2, 3}),
                FastutilCompat.toList(IntStream.of(1, 2, 3)));
    }

    @Test
    public void toListLongCollectsInOrder() {
        assertEquals(new LongArrayList(new long[]{1, 2, 3}),
                FastutilCompat.toList(LongStream.of(1, 2, 3)));
    }

    @Test
    public void intStreamPreservesOrder() {
        assertArrayEquals(new int[]{4, 2, 7},
                FastutilCompat.intStream(new IntArrayList(new int[]{4, 2, 7})).toArray());
    }

    @Test
    public void longStreamPreservesOrder() {
        assertArrayEquals(new long[]{4, 2, 7},
                FastutilCompat.longStream(new LongArrayList(new long[]{4, 2, 7})).toArray());
    }

    @Test
    public void computeIfAbsentStoresComputedValue() {
        Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
        Defining function = new Defining();
        assertEquals("k1", FastutilCompat.computeIfAbsent(map, 1, function));
        assertEquals("k1", map.get(1));
        assertEquals(1, function.calls);
    }

    @Test
    public void computeIfAbsentReturnsPresentWithoutCalling() {
        Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
        map.put(1, "present");
        Defining function = new Defining();
        assertEquals("present", FastutilCompat.computeIfAbsent(map, 1, function));
        assertEquals(0, function.calls);
    }

    @Test
    public void computeIfAbsentReturnsStoredDefaultWithoutCalling() {
        // A key stored with the default return value still counts as present.
        Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
        map.put(1, null);
        Defining function = new Defining();
        assertNull(FastutilCompat.computeIfAbsent(map, 1, function));
        assertEquals(0, function.calls);
    }

    @Test
    public void computeIfAbsentSkipsKeysTheFunctionDoesNotDefine() {
        Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
        map.defaultReturnValue("d");
        NotDefining function = new NotDefining();
        assertEquals("d", FastutilCompat.computeIfAbsent(map, 7, function));
        assertEquals(0, function.calls);
    }

    @Test
    public void computeIfAbsentCallsDefiningFunction() {
        Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
        assertEquals("k3", FastutilCompat.computeIfAbsent(map, 3, new Defining()));
        assertEquals("k3", map.get(3));
    }

    @Test
    public void computeIfAbsentLongStoresComputedValue() {
        Long2ObjectMap<String> map = new Long2ObjectOpenHashMap<>();
        String value = FastutilCompat.computeIfAbsent(map, 2L, new DefiningLong());
        assertEquals("v2", value);
        assertEquals("v2", map.get(2L));
    }

    @Test
    public void getOrDefaultReturnsStoredOrSupplied() {
        Object2IntMap<String> map = new Object2IntOpenHashMap<>();
        map.put("a", 5);
        assertEquals(5, FastutilCompat.getOrDefault(map, "a", -1));
        assertEquals(-1, FastutilCompat.getOrDefault(map, "b", -1));
    }

    @Test
    public void getOrDefaultKeepsStoredDefaultReturnValue() {
        // A stored value equal to defaultReturnValue still wins over the
        // caller's default — containsKey is the tie-break.
        Object2IntMap<String> map = new Object2IntOpenHashMap<>();
        map.defaultReturnValue(-1);
        map.put("a", -1);
        assertEquals(-1, FastutilCompat.getOrDefault(map, "a", 9));
    }

    @Test
    public void fastIterableIteratesAllEntries() {
        Object2ObjectMap<String, Integer> map = new Object2ObjectOpenHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        ObjectIterable<Object2ObjectMap.Entry<String, Integer>> iterable =
                FastutilCompat.fastIterable(map);
        int sum = 0;
        for (ObjectIterator<Object2ObjectMap.Entry<String, Integer>> it = iterable.iterator();
             it.hasNext(); ) {
            sum += it.next().getValue();
        }
        assertEquals(3, sum);
        int[] forEachSum = {0};
        iterable.forEach(e -> forEachSum[0] += e.getValue());
        assertEquals(3, forEachSum[0]);
    }

    @Test
    public void fastIterableFallsBackToTheEntrySet() {
        Object2ObjectMap<String, Integer> wrapped =
                Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>());
        // The unmodifiable wrapper's entry set is no FastEntrySet, so the
        // entry set itself is the iterable.
        ObjectSet<Object2ObjectMap.Entry<String, Integer>> entries =
                wrapped.object2ObjectEntrySet();
        assertSame(entries, FastutilCompat.fastIterable(wrapped));
    }

    // The function impls are class-based, not lambdas: Int2ObjectFunction has
    // several abstract methods on fastutil 7.1.0, so it is no functional
    // interface on the compile baseline. Non-synthetic classes also exercise
    // the containsKey branch a lambda's synthetic class would skip.
    private static final class NotDefining extends AbstractInt2ObjectFunction<String> {
        int calls;

        @Override
        public String get(int key) {
            calls++;
            return "x";
        }

        @Override
        public boolean containsKey(int key) {
            return false;
        }

        // size() is abstract on the 7.1.0 baseline; containsKey answers per
        // key, so the count is never consulted.
        @Override
        public int size() {
            return 0;
        }
    }

    private static final class Defining extends AbstractInt2ObjectFunction<String> {
        int calls;

        @Override
        public String get(int key) {
            calls++;
            return "k" + key;
        }

        @Override
        public boolean containsKey(int key) {
            return true;
        }

        @Override
        public int size() {
            return 0;
        }
    }

    private static final class DefiningLong extends AbstractLong2ObjectFunction<String> {
        @Override
        public String get(long key) {
            return "v" + key;
        }

        @Override
        public boolean containsKey(long key) {
            return true;
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
