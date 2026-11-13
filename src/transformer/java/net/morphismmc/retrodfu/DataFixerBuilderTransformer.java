package net.morphismmc.retrodfu;

import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import org.apache.logging.log4j.Logger;

/**
 * Redirects {@code DataFixerBuilder.addFixer}'s {@code warn} call onto the
 * varargs overload. The {@code renames} map retargets slf4j's {@code Logger}
 * to log4j2's, but the fixed-arity {@code (String, Object, Object)} overloads
 * do not exist on log4j-api 2.0-beta9 — compiled against that baseline, the
 * call below binds to {@code warn(String, Object...)}, which every game
 * provides.
 */
@CTransformer(name = "com.mojang.datafixers.DataFixerBuilder")
abstract class DataFixerBuilderTransformer {

    @CRedirect(
            method = "addFixer",
            target =
                    @CTarget(
                            value = "INVOKE",
                            target =
                                    "Lorg/apache/logging/log4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    void warn(Logger logger, String format, Object arg1, Object arg2) {
        logger.warn(format, arg1, arg2);
    }
}
