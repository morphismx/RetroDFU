package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.stream.LongStream;

/**
 * {@code @Redirect} handlers for {@code LongArrayList} calls that postdate
 * fastutil 7.1.0.
 */
public final class LongArrayListCompat {

    private LongArrayListCompat() {
    }

    /**
     * {@code long} counterpart of {@link IntArrayListCompat#toList}.
     */
    @Redirect(LongArrayList.class)
    public static LongArrayList toList(LongStream stream) {
        return LongArrayList.wrap(stream.toArray());
    }
}
