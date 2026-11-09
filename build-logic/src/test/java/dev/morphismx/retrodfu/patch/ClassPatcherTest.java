package dev.morphismx.retrodfu.patch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

public class ClassPatcherTest implements Opcodes {

    private static final String GUAVA_TARGET = "com/google/common/collect/ImmutableMap";
    private static final String GUAVA_HANDLER_DESC =
            "(Lcom/google/common/collect/ImmutableMap;)Lcom/google/common/collect/ImmutableMap;";

    private static final Map<String, byte[]> SLF4J_RULES = Map.of(
            "dev/morphismx/retrodfu/slf4j/Logger",
            replacesClass("dev/morphismx/retrodfu/slf4j/Logger", "org/slf4j/Logger"),
            "dev/morphismx/retrodfu/slf4j/LoggerFactory",
            replacesClass("dev/morphismx/retrodfu/slf4j/LoggerFactory", "org/slf4j/LoggerFactory"));

    private static Map<String, byte[]> guavaRules(int methodAccess, String handlerDesc) {
        return Map.of("dev/morphismx/retrodfu/guava/ImmutableMapCompat",
                transformerClass(methodAccess, handlerDesc));
    }

    private static Map<String, byte[]> guavaRules() {
        return guavaRules(ACC_PUBLIC | ACC_STATIC, GUAVA_HANDLER_DESC);
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A class that touches org/slf4j in a field, an INVOKEINTERFACE and an invokestatic.
     */
    private static byte[] callerClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "example/Caller", null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "logger", "Lorg/slf4j/Logger;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "call", "(Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "example/Caller", "logger", "Lorg/slf4j/Logger;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/slf4j/Logger", "info", "(Ljava/lang/String;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        MethodVisitor mk = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()Lorg/slf4j/Logger;", null, null);
        mk.visitCode();
        mk.visitLdcInsn("x");
        mk.visitMethodInsn(INVOKESTATIC, "org/slf4j/LoggerFactory", "getLogger",
                "(Ljava/lang/String;)Lorg/slf4j/Logger;", false);
        mk.visitInsn(ARETURN);
        mk.visitMaxs(1, 0);
        mk.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class that calls a guava member missing on the game's guava.
     */
    private static byte[] guavaCallerClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "example/Calls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "go",
                "(Lcom/google/common/collect/ImmutableMap;)Lcom/google/common/collect/ImmutableMap;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, GUAVA_TARGET,
                "buildKeepingLast", "()Lcom/google/common/collect/ImmutableMap;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class that invokes a redirected member via INVOKESPECIAL (a super call).
     */
    private static byte[] specialCallerClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "example/Sub", null,
                "com/google/common/collect/ImmutableMap", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "go", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, GUAVA_TARGET,
                "buildKeepingLast", "()Lcom/google/common/collect/ImmutableMap;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A fabricated stand-in class carrying only an @Replaces, CLASS retention.
     */
    private static byte[] replacesClass(String internalName, String targetInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Ldev/morphismx/retrodfu/annotation/Replaces;", false);
        av.visit("value", targetInternal.replace('/', '.'));
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A fabricated compat class: @Replaces + one @Redirect handler, CLASS retention.
     */
    private static byte[] transformerClass(int methodAccess, String handlerDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "dev/morphismx/retrodfu/guava/ImmutableMapCompat", null, "java/lang/Object", null);

        AnnotationVisitor replaces = cw.visitAnnotation("Ldev/morphismx/retrodfu/annotation/Replaces;", false);
        replaces.visit("value", "org.slf4j.Logger");
        replaces.visitEnd();

        MethodVisitor mv = cw.visitMethod(methodAccess, "buildKeepingLast", handlerDesc, null, null);
        AnnotationVisitor redirect = mv.visitAnnotation("Ldev/morphismx/retrodfu/annotation/Redirect;", false);
        // method element omitted: the handler's own name is the target
        redirect.visit("value", Type.getObjectType(GUAVA_TARGET));
        redirect.visitEnd();
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Facts scan(byte[] bytes) {
        Facts f = new Facts();
        new ClassReader(bytes).accept(f, 0);
        return f;
    }

    private static void put(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        JarEntry e = new JarEntry(name);
        e.setTime(100000000L);
        jos.putNextEntry(e);
        jos.write(bytes);
        jos.closeEntry();
    }

    private static byte[] readEntry(JarFile jar, String name) throws IOException {
        return jar.getInputStream(jar.getEntry(name)).readAllBytes();
    }

    private static void writeFile(File root, String rel, byte[] bytes) throws IOException {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), bytes);
    }

    /**
     * Packs classes as a jar, each under "<internalName>.class" — the shape the
     * {@link ClassPatcher} constructor and {@link PatchSpec#scan} take rules in.
     */
    private File rulesJar(Map<String, byte[]> classes) throws IOException {
        File jar = tmp.newFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                put(jos, e.getKey() + ".class", e.getValue());
            }
        }
        return jar;
    }

    private ClassPatcher patcher(Map<String, byte[]> rules) throws IOException {
        return new ClassPatcher(rulesJar(rules));
    }

    @Test
    public void renamesTypesWithoutTouchingInvokeForm() throws IOException {
        Facts f = scan(patcher(SLF4J_RULES).patchClass("example/Caller.class", callerClass()));

        assertTrue(f.members.contains("logger:Ldev/morphismx/retrodfu/slf4j/Logger;"));
        assertTrue(f.members.contains("make:()Ldev/morphismx/retrodfu/slf4j/Logger;"));
        // INVOKEINTERFACE stays INVOKEINTERFACE: the compat Logger is an
        // interface like slf4j's, so the rename alone keeps it legal.
        assertTrue(f.calls.contains(INVOKEINTERFACE + ":true:dev/morphismx/retrodfu/slf4j/Logger.info(Ljava/lang/String;)V"));
        assertTrue(f.calls.contains(INVOKESTATIC + ":false:dev/morphismx/retrodfu/slf4j/LoggerFactory.getLogger(Ljava/lang/String;)Ldev/morphismx/retrodfu/slf4j/Logger;"));
        assertTrue(f.calls.stream().noneMatch(c -> c.contains("org/slf4j")));
    }

    @Test
    public void leavesNoRemappedTypeBytesBehind() throws IOException {
        // ClassWriter(0) rebuilds the constant pool from scratch: the old
        // slf4j UTF8 entries must not survive as dead pool entries.
        String s = new String(patcher(SLF4J_RULES).patchClass("example/Caller.class", callerClass()),
                StandardCharsets.ISO_8859_1);
        assertFalse(s.contains("org/slf4j"));
    }

    @Test
    public void scanCollectsReplacesAndRedirectRules() throws IOException {
        PatchSpec spec = PatchSpec.scan(rulesJar(guavaRules()));

        assertEquals("dev/morphismx/retrodfu/guava/ImmutableMapCompat",
                spec.replaces.get("org/slf4j/Logger"));

        List<PatchSpec.Redirect> handlers = spec.redirects.get(GUAVA_TARGET);
        assertNotNull(handlers);
        assertEquals(1, handlers.size());
        PatchSpec.Redirect r = handlers.getFirst();
        assertEquals("dev/morphismx/retrodfu/guava/ImmutableMapCompat", r.handlerOwner());
        assertEquals("buildKeepingLast", r.handlerName());
        assertEquals(GUAVA_HANDLER_DESC, r.handlerDesc());
        assertEquals("buildKeepingLast", r.targetMethod());
    }

    @Test
    public void redirectRewritesInstanceCallToInvokestatic() throws IOException {
        Facts f = scan(patcher(guavaRules()).patchClass("example/Calls.class", guavaCallerClass()));

        assertTrue(f.calls.contains(INVOKESTATIC + ":false:dev/morphismx/retrodfu/guava/ImmutableMapCompat.buildKeepingLast"
                + GUAVA_HANDLER_DESC));
        assertTrue(f.calls.stream().noneMatch(c -> c.contains("ImmutableMap.buildKeepingLast")));
    }

    @Test
    public void scanRejectsNonStaticHandler() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PatchSpec.scan(rulesJar(guavaRules(ACC_PUBLIC, GUAVA_HANDLER_DESC))));
        assertTrue(e.getMessage().contains("must be static"));
    }

    @Test
    public void scanRejectsNonPublicHandler() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PatchSpec.scan(rulesJar(guavaRules(ACC_PRIVATE | ACC_STATIC, GUAVA_HANDLER_DESC))));
        assertTrue(e.getMessage().contains("must be public"));
    }

    @Test
    public void leavesDescUnmatchedCallSiteAlone() throws IOException {
        // A "()V" handler can't serve the (Builder)->Map call: the invoke is
        // left untouched and CompatScan is what flags it downstream.
        Facts f = scan(patcher(guavaRules(ACC_PUBLIC | ACC_STATIC, "()V"))
                .patchClass("example/Calls.class", guavaCallerClass()));
        assertTrue(f.calls.contains(INVOKEVIRTUAL + ":false:" + GUAVA_TARGET
                + ".buildKeepingLast()Lcom/google/common/collect/ImmutableMap;"));
    }

    @Test
    public void leavesInvokespecialCallSiteAlone() throws IOException {
        // A super call to a redirected member: INVOKESPECIAL can never be
        // expressed as a static handler call, so it must pass through.
        Facts f = scan(patcher(guavaRules()).patchClass("example/Sub.class", specialCallerClass()));
        assertTrue(f.calls.contains(INVOKESPECIAL + ":false:" + GUAVA_TARGET
                + ".buildKeepingLast()Lcom/google/common/collect/ImmutableMap;"));
    }

    @Test
    public void patchJarAppliesRulesFromPatchesJar() throws IOException {
        File in = tmp.newFile("patch-in.jar");
        File out = new File(tmp.getRoot(), "patch-out.jar");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(in))) {
            put(jos, "example/Calls.class", guavaCallerClass());
        }

        assertTrue(patcher(guavaRules()).patchJar(in, out).isEmpty());

        try (JarFile jout = new JarFile(out)) {
            Facts f = scan(readEntry(jout, "example/Calls.class"));
            assertTrue(f.calls.contains(INVOKESTATIC + ":false:dev/morphismx/retrodfu/guava/ImmutableMapCompat.buildKeepingLast"
                    + GUAVA_HANDLER_DESC));
        }
    }

    @Test
    public void patchJarReportsUnusedRedirects() throws IOException {
        File in = tmp.newFile("patch-in.jar");
        File out = new File(tmp.getRoot(), "patch-out.jar");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(in))) {
            // the handler is there, but no class ever calls its target
            put(jos, "example/Idle.class", callerClass());
        }

        List<String> unused = patcher(guavaRules()).patchJar(in, out);
        assertEquals(1, unused.size());
        assertTrue(unused.getFirst().contains("buildKeepingLast"));
        try (JarFile jout = new JarFile(out)) {
            assertNotNull(jout.getJarEntry("example/Idle.class"));
        }
    }

    @Test
    public void rewritesJarCopyingNonClassEntriesVerbatim() throws IOException {
        File in = tmp.newFile("patch-in.jar");
        File out = new File(tmp.getRoot(), "patch-out.jar");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(in))) {
            put(jos, "example/Caller.class", callerClass());
            put(jos, "data.txt", "hello".getBytes(StandardCharsets.UTF_8));
            // patchJar itself drops nothing: module-info.class fails
            // isClassFile and is copied verbatim, while a versioned class is
            // rewritten like any other. Excluding them is PatchJar's job.
            put(jos, "module-info.class", callerClass());
            put(jos, "META-INF/versions/9/example/Caller.class", callerClass());
        }

        patcher(SLF4J_RULES).patchJar(in, out);

        try (JarFile jin = new JarFile(in); JarFile jout = new JarFile(out)) {
            byte[] patchedClass = readEntry(jout, "example/Caller.class");
            assertNotEquals(0, patchedClass.length);
            assertTrue(scan(patchedClass).calls.stream().noneMatch(c -> c.contains("org/slf4j")));
            assertArrayEquals(callerClass(), readEntry(jout, "module-info.class"));
            assertTrue(scan(readEntry(jout, "META-INF/versions/9/example/Caller.class"))
                    .calls.stream().noneMatch(c -> c.contains("org/slf4j")));
            assertEquals("hello", new String(readEntry(jout, "data.txt"), StandardCharsets.UTF_8));

            // surviving entries keep input order and timestamps
            List<String> inNames = new ArrayList<>();
            Enumeration<JarEntry> ein = jin.entries();
            while (ein.hasMoreElements()) inNames.add(ein.nextElement().getName());
            List<String> outNames = new ArrayList<>();
            Enumeration<JarEntry> eout = jout.entries();
            while (eout.hasMoreElements()) {
                JarEntry b = eout.nextElement();
                outNames.add(b.getName());
                assertEquals(100000000L, b.getTime());
            }
            assertEquals(inNames, outNames);
        }
    }

    @Test
    public void patchDirBehavesLikePatchJar() throws IOException {
        File in = tmp.newFolder("classes");
        File out = new File(tmp.getRoot(), "patched");

        writeFile(in, "example/Calls.class", guavaCallerClass());
        writeFile(in, "data.txt", "hello".getBytes(StandardCharsets.UTF_8));
        writeFile(in, "module-info.class", callerClass());

        assertTrue(patcher(guavaRules()).patchDirs(List.of(in), out).isEmpty());

        Facts f = scan(Files.readAllBytes(new File(out, "example/Calls.class").toPath()));
        assertTrue(f.calls.contains(INVOKESTATIC + ":false:dev/morphismx/retrodfu/guava/ImmutableMapCompat.buildKeepingLast"
                + GUAVA_HANDLER_DESC));
        assertEquals("hello",
                Files.readString(new File(out, "data.txt").toPath()));
        // PatchFiles does no filtering either — module-info travels verbatim.
        assertArrayEquals(callerClass(),
                Files.readAllBytes(new File(out, "module-info.class").toPath()));
    }

    /**
     * (re)reads a class and records member descriptors and method-insn calls.
     */
    private static final class Facts extends ClassVisitor {
        final List<String> members = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        Facts() {
            super(ASM9);
        }

        @Override
        public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
            members.add(n + ":" + d);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
            members.add(n + ":" + d);
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                    calls.add(op + ":" + itf + ":" + owner + "." + name + desc);
                }
            };
        }
    }
}
