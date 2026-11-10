package dev.morphismx.retrodfu;

public final class Utils {

    private Utils() {
    }

    public static String slash(String str) {
        return str.replace('.', '/');
    }

    /**
     * Whether a jar or directory entry is a class file. {@code module-info}
     * is excluded: it is not a class anything here should patch or scan.
     */
    public static boolean isClassFile(String name) {
        return name.endsWith(".class")
                && !name.endsWith("module-info.class");
    }
}
