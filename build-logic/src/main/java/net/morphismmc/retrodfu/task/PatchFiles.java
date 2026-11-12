package net.morphismmc.retrodfu.task;

import java.io.IOException;
import net.morphismmc.retrodfu.ClassPatcher;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;

/**
 * Directory-tree variant of {@link PatchJar}: every file under
 * {@link #getInputDirs()} is rewritten into {@link #getOutputDir()}, keeping
 * relative paths. Used on the test classes, which travel as a class tree
 * rather than a jar. Unlike {@link PatchJar} it performs no merge — the
 * patched tree is the output itself.
 */
@CacheableTask
public abstract class PatchFiles extends DefaultTask implements PatchTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputDirs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        new ClassPatcher(
                        getTransformers().getFiles(),
                        getTransformerConfig().get(),
                        getClasspath().getFiles())
                .patchDirs(getInputDirs().getFiles(), getOutputDir().get().getAsFile());
    }
}
