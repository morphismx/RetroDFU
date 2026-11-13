package net.morphismmc.retrodfu.task;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.morphismmc.retrodfu.ClassScanner;
import net.morphismmc.retrodfu.ClassScanner.Needle;
import net.morphismmc.retrodfu.ClassScanner.Rules;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/**
 * Runs {@link ClassScanner} over the final jar and writes a report file, which
 * makes the task up-to-date checkable and cacheable. The gate rules are task
 * inputs configured by the build script; {@link Baseline} pairs an owner prefix
 * with the jars that every matching member reference must resolve against.
 */
@CacheableTask
public abstract class ScanJar extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    /**
     * Entry prefixes jar entries must live under; parent directories of an
     * allowed prefix are allowed too.
     */
    @Input
    public abstract ListProperty<String> getEntryPrefixes();

    /**
     * Entry prefixes jar entries must never live under.
     */
    @Input
    public abstract ListProperty<String> getForbiddenEntryPrefixes();

    /**
     * The maximum class-file major version.
     */
    @Input
    public abstract Property<Integer> getMaxMajor();

    /**
     * Entry prefixes exempt from the forbidden-needle byte scan.
     */
    @Input
    public abstract ListProperty<String> getScanExemptPrefixes();

    /**
     * Contexts exempting a needle match: a needle that is the tail of one of
     * these is payload, not a leak.
     */
    @Input
    public abstract ListProperty<String> getExemptContexts();

    /**
     * The forbidden needles.
     */
    @Nested
    public abstract ListProperty<ForbiddenNeedle> getForbidden();

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
            List<File> jars = new ArrayList<>(b.getJars().getFiles());
            Collections.sort(jars);
            apiJars.put(b.getPrefix().get(), jars);
        }

        List<Needle> forbidden = getForbidden().get().stream()
                .map(n -> new Needle(n.getText().get(), n.getQualifiers().get()))
                .toList();
        Rules rules = new Rules(
                getEntryPrefixes().get(),
                getForbiddenEntryPrefixes().get(),
                getMaxMajor().get(),
                getScanExemptPrefixes().get(),
                getExemptContexts().get(),
                forbidden);

        // Scan before touching the report, so an I/O failure cannot leave a
        // truncated report behind.
        ClassScanner.Result result = ClassScanner.scan(
                getInputJar().get().getAsFile(), rules, getSelfPrefixes().get(), apiJars);

        File report = getReportFile().get().getAsFile();
        File parent = report.getParentFile();
        if (parent != null) parent.mkdirs();
        try (PrintWriter w = new PrintWriter(report, StandardCharsets.UTF_8)) {
            if (result.problems().isEmpty()) {
                w.println("scanJar OK: " + result.refCount() + " refs checked");
            } else {
                w.println("scanJar FAILED (" + result.problems().size() + "):");
                for (String p : result.problems()) w.println("  - " + p);
            }
        }
        if (!result.problems().isEmpty()) {
            throw new GradleException("scanJar failed (" + result.problems().size() + " problems, see " + report
                    + "):\n - " + String.join("\n - ", result.problems()));
        }
        getLogger().lifecycle("scanJar OK: {} refs checked", result.refCount());
    }

    /**
     * A forbidden byte string; non-empty qualifiers mean a match only counts
     * when the token starting at it contains one of them.
     */
    public abstract static class ForbiddenNeedle {
        @Input
        public abstract Property<String> getText();

        @Input
        public abstract ListProperty<String> getQualifiers();
    }

    /**
     * The jars one API namespace's member refs are checked against.
     */
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
