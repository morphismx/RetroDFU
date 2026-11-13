package net.morphismmc.retrodfu;

import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import org.apache.logging.log4j.Logger;

/**
 * The same fixed-arity-to-varargs redirect as
 * {@link DataFixerBuilderTransformer}, for the {@code info} call in the
 * completion lambda of {@code DataFixerBuilder$Result.optimize} — a static
 * lambda body, so the handler is static as well.
 */
@CTransformer(name = "com.mojang.datafixers.DataFixerBuilder$Result")
abstract class DataFixerBuilderResultTransformer {

    @CRedirect(
            method = "lambda$optimize$4",
            target =
                    @CTarget(
                            value = "INVOKE",
                            target =
                                    "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void info(Logger logger, String format, Object arg1, Object arg2) {
        logger.info(format, arg1, arg2);
    }
}
