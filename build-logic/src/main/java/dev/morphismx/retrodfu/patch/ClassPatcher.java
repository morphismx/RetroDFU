package dev.morphismx.retrodfu.patch;

import dev.morphismx.retrodfu.Utils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;


/**
 * Patches class bytecode with the rules the patch classes declare
 * ({@link PatchSpec}):
 *
 * <ul>
 *   <li>{@code @Replaces}: a whole-type rename, applied by
 *       {@link ClassRemapper} at every type position. Opcodes are not
 *       rewritten — a replacement has the same kind as the original.</li>
 *   <li>{@code @Redirect}: a matching invoke becomes an
 *       {@code INVOKESTATIC} of the handler, with an instance call's
 *       receiver becoming the first argument. Matching uses the call's
 *       literal owner; subtype calls and method references in
 *       {@code invokedynamic} are left for CompatScan to report.</li>
 * </ul>
 */
public final class ClassPatcher {
    private final Remapper remapper;
    private final Map<String, List<PatchSpec.Redirect>> redirects;
    private final Set<PatchSpec.Redirect> applied = new HashSet<>();

    /**
     * Builds a patcher from the {@code @Replaces} and {@code @Redirect}
     * declarations in {@code patches}.
     *
     * <p>One patcher can patch multiple inputs; applied handlers accumulate
     * across all inputs.</p>
     */
    public ClassPatcher(File patches) throws IOException {
        this(PatchSpec.scan(patches));
    }

    private ClassPatcher(PatchSpec spec) {
        this.remapper = new SimpleRemapper(Opcodes.ASM9, spec.replaces);
        this.redirects = spec.redirects;
    }

    /**
     * Patches {@code inFile} into {@code outFile}: class entries are
     * rewritten, other resources copied verbatim.
     *
     * @return descriptions of redirect handlers that never matched a call site
     */
    public List<String> patchJar(File inFile, File outFile) throws IOException {
        Path parent = outFile.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (JarFile in = new JarFile(inFile);
             JarOutputStream out = new JarOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(outFile.toPath())))) {

            for (Enumeration<JarEntry> entries = in.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                out.putNextEntry(new JarEntry(entry));

                if (!entry.isDirectory()) {
                    try (InputStream is = in.getInputStream(entry)) {
                        if (Utils.isClassFile(entry.getName())) {
                            out.write(patchClass(entry.getName(), is.readAllBytes()));
                        } else {
                            is.transferTo(out);
                        }
                    }
                }

                out.closeEntry();
            }
        }

        return unusedRedirects();
    }

    /**
     * Directory-tree variant of {@link #patchJar(File, File)}.
     *
     * <p>Files are written at their relative paths. Later roots overwrite
     * earlier roots on name clashes, preserving classpath order.</p>
     *
     * @return descriptions of redirect handlers that never matched a call site
     */
    public List<String> patchDirs(Collection<File> inDirs, File outDir) throws IOException {
        Path outputRoot = outDir.toPath();

        for (File dir : inDirs) {
            Path root = dir.toPath();

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path relative = root.relativize(file);
                    Path output = outputRoot.resolve(relative);

                    Files.createDirectories(output.getParent());
                    Files.write(output, patchClass(
                            relative.toString().replace(File.separatorChar, '/'),
                            Files.readAllBytes(file)
                    ));
                }
            }
        }

        return unusedRedirects();
    }

    private List<String> unusedRedirects() {
        // A handler that never matched usually means a misspelled descriptor
        // or an upstream call that went away; the caller logs these as warnings.
        List<String> unused = new ArrayList<>();

        redirects.forEach((target, handlers) -> {
            for (PatchSpec.Redirect handler : handlers) {
                if (!applied.contains(handler)) {
                    unused.add(handler.handlerOwner() + "."
                            + handler.handlerName() + handler.handlerDesc()
                            + " (target " + target + ")");
                }
            }
        });

        return unused;
    }

    byte[] patchClass(String entryName, byte[] bytes) {
        // patchDirs feeds every walked file through here; resources pass verbatim.
        if (!Utils.isClassFile(entryName)) {
            return bytes;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassRemapper(cw, remapper) {
            @Override
            protected MethodVisitor createMethodRemapper(MethodVisitor mv) {
                return new RedirectMV(super.createMethodRemapper(mv));
            }
        }, 0);

        return cw.toByteArray();
    }

    private PatchSpec.Redirect findRedirect(String owner, String name, String desc) {
        List<PatchSpec.Redirect> candidates = redirects.get(owner);
        if (candidates == null) {
            return null;
        }

        for (PatchSpec.Redirect handler : candidates) {
            if (handler.targetMethod().equals(name)
                    && handler.handlerDesc().equals(desc)) {
                return handler;
            }
        }

        return null;
    }

    /**
     * Replaces matching calls with static handler invocations.
     *
     * <p>Runs before type remapping, so matching uses the original call
     * owner, method name and descriptor.</p>
     */
    private final class RedirectMV extends MethodVisitor {
        RedirectMV(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String desc, boolean isInterface) {

            // Constructors and super calls must stay INVOKESPECIAL for the verifier.
            if (opcode != Opcodes.INVOKESPECIAL) {
                // An instance call's receiver becomes the handler's first argument.
                String requiredDesc = opcode == Opcodes.INVOKESTATIC
                        ? desc
                        : "(L" + owner + ";" + desc.substring(1);

                PatchSpec.Redirect handler = findRedirect(owner, name, requiredDesc);
                if (handler != null) {
                    applied.add(handler);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            handler.handlerOwner(),
                            handler.handlerName(),
                            handler.handlerDesc(),
                            false
                    );
                    return;
                }
            }

            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
        }
    }
}
