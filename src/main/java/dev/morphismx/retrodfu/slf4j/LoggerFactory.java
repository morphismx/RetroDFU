package dev.morphismx.retrodfu.slf4j;

import dev.morphismx.retrodfu.annotation.Replaces;

/**
 * Replacement for {@code org.slf4j.LoggerFactory} that returns loggers backed by Log4j2.
 */
@Replaces("org.slf4j.LoggerFactory")
public final class LoggerFactory {

    private LoggerFactory() {
    }

    public static Logger getLogger(String name) {
        return new LoggerImpl(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
}
