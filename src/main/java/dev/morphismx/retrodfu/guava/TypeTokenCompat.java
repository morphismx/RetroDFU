package dev.morphismx.retrodfu.guava;

import com.google.common.reflect.TypeToken;
import dev.morphismx.retrodfu.annotation.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@code @Redirect} handlers for {@code TypeToken} calls that postdate the
 * game's guava 17 baseline.
 */
public final class TypeTokenCompat {

    private static final MethodHandle IS_SUPERTYPE_OF = findIsSupertypeOf();

    private TypeTokenCompat() {
    }

    /**
     * Calls whichever subtype check this guava provides: guava 17 has
     * {@code isAssignableFrom}, 19 renamed it to {@code isSupertypeOf}, and
     * 21 removed the old name. The method is resolved once, in a
     * {@link MethodHandle} that the JIT can inline.
     */
    @Redirect(TypeToken.class)
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
        for (String name : new String[]{"isAssignableFrom", "isSupertypeOf"}) {
            try {
                return MethodHandles.publicLookup().findVirtual(TypeToken.class, name,
                        MethodType.methodType(boolean.class, TypeToken.class));
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
            }
        }
        throw new ExceptionInInitializerError(
                "no TypeToken subtype check found on this guava");
    }
}
