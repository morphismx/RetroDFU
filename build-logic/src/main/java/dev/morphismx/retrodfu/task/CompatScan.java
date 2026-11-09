package dev.morphismx.retrodfu.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link dev.morphismx.retrodfu.CompatScan} over the final jar and writes a report file, which
 * makes the task up-to-date checkable and cacheable. Each {@link Baseline}
 * pairs an owner prefix with the jars that every matching member reference
 * must resolve against.
 */
@CacheableTask
public abstract class CompatScan extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    /**
     * In-jar namespaces whose member refs must resolve inside the jar.
     */
    @Input
    public abstract ListProperty<String> getSelfPrefixes();

    /**
     * One baseline per checked API namespace, such as {@code com/google/gson}
     * with the gson jars of every supported Minecraft version.
     */
    @Nested
    public abstract ListProperty<Baseline> getBaselines();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        Map<String, List<File>> apiJars = new LinkedHashMap<>();
        for (Baseline b : getBaselines().get()) {
            apiJars.put(b.getPrefix().get(), new ArrayList<>(b.getJars().getFiles()));
        }

        File report = getReportFile().get().getAsFile();
        dev.morphismx.retrodfu.CompatScan.Result result;
        try (PrintWriter w = new PrintWriter(report, StandardCharsets.UTF_8)) {
            result = dev.morphismx.retrodfu.CompatScan.scan(getInputJar().get().getAsFile(), getSelfPrefixes().get(), apiJars);
            if (result.problems().isEmpty()) {
                w.println("CompatScan OK: " + result.refCount() + " refs checked");
            } else {
                w.println("CompatScan FAILED (" + result.problems().size() + "):");
                for (String p : result.problems()) w.println("  - " + p);
            }
        }
        if (!result.problems().isEmpty()) {
            throw new GradleException("CompatScan failed (" + result.problems().size() + " problems, see "
                    + report + "):\n - " + String.join("\n - ", result.problems()));
        }
        getLogger().lifecycle("CompatScan OK: {} refs checked", result.refCount());
    }

    public abstract static class Baseline {
        /**
         * Owner prefix whose member refs must resolve, e.g. com/google/gson.
         */
        @Input
        public abstract Property<String> getPrefix();

        /**
         * The jars a matching member reference must resolve against, all of them.
         */
        @Classpath
        public abstract ConfigurableFileCollection getJars();
    }
}
