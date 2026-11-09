package dev.morphismx.retrodfu.guava;

import com.google.common.collect.ImmutableList;
import dev.morphismx.retrodfu.annotation.Redirect;

/**
 * {@code @Redirect} handlers for {@code ImmutableList} calls that postdate the
 * game's guava 17 baseline.
 */
public final class ImmutableListCompat {

    private ImmutableListCompat() {
    }

    /**
     * Mirrors guava's {@code builderWithExpectedSize}. The size is only a
     * capacity hint, so a plain builder is equivalent; a negative hint is still
     * rejected, as guava does.
     */
    @Redirect(ImmutableList.class)
    public static <E> ImmutableList.Builder<E> builderWithExpectedSize(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException(
                    "expectedSize cannot be negative but was: " + expectedSize);
        }
        return ImmutableList.builder();
    }
}
