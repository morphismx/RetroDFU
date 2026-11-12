package dev.morphismx.retrodfu.task;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/**
 * The transformer inputs shared by {@link PatchJar} and {@link PatchFiles}.
 */
interface PatchTask {

    /**
     * The class trees holding the {@code @CTransformer} classes — not
     * {@code @Classpath}: the rules live in runtime-invisible annotations,
     * which classpath normalization ignores.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    ConfigurableFileCollection getTransformers();

    /**
     * The transformer config JSON inside {@link #getTransformers()}, e.g.
     * {@code dfu.transformers.json}.
     */
    @Input
    Property<String> getTransformerConfig();

    /**
     * Jars and class trees ClassTransform's frame computer may resolve classes
     * from — the input's dependencies plus the project's own classes.
     */
    @Classpath
    ConfigurableFileCollection getClasspath();
}
