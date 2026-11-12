package net.morphismmc.retrodfu.slf4j;

/**
 * Replacement for {@code org.slf4j.Logger}.
 *
 * <p>The {@code renames} map in the transformer configs renames every slf4j
 * reference to this type without touching call sites, so this interface must
 * keep the shape of slf4j's. It declares only the methods DFU calls.
 */
public interface Logger {

    void info(String msg);

    void info(String format, Object arg1, Object arg2);

    void warn(String format, Object arg1, Object arg2);

    void error(String msg);
}
