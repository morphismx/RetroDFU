package dev.morphismx.retrodfu;

/**
 * Small helpers shared by the patcher and the scanner.
 */
public final class Utils {

    private Utils() {}

    /**
     * Converts a binary class name such as {@code a.b.C} to its internal form
     * {@code a/b/C}. Nested-class {@code $} separators are left untouched.
     */
    public static String slash(String str) {
        return str.replace('.', '/');
    }

    /**
     * Whether a jar or directory entry is a class file. {@code module-info}
     * is excluded: it is not a class anything here should patch or scan.
     */
    public static boolean isClassFile(String name) {
        return name.endsWith(".class") && !name.endsWith("module-info.class");
    }
}
