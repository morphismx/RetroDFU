package dev.morphismx.retrodfu.task;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.codehaus.mojo.animal_sniffer.ClassListBuilder;
import org.codehaus.mojo.animal_sniffer.SignatureChecker;
import org.codehaus.mojo.animal_sniffer.logging.Logger;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;

/**
 * Runs animal-sniffer's {@link SignatureChecker} on one jar: every referenced
 * API must exist in the signature file or in the extra API jars.
 *
 * <p>Follows the ant task's flow. The packages of the checked jar are added to
 * the ignore set first, so references within the jar are not held against the
 * signature.
 */
@CacheableTask
public abstract class CheckSignature extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    /**
     * The {@code .signature} file (e.g. org.codehaus.mojo.signature:java18).
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSignatureFile();

    /**
     * Extra jars whose API surface counts as allowed (e.g. the game dependency sets).
     */
    @Classpath
    public abstract ConfigurableFileCollection getApiJars();

    /**
     * Package/class names skipped entirely (wildcards follow animal-sniffer's rules).
     */
    @Input
    public abstract SetProperty<String> getIgnores();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        File jar = getInputJar().get().getAsFile();
        List<String> problems = new ArrayList<>();
        // Violations arrive via warn/error; collecting them feeds the report and failure message.
        Logger logger = new Logger() {
            public void info(String s) {
                getLogger().info(s);
            }

            public void info(String s, Throwable t) {
                getLogger().info(s, t);
            }

            public void debug(String s) {
                getLogger().debug(s);
            }

            public void debug(String s, Throwable t) {
                getLogger().debug(s, t);
            }

            public void warn(String s) {
                problems.add(s);
                getLogger().warn(s);
            }

            public void warn(String s, Throwable t) {
                problems.add(s);
                getLogger().warn(s, t);
            }

            public void error(String s) {
                problems.add(s);
                getLogger().error(s);
            }

            public void error(String s, Throwable t) {
                problems.add(s);
                getLogger().error(s, t);
            }
        };

        // API-jar packages join the ignore set as in the ant task — that is what counts classpath deps as provided API.
        Set<String> ignores = new LinkedHashSet<>(getIgnores().get());
        ClassListBuilder packages = new ClassListBuilder(logger);
        packages.process(jar);
        for (File api : getApiJars().getFiles()) packages.process(api);
        ignores.addAll(packages.getPackages());

        boolean broken;
        try (InputStream sig = new FileInputStream(getSignatureFile().get().getAsFile())) {
            SignatureChecker checker = new SignatureChecker(sig, ignores, logger);
            checker.setSourcePath(new ArrayList<>(getApiJars().getFiles()));
            checker.process(new File[] {jar});
            broken = checker.isSignatureBroken();
        }

        File report = getReportFile().get().getAsFile();
        File parent = report.getParentFile();
        if (parent != null) parent.mkdirs();
        try (PrintWriter w = new PrintWriter(report, StandardCharsets.UTF_8)) {
            if (broken) {
                w.println("checkSignature FAILED (" + problems.size() + " violations)");
            } else if (!problems.isEmpty()) {
                w.println("checkSignature WARNINGS (" + problems.size() + ")");
            } else {
                w.println("checkSignature OK");
            }
            for (String p : problems) w.println("  - " + p);
        }
        if (broken) {
            throw new GradleException("checkSignature failed (" + problems.size() + " violations, see " + report
                    + "):\n - " + String.join("\n - ", problems));
        }
        getLogger().lifecycle("{}: OK ({} bytes)", getName(), jar.length());
    }
}
