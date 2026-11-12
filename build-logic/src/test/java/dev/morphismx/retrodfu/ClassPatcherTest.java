package dev.morphismx.retrodfu;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class ClassPatcherTest implements Opcodes {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ClassPatcher renamingPatcher() throws IOException {
        File config = tmp.newFolder("config");
        Files.writeString(
                config.toPath().resolve("t.json"),
                "{\"package\":\"x\",\"renames\":{\"old.Type\":\"new.Type\"}}",
                StandardCharsets.UTF_8);
        return new ClassPatcher(List.of(config), "t.json", List.of());
    }

    private static byte[] classWithFieldOf(String name, String fieldType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "f", "L" + fieldType + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    public void patchDirsRenamesTypesAndLeavesUnaffectedClassesUntouched() throws IOException {
        Path in = tmp.newFolder("in").toPath();
        byte[] unaffected = classWithFieldOf("a/Plain", "java/lang/String");
        Files.write(in.resolve("Plain.class"), unaffected);
        Files.write(in.resolve("User.class"), classWithFieldOf("a/User", "old/Type"));
        Path out = tmp.newFolder("out").toPath();

        renamingPatcher().patchDirs(List.of(in.toFile()), out.toFile());

        assertArrayEquals(unaffected, Files.readAllBytes(out.resolve("Plain.class")));
        String renamed = new String(Files.readAllBytes(out.resolve("User.class")), StandardCharsets.ISO_8859_1);
        assertTrue(renamed.contains("new/Type"));
        assertFalse(renamed.contains("old/Type"));
    }

    @Test
    public void patchDirsRemovesStaleOutputFiles() throws IOException {
        Path in = tmp.newFolder("in").toPath();
        Files.writeString(in.resolve("kept.txt"), "kept");
        Path out = tmp.newFolder("out").toPath();
        Files.createDirectories(out.resolve("sub"));
        Files.writeString(out.resolve("sub").resolve("stale.txt"), "stale");

        renamingPatcher().patchDirs(List.of(in.toFile()), out.toFile());

        assertTrue(Files.exists(out.resolve("kept.txt")));
        assertFalse(Files.exists(out.resolve("sub").resolve("stale.txt")));
        assertFalse(Files.exists(out.resolve("sub")));
    }
}
