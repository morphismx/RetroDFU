package net.morphismmc.retrodfu;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Marker plugin: it only puts the task types on the build-script classpath.
 */
public final class RetroDFUPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {}
}
