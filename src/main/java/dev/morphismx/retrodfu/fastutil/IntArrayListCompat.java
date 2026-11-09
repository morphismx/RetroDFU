package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.stream.IntStream;

/**
 * {@code @Redirect} handlers for {@code IntArrayList} calls that postdate
 * fastutil 7.1.0.
 */
public final class IntArrayListCompat {

    private IntArrayListCompat() {
    }

    /**
     * Mirrors fastutil 8.5's {@code toList()} collector. It collects in
     * encounter order, so wrapping the stream's array yields the same list.
     */
    @Redirect(IntArrayList.class)
    public static IntArrayList toList(IntStream stream) {
        return IntArrayList.wrap(stream.toArray());
    }
}
