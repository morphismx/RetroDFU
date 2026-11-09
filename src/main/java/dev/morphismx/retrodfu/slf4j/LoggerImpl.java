package dev.morphismx.retrodfu.slf4j;

import org.apache.logging.log4j.LogManager;

/**
 * {@link Logger} that forwards to a Log4j2 logger.
 *
 * <p>Log4j2 uses the same {@code {}} placeholders as SLF4J, so format strings
 * pass through unchanged. log4j-api 2.0-beta9 (MC 1.7.10) has no
 * {@code (String, Object, Object)} overload; the two-argument calls below
 * resolve to {@code (String, Object...)} there.
 */
final class LoggerImpl implements Logger {

    private final org.apache.logging.log4j.Logger delegate;

    LoggerImpl(String name) {
        this.delegate = LogManager.getLogger(name);
    }

    @Override
    public void info(String msg) {
        delegate.info(msg);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        delegate.info(format, arg1, arg2);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        delegate.warn(format, arg1, arg2);
    }

    @Override
    public void error(String msg) {
        delegate.error(msg);
    }
}
