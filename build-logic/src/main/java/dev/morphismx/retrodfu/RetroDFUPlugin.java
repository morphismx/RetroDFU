package dev.morphismx.retrodfu;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Marker plugin that puts the task types of {@code dev.morphismx.retrodfu.task} on the build
 * script classpath. Tasks are registered in the build script itself, so their
 * inputs and outputs stay visible there.
 */
public final class RetroDFUPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // Intentionally empty: the build script registers the tasks.
    }
}
