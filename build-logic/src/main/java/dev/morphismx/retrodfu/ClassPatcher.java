package dev.morphismx.retrodfu;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.additionalclassprovider.DelegatingClassProvider;
import net.lenni0451.classtransform.additionalclassprovider.LazyFileClassProvider;
import net.lenni0451.classtransform.additionalclassprovider.PathClassProvider;
import net.lenni0451.classtransform.transformer.IBytecodeTransformer;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.mappings.MapRemapper;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * Patches class bytecode using ClassTransform.
 *
 * <p>A mixin-style JSON config declares the patch: {@code renames} whole-type
 * renames applied to every input class ahead of the injection transformers,
 * and {@code transformers}, {@code @CTransformer} classes loaded as bytecode
 * for {@link TransformerManager}.</p>
 *
 * <p>Transformer targets must exist in the input; missing targets fail
 * the build rather than silently producing an incomplete patch.</p>
 */
public final class ClassPatcher {

    private final List<ClassNode> transformers;
    private final Map<String, String> renames;
    private final List<File> classpath;

    /**
     * Creates a patcher from a mixin-style transformer configuration.
     *
     * <p>The first root containing {@code config} supplies the configuration.
     * Transformer classes are resolved against all roots in order.</p>
     *
     * @param roots     directories and JARs containing transformer classes
     * @param config    transformer configuration path
     * @param classpath dependencies used by the frame computer
     */
    public ClassPatcher(Collection<File> roots, String config, Collection<File> classpath) throws IOException {
        byte[] configBytes = findResource(roots, config);
        if (configBytes == null) {
            throw new IOException("Transformer config '" + config + "' not found in " + roots);
        }

        Config cfg;
        try {
            cfg = new Gson().fromJson(new String(configBytes, StandardCharsets.UTF_8), Config.class);
        } catch (JsonParseException e) {
            throw new IOException("Transformer config '" + config + "' is malformed", e);
        }
        if (cfg == null || cfg.packageName() == null) {
            throw new IOException("Transformer config '" + config + "' has no 'package'");
        }

        this.transformers = loadTransformers(roots, config, cfg);
        this.renames = cfg.renames() == null ? Map.of() : Map.copyOf(cfg.renames());
        this.classpath = List.copyOf(classpath);
    }

    /**
     * Loads every {@code @CTransformer} class the config names as bytecode.
     */
    private static List<ClassNode> loadTransformers(Collection<File> roots, String config, Config cfg)
            throws IOException {
        List<String> names = cfg.transformers();
        if (names == null) return List.of();

        List<ClassNode> transformers = new ArrayList<>(names.size());
        for (String name : names) {
            String dotName = cfg.packageName() + "." + name;
            byte[] bytes = findResource(roots, Utils.slash(dotName) + ".class");

            if (bytes == null) {
                throw new IOException("Transformer '" + dotName + "' of '" + config + "' not found in " + roots);
            }
            transformers.add(ASMUtils.fromBytes(bytes));
        }
        return transformers;
    }

    /**
     * Finds a resource in the first matching root.
     *
     * <p>Supports both exploded class directories and JAR files.</p>
     */
    private static byte[] findResource(Collection<File> roots, String name) throws IOException {
        for (File root : roots) {
            if (root.isDirectory()) {
                Path file = root.toPath().resolve(name);
                if (Files.isRegularFile(file)) return Files.readAllBytes(file);
            } else if (root.isFile()) {
                try (JarFile jar = new JarFile(root)) {
                    JarEntry entry = jar.getJarEntry(name);
                    if (entry != null) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Providers read classes lazily: directories via {@link PathClassProvider},
     * jars via {@link LazyFileClassProvider}, which also opens the jar only on
     * first use and stays closeable afterwards.
     */
    private static void addFileProviders(List<IClassProvider> providers, Collection<File> files) {
        for (File file : files) {
            if (file.isDirectory()) providers.add(new PathClassProvider(file.toPath()));
            else providers.add(new LazyFileClassProvider(List.of(file)));
        }
    }

    /**
     * Builds the whole-type rename pass: every {@code renames} key becomes its
     * value inside class files, applied before the injection transformers.
     * The renames are pure renames — a replacement has the same kind as the
     * original, so opcodes are not rewritten.
     */
    private static IBytecodeTransformer renameTransformer(Map<String, String> renames) {
        MapRemapper remapper = new MapRemapper();
        renames.forEach((from, to) -> remapper.addClassMapping(Utils.slash(from), Utils.slash(to)));
        // A renamed type must occur in the constant pool verbatim, so a plain
        // byte search spares parsing and rewriting every unaffected class.
        List<byte[]> needles = renames.keySet().stream()
                .map(from -> Utils.slash(from).getBytes(StandardCharsets.UTF_8))
                .toList();
        return (className, bytecode, frames) -> {
            if (needles.stream().noneMatch(needle -> contains(bytecode, needle))) return null;
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            byte[] remapped = writer.toByteArray();
            return Arrays.equals(remapped, bytecode) ? null : remapped;
        };
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Patches a JAR, transforming class entries and copying resources unchanged.
     *
     * @param input  source JAR
     * @param output destination JAR
     */
    public void patchJar(File input, File output) throws IOException {
        Path target = output.toPath();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (Transform tx = newTransform(List.of(input));
                JarFile jar = new JarFile(input);
                JarOutputStream out = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                out.putNextEntry(new JarEntry(entry));

                if (!entry.isDirectory()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        out.write(tx.apply(entry.getName(), in.readAllBytes()));
                    }
                }
                out.closeEntry();
            }
            tx.checkCoverage();
        }
    }

    /**
     * Patches directory trees, preserving relative paths.
     *
     * <p>Roots are processed in order; later roots overwrite earlier files
     * with the same relative path.</p>
     *
     * @param inputs source directories
     * @param output destination directory
     */
    public void patchDirs(Collection<File> inputs, File output) throws IOException {
        Path outputRoot = output.toPath().toAbsolutePath().normalize();
        List<File> roots = List.copyOf(inputs);

        for (File input : roots) {
            Path root = input.toPath().toAbsolutePath().normalize();
            if (outputRoot.startsWith(root)) {
                throw new IllegalArgumentException("Output directory must not be inside an input directory: " + output);
            }
        }

        // Gradle does not clear a task's output directory between runs, so a
        // class dropped from the input would otherwise survive in the output.
        deleteContents(outputRoot);

        try (Transform tx = newTransform(roots)) {
            for (File input : roots) {
                Path root = input.toPath();
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Path relative = root.relativize(file);
                        Path target = outputRoot.resolve(relative);

                        Files.createDirectories(target.getParent());
                        Files.write(
                                target,
                                tx.apply(
                                        relative.toString().replace(File.separatorChar, '/'),
                                        Files.readAllBytes(file)));
                    }
                }
            }
            tx.checkCoverage();
        }
    }

    /**
     * Empties {@code dir} but keeps the directory itself; a missing directory
     * is left alone.
     */
    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) Files.delete(p);
            }
        }
    }

    /**
     * Creates an isolated transformation run.
     *
     * <p>Each run gets its own manager and provider, preventing transformation
     * state and class caches from leaking between inputs.</p>
     */
    private Transform newTransform(List<File> inputs) {
        List<IClassProvider> providers = new ArrayList<>();
        addFileProviders(providers, inputs);
        addFileProviders(providers, classpath);
        providers.add(new BasicClassProvider());
        IClassProvider provider = providers.size() == 1
                ? providers.get(0)
                : new DelegatingClassProvider(
                        providers.get(0), providers.subList(1, providers.size()).toArray(new IClassProvider[0]));

        TransformerManager manager = new TransformerManager(provider);
        // EXIT would terminate the Gradle daemon; propagate failures instead.
        manager.setFailStrategy(FailStrategy.THROW);

        if (!renames.isEmpty()) {
            manager.addBytecodeTransformer(renameTransformer(renames));
        }
        transformers.forEach(manager::addTransformer);
        return new Transform(providers, manager);
    }

    /**
     * The {@code *.transformers.json} shape Gson parses; absent lists mean none.
     */
    private record Config(
            @SerializedName("package") String packageName, Map<String, String> renames, List<String> transformers) {}

    /**
     * A single transformation run, tracking classes seen in the input.
     */
    private static final class Transform implements AutoCloseable {
        private final List<IClassProvider> providers;
        private final TransformerManager manager;
        private final Set<String> seen = new HashSet<>();

        private Transform(List<IClassProvider> providers, TransformerManager manager) {
            this.providers = providers;
            this.manager = manager;
        }

        /**
         * Transforms a class entry; non-class resources pass through unchanged.
         */
        byte[] apply(String entryName, byte[] bytes) {
            if (!Utils.isClassFile(entryName)) return bytes;

            String name = entryName.substring(0, entryName.length() - 6).replace('/', '.');
            seen.add(name);

            byte[] transformed = manager.transform(name, bytes);
            return transformed == null ? bytes : transformed;
        }

        /**
         * Ensures every registered transformer target appeared in the input.
         *
         * <p>This prevents missing targets from silently producing incomplete
         * patches.</p>
         */
        void checkCoverage() {
            for (String target : manager.getTransformedClasses()) {
                if (!seen.contains(target)) {
                    throw new IllegalStateException("Transformer target '" + target + "' is not in the input");
                }
            }
        }

        @Override
        public void close() throws IOException {
            for (IClassProvider provider : providers) {
                if (provider instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) provider).close();
                    } catch (Exception e) {
                        throw new IOException("Failed to close class provider", e);
                    }
                }
            }
        }
    }
}
