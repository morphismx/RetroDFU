package dev.morphismx.retrodfu.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method as the build-time handler for calls to the methods of
 * {@link #value()} named {@link #method()}. ClassPatcher rewrites each matching
 * invoke into an {@code INVOKESTATIC} of the handler.
 *
 * <p>The handler's erased descriptor must equal the call site's; for an
 * instance target the descriptor is taken with the owner type prepended, so
 * the receiver becomes the handler's first argument.
 *
 * <p>Matching is per call site. An invoke whose descriptor does not fit is left
 * untouched, so unrelated overloads of the same name pass through; CompatScan
 * then checks that the member resolves on the game baselines.
 *
 * <p>Retention is {@code CLASS}: the build reads the annotation from the class
 * file and the JVM ignores it at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Redirect {

    /**
     * The type whose methods get redirected, e.g. {@code Int2ObjectMap.class}.
     */
    Class<?> value();

    /**
     * Target method name; defaults to the annotated method's own name.
     */
    String method() default "";
}
