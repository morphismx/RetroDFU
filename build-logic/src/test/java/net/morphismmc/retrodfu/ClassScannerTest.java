package net.morphismmc.retrodfu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fixture classes live under net/morphismmc/retrodfu/x/ to pass the entry
 * whitelist (which — not selfPrefixes — gates jar entries); the
 * forbidden-bytes test goes under com/mojang/datafixers/ because our own
 * tree is exempt from the byte scan. {@link #RULES} mirrors the shapes of
 * the real gate; the production rules live in {@code build.gradle.kts}.
 */
public class ClassScannerTest implements Opcodes {

    private static final String SELF_PKG = "net/morphismmc/retrodfu/x/";
    private static final List<String> SELF = List.of("net/morphismmc/retrodfu/");
    private static final ClassScanner.Rules RULES = new ClassScanner.Rules(
            List.of("com/mojang/", "net/morphismmc/retrodfu/", "META-INF/"),
            List.of("META-INF/versions/"),
            52,
            List.of("net/morphismmc/retrodfu/"),
            List.of("net/morphismmc/retrodfu/shadow/jvmdg/"),
            List.of(
                    new ClassScanner.Needle("org/slf4j"),
                    new ClassScanner.Needle("xyz/wagyourtail", List.of("/stub/", "/exc/", ".stub.", ".exc."))));

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Emits a class declaring {@code methods} ("name:desc"), with one extra
     * method "run" whose body issues the given member instructions
     * ({'m'|'f'}, owner, name, desc).
     */
    private static byte[] cls(
            int version, String name, String sup, String[] ifs, String[] methods, String[] fields, String[][] calls) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(version, ACC_PUBLIC, name, null, sup, ifs);
        for (String f : fields) {
            String[] p = f.split(":", 2);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, p[0], p[1], null, null).visitEnd();
        }
        for (String m : methods) {
            String[] p = m.split(":", 2);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, p[0], p[1], null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ATHROW);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        if (calls.length > 0) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
            mv.visitCode();
            for (String[] c : calls) {
                if (c[0].equals("m")) {
                    mv.visitMethodInsn(INVOKESTATIC, c[1], c[2], c[3], false);
                } else {
                    mv.visitFieldInsn(GETSTATIC, c[1], c[2], c[3]);
                }
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] cls(
            String name, String sup, String[] ifs, String[] methods, String[] fields, String[][] calls) {
        return cls(V1_8, name, sup, ifs, methods, fields, calls);
    }

    /**
     * Emits a class carrying {@code embedded} as an annotation value — a
     * byte-level needle the scan sees without producing a member reference.
     */
    private static byte[] cls(String name, String embedded) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lx/Anno;", true);
        av.visit("v", embedded);
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassScanner.Result scan(File jar, Map<String, List<File>> apiJars) throws IOException {
        return ClassScanner.scan(jar, RULES, SELF, apiJars);
    }

    private File jar(String fileName, Object... entries) throws IOException {
        File jar = new File(tmp.getRoot(), fileName);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            for (int i = 0; i < entries.length; i += 2) {
                jos.putNextEntry(new JarEntry((String) entries[i]));
                Object v = entries[i + 1];
                jos.write(v instanceof byte[] ? (byte[]) v : ((String) v).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    public void selfRefsResolveThroughHierarchy() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Base.class",
                cls(
                        SELF_PKG + "Base",
                        "java/lang/Object",
                        null,
                        new String[] {"m:()V"},
                        new String[0],
                        new String[0][0]),
                SELF_PKG + "Sub.class",
                cls(SELF_PKG + "Sub", SELF_PKG + "Base", null, new String[0], new String[0], new String[0][0]),
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", SELF_PKG + "Base", "m", "()V"},
                    {"m", SELF_PKG + "Sub", "m", "()V"}, // resolved via Base
                }));

        ClassScanner.Result r = scan(j, Map.of());
        assertTrue(r.problems().toString(), r.problems().isEmpty());
    }

    @Test
    public void missingSelfMemberIsReported() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Base.class",
                cls(
                        SELF_PKG + "Base",
                        "java/lang/Object",
                        null,
                        new String[] {"m:()V"},
                        new String[0],
                        new String[0][0]),
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", SELF_PKG + "Base", "nope", "()V"},
                }));

        ClassScanner.Result r = scan(j, Map.of());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).contains("unresolved self ref")
                && r.problems().get(0).contains(SELF_PKG + "Base.nope"));
    }

    private File baselineJar() throws IOException {
        return jar(
                "gson-2.2.4.jar",
                "fake/api/Thing.class",
                cls(
                        "fake/api/Thing",
                        "java/lang/Object",
                        null,
                        new String[] {"work:()V", "helper:()I"},
                        new String[0],
                        new String[0][0]));
    }

    @Test
    public void baselineMembersResolveByExactDescriptor() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", "fake/api/Thing", "work", "()V"},
                }));

        assertTrue(
                scan(j, Map.of("fake/api", List.of(baselineJar()))).problems().isEmpty());
    }

    @Test
    public void baselineMismatchOnReturnTypeIsReported() throws IOException {
        // same name + params, different return type: reflection would pass
        // this, the JVM would not — the scan must agree with the JVM.
        File j = jar(
                "in.jar",
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", "fake/api/Thing", "helper", "()Ljava/lang/String;"},
                }));

        ClassScanner.Result r = scan(j, Map.of("fake/api", List.of(baselineJar())));
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).contains("missing in gson-2.2.4.jar"));
    }

    @Test
    public void baselineMissingClassAndMemberAreReported() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Caller.class",
                cls(
                        SELF_PKG + "Caller",
                        "java/lang/Object",
                        null,
                        new String[0],
                        new String[] {"field:Lfake/api/Gone;"},
                        new String[][] {
                            {"m", "fake/api/Thing", "gone", "()V"},
                            {"m", "fake/api/Gone", "m", "()V"},
                        }));

        ClassScanner.Result r = scan(j, Map.of("fake/api", List.of(baselineJar())));
        // field type ref, missing method, missing owner: three misses
        assertEquals(3, r.problems().size());
        assertTrue(r.problems().stream().allMatch(p -> p.contains("missing in gson-2.2.4.jar")));
    }

    @Test
    public void buildLogicClasspathClassesDoNotCountAsJdk() throws IOException {
        // ASM is on the build-logic test classpath; a stray reference to it
        // must still be reported — it is not a JDK class.
        File j = jar(
                "in.jar",
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", "org/objectweb/asm/ClassReader", "accept", "()V"},
                    {"m", "java/util/ArrayList", "size", "()I"},
                }));

        ClassScanner.Result r = scan(j, Map.of());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).contains("unresolvable type")
                && r.problems().get(0).contains("org/objectweb/asm/ClassReader"));
    }

    @Test
    public void rejectsForbiddenEntries() throws IOException {
        File j = jar(
                "in.jar",
                "evil/E.class",
                cls("evil/E", "java/lang/Object", null, new String[0], new String[0], new String[0][0]),
                "module-info.class",
                cls("module-info", "java/lang/Object", null, new String[0], new String[0], new String[0][0]),
                "META-INF/versions/9/example/A.class",
                cls("example/A", "java/lang/Object", null, new String[0], new String[0], new String[0][0]));

        ClassScanner.Result r = scan(j, Map.of());
        assertEquals(3, r.problems().size());
        assertTrue(r.problems().stream().allMatch(p -> p.startsWith("forbidden entry:")));
    }

    @Test
    public void rejectsNewerBytecodeLevel() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "New.class",
                cls(V17, SELF_PKG + "New", "java/lang/Object", null, new String[0], new String[0], new String[0][0]));

        ClassScanner.Result r = scan(j, Map.of());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("major version 61")));
    }

    @Test
    public void rejectsForbiddenReferenceBytes() throws IOException {
        // outside net/morphismmc/retrodfu/ the byte scan is not exempt
        File j = jar(
                "in.jar",
                "com/mojang/datafixers/Bad.class",
                cls(
                        "com/mojang/datafixers/Bad",
                        "java/lang/Object",
                        null,
                        new String[0],
                        new String[] {"f:Lorg/slf4j/Logger;"},
                        new String[0][0]));

        ClassScanner.Result r = scan(j, Map.of());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("references org/slf4j")));
    }

    @Test
    public void xyzNeedleNeedsAQualifierAndSurvivesExemptContext() throws IOException {
        // A stub-tree ref flags; a token without stub/exc is skipped; a match
        // preceded by the relocated stub tree is payload, not a leak.
        File j = jar(
                "in.jar",
                "com/mojang/datafixers/Leak.class",
                cls("com/mojang/datafixers/Leak", "xyz/wagyourtail/jvmdg/j9/stub/java10/X"),
                "com/mojang/datafixers/Doc.class",
                cls("com/mojang/datafixers/Doc", "xyz/wagyourtail/version/R"),
                "com/mojang/datafixers/Shadow.class",
                cls("com/mojang/datafixers/Shadow", "net/morphismmc/retrodfu/shadow/jvmdg/xyz/wagyourtail/j9/stub/Z"));

        ClassScanner.Result r = scan(j, Map.of());
        List<String> hits =
                r.problems().stream().filter(p -> p.contains("references xyz")).toList();
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).contains("Leak"));
    }

    @Test
    public void unresolvableTypeIsReported() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Caller.class",
                cls(SELF_PKG + "Caller", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", "gone/Elsewhere", "run", "()V"},
                }));

        ClassScanner.Result r = scan(j, Map.of());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).contains("unresolvable type")
                && r.problems().get(0).contains("gone/Elsewhere"));
    }

    @Test
    public void catchTypeIsChecked() throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SELF_PKG + "Catcher", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        mv.visitTryCatchBlock(start, end, handler, "gone/MissingException");
        mv.visitLabel(start);
        mv.visitInsn(NOP);
        mv.visitLabel(end);
        mv.visitInsn(RETURN);
        mv.visitLabel(handler);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassScanner.Result r = scan(jar("in.jar", SELF_PKG + "Catcher.class", cw.toByteArray()), Map.of());
        assertEquals(r.problems().toString(), 1, r.problems().size());
        assertTrue(r.problems().get(0).contains("gone/MissingException"));
    }

    @Test
    public void invokeDynamicDescriptorTypesAreChecked() throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SELF_PKG + "Lambda", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        Handle bootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        mv.visitInvokeDynamicInsn("run", "(Lgone/Captured;)Ljava/lang/Runnable;", bootstrap);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassScanner.Result r = scan(jar("in.jar", SELF_PKG + "Lambda.class", cw.toByteArray()), Map.of());
        assertTrue(r.problems().toString(), r.problems().stream().anyMatch(p -> p.contains("gone/Captured")));
    }

    @Test
    public void inheritedProtectedJdkMemberResolves() throws IOException {
        File j = jar(
                "in.jar",
                SELF_PKG + "Cloner.class",
                cls(SELF_PKG + "Cloner", "java/lang/Object", null, new String[0], new String[0], new String[][] {
                    {"m", SELF_PKG + "Cloner", "clone", "()Ljava/lang/Object;"},
                }));

        ClassScanner.Result r = scan(j, Map.of());
        assertTrue(r.problems().toString(), r.problems().isEmpty());
    }
}
