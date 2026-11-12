package net.morphismmc.retrodfu.slf4j;

/**
 * Replacement for {@code org.slf4j.LoggerFactory} that returns loggers backed
 * by Log4j2. The build-time type remap rewrites every slf4j reference here.
 */
public final class LoggerFactory {

    private LoggerFactory() {}

    public static Logger getLogger(String name) {
        return new LoggerImpl(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
}
