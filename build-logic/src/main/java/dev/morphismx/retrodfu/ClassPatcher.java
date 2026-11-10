package dev.morphismx.retrodfu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.transformer.IBytecodeTransformer;
import net.lenni0451.classtransform.transformer.IPostTransformer;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Patches class bytecode with ClassTransform:
 *
 * <ul>
 *   <li>The bytecode transformers named by the config's
 *       {@code bytecodeTransformers} list — live objects instantiated here —
 *       rewrite every input class first (whole-type renames).</li>
 *   <li>The transformer classes (mixin-style {@code @CTransformer} classes
 *       with {@code @CRedirect} methods) retarget the annotated call sites.
 *       Handlers are copied into the target class, so a misspelled method or
 *       target fails the build instead of silently passing through.</li>
 * </ul>
 */
public final class ClassPatcher {
    private final List<ClassNode> transformers;
    private final List<IBytecodeTransformer> bytecodeTransformers;
    private final List<File> classpath;

    /**
     * Builds a patcher from the transformer config named {@code configPath} in
     * {@code transformerRoots}: a mixin-style JSON with {@code package}, a
     * {@code transformers} class-name list and a {@code bytecodeTransformers}
     * class-name list.
     *
     * <p>One patcher can patch multiple inputs; transformer classes are read
     * once here so a bad config fails before any output is written.</p>
     *
     * @param classpath jars and class directories the frame computer may
     *                  resolve classes from (dependencies of the input plus
     *                  the project's own classes)
     */
    public ClassPatcher(Collection<File> transformerRoots, String transformerConfig,
                        Collection<File> classpath)
            throws IOException {
        TransformerClasses loaded = readTransformers(transformerRoots, transformerConfig);
        this.transformers = loaded.transformers();
        this.bytecodeTransformers = loaded.bytecodeTransformers();
        this.classpath = List.copyOf(classpath);
    }

    /**
     * Patches {@code inFile} into {@code outFile}: class entries are
     * transformed, other resources copied verbatim.
     *
     * @throws IllegalStateException if a transformer target never appeared in
     *                               the input
     */
    public void patchJar(File inFile, File outFile) throws IOException {
        Path parent = outFile.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Transform tx = newTransform(List.of(inFile))) {
            try (JarFile in = new JarFile(inFile);
                 JarOutputStream out = new JarOutputStream(
                         new BufferedOutputStream(Files.newOutputStream(outFile.toPath())))) {

                for (Enumeration<JarEntry> entries = in.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    out.putNextEntry(new JarEntry(entry));

                    if (!entry.isDirectory()) {
                        try (InputStream is = in.getInputStream(entry)) {
                            if (Utils.isClassFile(entry.getName())) {
                                out.write(tx.apply(entry.getName(), is.readAllBytes()));
                            } else {
                                is.transferTo(out);
                            }
                        }
                    }

                    out.closeEntry();
                }
            }
            tx.checkCoverage();
        }
    }

    /**
     * Directory-tree variant of {@link #patchJar(File, File)}.
     *
     * <p>Files are written at their relative paths. Later roots overwrite
     * earlier roots on name clashes, preserving classpath order.</p>
     */
    public void patchDirs(Collection<File> inDirs, File outDir) throws IOException {
        Path outputRoot = outDir.toPath();

        try (Transform tx = newTransform(List.copyOf(inDirs))) {
            for (File dir : inDirs) {
                Path root = dir.toPath();

                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Path relative = root.relativize(file);
                        Path output = outputRoot.resolve(relative);

                        Files.createDirectories(output.getParent());
                        Files.write(output, tx.apply(
                                relative.toString().replace(File.separatorChar, '/'),
                                Files.readAllBytes(file)
                        ));
                    }
                }
            }
            tx.checkCoverage();
        }
    }

    /**
     * Reads the transformer classes named by the {@code configPath} JSON —
     * mixin-style {@code package} + {@code transformers}/{@code
     * bytecodeTransformers} name lists. The first root carrying the config
     * supplies it; each class file resolves against the roots in order.
     */
    private static TransformerClasses readTransformers(Collection<File> roots, String configPath)
            throws IOException {
        Path config = null;
        for (File root : roots) {
            Path candidate = root.toPath().resolve(configPath);
            if (Files.isRegularFile(candidate)) {
                config = candidate;
                break;
            }
        }
        if (config == null) {
            throw new IOException("Transformer config '" + configPath + "' not found in " + roots);
        }

        JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        JsonElement pkg = json.get("package");
        if (pkg == null) {
            throw new IOException("Transformer config '" + configPath + "' has no 'package'");
        }
        List<ClassNode> transformers = new ArrayList<>();
        JsonElement list = json.get("transformers");
        if (list != null) {
            for (JsonElement element : list.getAsJsonArray()) {
                String className = pkg.getAsString() + "." + element.getAsString();
                Path file = findClassFile(roots, className);
                if (file == null) {
                    throw new IOException("Transformer '" + className + "' of '" + configPath
                            + "' not found in " + roots);
                }
                transformers.add(ASMUtils.fromBytes(Files.readAllBytes(file)));
            }
        }
        List<IBytecodeTransformer> bytecodeTransformers =
                instantiateBytecodeTransformers(roots, pkg.getAsString(),
                        json.get("bytecodeTransformers"));
        return new TransformerClasses(transformers, bytecodeTransformers);
    }

    /**
     * Instantiates the {@code bytecodeTransformers} entries through a
     * classloader over the transformer roots — they run in this JVM, unlike
     * the {@code @CTransformer} classes which are only read as bytecode.
     */
    private static List<IBytecodeTransformer> instantiateBytecodeTransformers(
            Collection<File> roots, String pkg, JsonElement list) throws IOException {
        if (list == null) {
            return List.of();
        }

        URL[] urls = new URL[roots.size()];
        int i = 0;
        for (File root : roots) {
            try {
                urls[i++] = root.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }
        try (URLClassLoader loader =
                     new URLClassLoader(urls, ClassPatcher.class.getClassLoader())) {
            List<IBytecodeTransformer> instances = new ArrayList<>();
            for (JsonElement element : list.getAsJsonArray()) {
                String className = pkg + "." + element.getAsString();
                try {
                    Object instance = loader.loadClass(className)
                            .getDeclaredConstructor().newInstance();
                    instances.add((IBytecodeTransformer) instance);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    throw new IOException("Bytecode transformer '" + className
                            + "' failed to instantiate", e);
                }
            }
            return instances;
        }
    }

    /**
     * The two transformer kinds one config supplies: {@code @CTransformer}
     * classes as bytecode, bytecode transformers as live instances.
     */
    private record TransformerClasses(List<ClassNode> transformers,
                                      List<IBytecodeTransformer> bytecodeTransformers) {
    }

    private static Path findClassFile(Collection<File> roots, String dotName) {
        String relative = Utils.slash(dotName) + ".class";
        for (File root : roots) {
            Path file = root.toPath().resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private Transform newTransform(List<File> inputRoots) {
        Provider provider = new Provider(inputRoots, classpath);
        TransformerManager manager = new TransformerManager(provider);
        // EXIT would kill the Gradle daemon; let the task fail instead.
        manager.setFailStrategy(FailStrategy.THROW);

        for (IBytecodeTransformer transformer : bytecodeTransformers) {
            manager.addBytecodeTransformer(transformer);
        }
        manager.addPostTransformConsumer(new IPostTransformer() {
            @Override
            public void transform(String className, byte[] bytecode) {
            }

            @Override
            public byte[] replace(String className, byte[] bytecode) {
                return stripTransformerAnnotations(bytecode);
            }
        });
        for (ClassNode transformer : transformers) {
            manager.addTransformer(transformer);
        }
        return new Transform(provider, manager);
    }

    /**
     * Removes every {@code net.lenni0451.classtransform} annotation from the
     * class: methods copied in by the transformers keep their {@code @CRedirect}
     * and gain an {@code InjectionInfo} marker, none of which may leak into the
     * shipped jar.
     *
     * @return the stripped bytes, or {@code null} when nothing had to go
     */
    private static byte[] stripTransformerAnnotations(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean stripped = strip(node.visibleAnnotations) | strip(node.invisibleAnnotations)
                | strip(node.visibleTypeAnnotations) | strip(node.invisibleTypeAnnotations);
        for (MethodNode m : node.methods) {
            stripped |= strip(m.visibleAnnotations) | strip(m.invisibleAnnotations)
                    | strip(m.visibleTypeAnnotations) | strip(m.invisibleTypeAnnotations)
                    | strip(m.visibleParameterAnnotations) | strip(m.invisibleParameterAnnotations);
        }
        for (FieldNode f : node.fields) {
            stripped |= strip(f.visibleAnnotations) | strip(f.invisibleAnnotations)
                    | strip(f.visibleTypeAnnotations) | strip(f.invisibleTypeAnnotations);
        }
        if (node.recordComponents != null) {
            for (RecordComponentNode c : node.recordComponents) {
                stripped |= strip(c.visibleAnnotations) | strip(c.invisibleAnnotations)
                        | strip(c.visibleTypeAnnotations) | strip(c.invisibleTypeAnnotations);
            }
        }
        if (!stripped) return null;

        // The node already carries the computed frames; COMPUTE_MAXS keeps them
        // verbatim while writing the stripped class back out.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return cw.toByteArray();
    }

    private static boolean strip(List<? extends AnnotationNode> annotations) {
        return annotations != null && annotations.removeIf(
                a -> a.desc.startsWith("Lnet/lenni0451/classtransform/"));
    }

    private static boolean strip(List<AnnotationNode>[] annotations) {
        if (annotations == null) return false;
        boolean stripped = false;
        for (List<AnnotationNode> list : annotations) stripped |= strip(list);
        return stripped;
    }

    /**
     * A single patch run: the ClassTransform manager plus the input classes it
     * has seen, so untriggered transformer targets can be reported at the end.
     */
    private static final class Transform implements AutoCloseable {
        private final Provider provider;
        private final TransformerManager manager;
        private final Set<String> seen = new HashSet<>();

        Transform(Provider provider, TransformerManager manager) {
            this.provider = provider;
            this.manager = manager;
        }

        /**
         * Transforms one class entry. Non-class bytes pass through untouched.
         */
        byte[] apply(String entryName, byte[] bytes) {
            if (!Utils.isClassFile(entryName)) return bytes;
            String dotName = entryName.substring(0, entryName.length() - ".class".length())
                    .replace('/', '.');
            seen.add(dotName);
            byte[] out = manager.transform(dotName, bytes);
            return out == null ? bytes : out;
        }

        /**
         * Fails when a registered transformer target never appeared in the
         * input — the silent no-op the old unused-handler warning allowed.
         */
        void checkCoverage() {
            for (String target : manager.getTransformedClasses()) {
                if (!seen.contains(target)) {
                    throw new IllegalStateException(
                            "Transformer target '" + target + "' is not in the input");
                }
            }
        }

        @Override
        public void close() {
            provider.close();
        }
    }

    /**
     * Resolves class bytes from the input roots and the patch classpath, in
     * order, falling back to {@link BasicClassProvider} for JDK classes.
     */
    private static final class Provider implements IClassProvider {
        private final List<File> dirs = new ArrayList<>();
        private final List<File> jarPaths = new ArrayList<>();
        private final Map<File, JarFile> jars = new HashMap<>();
        private final IClassProvider jdk = new BasicClassProvider();

        Provider(List<File> inputRoots, List<File> classpath) {
            for (File root : concat(inputRoots, classpath)) {
                if (root.isDirectory()) dirs.add(root);
                else if (root.isFile()) jarPaths.add(root);
            }
        }

        private static List<File> concat(List<File> a, List<File> b) {
            List<File> all = new ArrayList<>(a.size() + b.size());
            all.addAll(a);
            all.addAll(b);
            return all;
        }

        @Override
        public byte[] getClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try {
                for (File dir : dirs) {
                    Path p = dir.toPath().resolve(path);
                    if (Files.isRegularFile(p)) return Files.readAllBytes(p);
                }
                for (File jarPath : jarPaths) {
                    JarFile jar = jars.computeIfAbsent(jarPath, Provider::openJar);
                    JarEntry entry = jar.getJarEntry(path);
                    if (entry != null) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            return in.readAllBytes();
                        }
                    }
                }
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
            return jdk.getClass(name);
        }

        private static JarFile openJar(File file) {
            try {
                return new JarFile(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, Supplier<byte[]>> getAllClasses() {
            // Only needed for wildcard transformer names, which we never use.
            throw new UnsupportedOperationException();
        }

        void close() {
            for (JarFile jar : jars.values()) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
