package net.morphismmc.retrodfu.task;

import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.inject.Inject;
import net.morphismmc.retrodfu.ClassPatcher;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;

/**
 * Rewrites {@link #getInputFile()} with {@link ClassPatcher#patchJar} and then
 * repacks the result through the jar copy machinery, so anything configured
 * with {@code from} (the project's own classes, licenses) is merged into
 * {@link #getArchiveFile()}. {@link #getTransformers()} only supplies the
 * ClassTransform transformer classes — they are registered, not merged.
 *
 * <p>Shaped after JvmDG's jar tasks: transform to a temp jar, carry the input
 * manifest into this task's own, then {@code from(zipTree(temp))} +
 * {@code copy()}. {@code module-info.class} and {@code META-INF/versions/**}
 * are dropped at the repack: a Java 8 jar has no use for them and scanJar
 * forbids them.
 */
@CacheableTask
public abstract class PatchJar extends Jar implements PatchTask {

    @Inject
    public abstract ArchiveOperations getArchiveOps();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputFile();

    @TaskAction
    public void run() throws IOException {
        File tempOutput = tempFile("patchedInput.jar");
        if (tempOutput.exists() && !tempOutput.delete()) {
            throw new IOException("Failed to delete existing temp output file: " + tempOutput);
        }

        new ClassPatcher(
                        getTransformers().getFiles(),
                        getTransformerConfig().get(),
                        getClasspath().getFiles())
                .patchJar(getInputFile().get().getAsFile(), tempOutput);

        // Carry the input's manifest into this task's own, as JvmDG does, and
        // keep it out of the zip source so it lands exactly once.
        try (JarFile jf = new JarFile(tempOutput)) {
            JarEntry mf = jf.getJarEntry("META-INF/MANIFEST.MF");
            if (mf != null) {
                File tmp = tempFile("input-manifest.MF");
                try (InputStream in = jf.getInputStream(mf);
                        OutputStream out = new FileOutputStream(tmp)) {
                    in.transferTo(out);
                }
                getManifest().from(tmp);
            }
        }

        exclude("META-INF/MANIFEST.MF");
        exclude("META-INF/versions/**");
        exclude("module-info.class");
        from(getArchiveOps().zipTree(tempOutput));
        copy();
    }

    private File tempFile(String name) {
        return new File(getTemporaryDir(), name);
    }
}
