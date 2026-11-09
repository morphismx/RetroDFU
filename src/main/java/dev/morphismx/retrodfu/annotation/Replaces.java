package dev.morphismx.retrodfu.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as the build-time replacement for another type. ClassPatcher
 * renames every reference to the original type (fields, descriptors,
 * signatures, invokes, handles, constants) to the annotated type.
 *
 * <p>The target is a binary name, so it can name types deliberately absent
 * at compile time, such as {@code org.slf4j}, which this jar does not
 * depend on.
 *
 * <p>The remap is a pure rename, so the replacement must keep the original's
 * bytecode shape: the same class or interface kind and the member signatures
 * that callers reference. CompatScan fails the build if a remapped member no
 * longer resolves.
 *
 * <p>Retention is {@code CLASS}: the build reads the annotation from the class
 * file and the JVM ignores it at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Replaces {

    /**
     * Binary name of the replaced type, e.g. {@code "org.slf4j.Logger"}.
     */
    String value();
}
