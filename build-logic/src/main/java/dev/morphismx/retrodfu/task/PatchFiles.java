package dev.morphismx.retrodfu.task;

import dev.morphismx.retrodfu.patch.ClassPatcher;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

import java.io.IOException;
import java.util.List;

/**
 * Directory-tree variant of {@link PatchJar}: every file under
 * {@link #getInputDirs()} is rewritten into {@link #getOutputDir()}, keeping
 * relative paths. Used on the test classes, which travel as a class tree
 * rather than a jar. Unlike {@link PatchJar} it performs no merge — the
 * patched tree is the output itself.
 */
@CacheableTask
public abstract class PatchFiles extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputDirs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /**
     * The jar scanned for {@code @Replaces} and {@code @Redirect}
     * declarations — the project's own classes jar. Plain {@code @InputFile},
     * not {@code @Classpath}: the rules live in runtime-invisible
     * annotations, which classpath normalization cannot see — a rules-only
     * edit must dirty this input.
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPatches();

    @TaskAction
    public void run() throws IOException {
        List<String> unused = new ClassPatcher(getPatches().get().getAsFile())
                .patchDirs(getInputDirs().getFiles(), getOutputDir().get().getAsFile());
        if (!unused.isEmpty()) {
            getLogger().warn("{} @Redirect handlers never matched a call site:\n - {}",
                    unused.size(), String.join("\n - ", unused));
        }
    }
}
