package dev.morphismx.retrodfu.slf4j;

import dev.morphismx.retrodfu.annotation.Replaces;

/**
 * Replacement for {@code org.slf4j.Logger}.
 *
 * <p>The patcher renames the type without touching call sites, so this interface
 * must keep the shape of slf4j's. It declares only the methods DFU calls.
 */
@Replaces("org.slf4j.Logger")
public interface Logger {

    void info(String msg);

    void info(String format, Object arg1, Object arg2);

    void warn(String format, Object arg1, Object arg2);

    void error(String msg);
}
