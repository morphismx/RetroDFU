package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;

import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * {@code @Redirect} handlers for {@code IntList} calls that postdate
 * fastutil 7.1.0.
 */
public final class IntListCompat {

    private IntListCompat() {
    }

    /**
     * Mirrors fastutil 8's {@code intStream()}, a lazy view over the list built
     * from {@code intSpliterator()}. fastutil 7.1.0 has no {@code IntSpliterator},
     * and its {@code IntIterator} is not a {@code PrimitiveIterator}, so the
     * iterator is adapted to {@code PrimitiveIterator.OfInt}. Laziness, order and
     * size are preserved.
     */
    @Redirect(IntList.class)
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
                Spliterators.spliterator(adapter, list.size(),
                        Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED
                                | Spliterator.NONNULL),
                false);
    }
}
