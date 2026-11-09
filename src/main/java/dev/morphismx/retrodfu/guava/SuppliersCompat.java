package dev.morphismx.retrodfu.guava;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import dev.morphismx.retrodfu.annotation.Redirect;

import java.util.Objects;

/**
 * {@code @Redirect} handlers for {@code Suppliers} calls that postdate the
 * game's guava 17 baseline.
 */
public final class SuppliersCompat {

    private SuppliersCompat() {
    }

    /**
     * Memoizes {@code delegate} in a supplier that implements both guava's and
     * {@code java.util.function.Supplier}.
     *
     * <p>Since guava 21, guava's {@link Supplier} extends the JDK one, and DFU,
     * compiled against guava 32, uses the memoized result as either. Guava 17
     * has no such bridge, so its own memoizer fails the cast at the use site.
     */
    @Redirect(Suppliers.class)
    public static <T> Supplier<T> memoize(Supplier<T> delegate) {
        if (delegate instanceof MemoizingSupplier) {
            return delegate;
        }
        return new MemoizingSupplier<>(delegate);
    }

    private static final class MemoizingSupplier<T>
            implements Supplier<T>, java.util.function.Supplier<T> {
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
            return "Suppliers.memoize("
                    + (d == null ? "<supplier that returned " + value + ">" : d) + ")";
        }
    }

}
