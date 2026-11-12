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
 * makes the task up-to-date checkable and cacheable. The gate rules live in
 * {@code RULES}; {@link Baseline} pairs an owner prefix with the jars that
 * every matching member reference must resolve against.
 */
@CacheableTask
public abstract class ScanJar extends DefaultTask {
    /**
     * A needle naming the JvmDG stub/exception trees counts as an unrelocated
     * leak; other mentions are incidental strings.
     */
    private static final List<String> STUB_OR_EXC = List.of("/stub/", "/exc/", ".stub.", ".exc.");

    private static final Rules RULES = new Rules(
            List.of(
                    "com/mojang/datafixers/", "com/mojang/serialization/",
                    "net/morphismmc/retrodfu/", "META-INF/"),
            // Multi-release content would sneak Java 9+ classes past the major check.
            List.of("META-INF/versions/"),
            52,
            // Our own classes legitimately carry the needles: the JvmDG stubs
            // name their unrelocated selves and transformer-facing javadocs
            // name slf4j. Member resolution still runs on every class.
            List.of("net/morphismmc/retrodfu/"),
            // A needle that is the tail of a relocated stub path is the
            // payload, not a leak.
            List.of("net/morphismmc/retrodfu/shadow/jvmdg/"),
            List.of(
                    new Needle("org/slf4j"),
                    new Needle("org.slf4j"),
                    new Needle("xyz/wagyourtail", STUB_OR_EXC),
                    new Needle("xyz.wagyourtail", STUB_OR_EXC),
                    new Needle("java/lang/Record"),
                    new Needle("java/lang/runtime/"),
                    new Needle("StringConcatFactory")));

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
            List<File> jars = new ArrayList<>(b.getJars().getFiles());
            Collections.sort(jars);
            apiJars.put(b.getPrefix().get(), jars);
        }

        // Scan before touching the report, so an I/O failure cannot leave a
        // truncated report behind.
        ClassScanner.Result result = ClassScanner.scan(
                getInputJar().get().getAsFile(), RULES, getSelfPrefixes().get(), apiJars);

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
