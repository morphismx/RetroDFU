package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * {@code @Redirect} handlers for {@code LongList} calls that postdate
 * fastutil 7.1.0.
 */
public final class LongListCompat {

    private LongListCompat() {
    }

    /**
     * {@code long} counterpart of {@link IntListCompat#intStream}.
     */
    @Redirect(LongList.class)
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
                Spliterators.spliterator(adapter, list.size(),
                        Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED
                                | Spliterator.NONNULL),
                false);
    }
}
