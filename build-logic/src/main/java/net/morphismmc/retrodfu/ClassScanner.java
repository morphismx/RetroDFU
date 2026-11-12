package net.morphismmc.retrodfu;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.*;

/**
 * Verification gate for a jar. {@link #scan} returns a list of problems; an
 * empty list means the jar passed all of these checks, parameterized by
 * {@link Rules}:
 *
 * <ol>
 *   <li>Entries live under the rules' entry prefixes and none under the
 *       forbidden entry prefixes.</li>
 *   <li>Every class has major version no higher than the rules' maximum.</li>
 *   <li>No forbidden needle occurs outside the exempt entry prefixes and
 *       contexts, subject to its qualifiers.</li>
 *   <li>Each member reference whose owner starts with an API prefix resolves
 *       in every baseline jar given for that prefix.</li>
 *   <li>Each member reference into a self package resolves inside the jar.</li>
 *   <li>Every other referenced type resolves in the jar, the JDK or a
 *       baseline jar.</li>
 * </ol>
 *
 * <p>Member resolution walks the class hierarchy by name and descriptor, as
 * the JVM does at link time.
 */
public final class ClassScanner {
    private static final Pattern REF_TOKEN = Pattern.compile("([A-Za-z0-9_/$.\\-]+)");

    private ClassScanner() {}

    /**
     * The policy {@link #scan} enforces: allowed and forbidden entry prefixes,
     * the maximum class-file major version, entry prefixes exempt from the
     * byte scan, context prefixes that exempt a needle match, and the
     * forbidden needles.
     */
    public record Rules(
            List<String> entryPrefixes,
            List<String> forbiddenEntryPrefixes,
            int maxMajor,
            List<String> scanExemptPrefixes,
            List<String> exemptContexts,
            List<Needle> forbidden) {}

    /**
     * A forbidden byte string. Non-empty {@code qualifiers} means a match only
     * counts when the token starting at it contains one of them.
     */
    public record Needle(String text, List<String> qualifiers) {
        public Needle(String text) {
            this(text, List.of());
        }
    }

    /**
     * Scans {@code jar}.
     *
     * @param rules        the checks to enforce
     * @param selfPrefixes in-jar namespaces whose member refs must resolve
     *                     inside the jar
     * @param apiJars      owner prefix to the baseline jars that every matching
     *                     member ref must resolve against
     */
    public static Result scan(File jar, Rules rules, List<String> selfPrefixes, Map<String, List<File>> apiJars)
            throws IOException {
        List<String> problems = new ArrayList<>();
        Set<Ref> refs = new TreeSet<>();
        Map<String, ClassInfo> index = new HashMap<>();

        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                String name = je.getName();
                if (!entryAllowed(name, rules)) {
                    problems.add("forbidden entry: " + name);
                    continue;
                }
                // module-info.class needs no explicit check: it already fails
                // the entry whitelist above.
                if (startsWithAny(name, rules.forbiddenEntryPrefixes())) {
                    problems.add("forbidden entry: " + name);
                    continue;
                }
                if (!name.endsWith(".class")) continue;
                byte[] b;
                try (InputStream in = jf.getInputStream(je)) {
                    b = in.readAllBytes();
                }
                int major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
                if (major > rules.maxMajor()) problems.add(name + " major version " + major);
                checkForbidden(name, b, rules, problems);
                new ClassReader(b).accept(new ScanCV(refs, index), ClassReader.SKIP_FRAMES);
            }
        }

        // Index each baseline jar the same way as the main jar, so baseline
        // members are matched by exact name and descriptor, like the JVM does.
        Map<File, Map<String, ClassInfo>> baselineIndexes = new HashMap<>();
        for (List<File> jars : apiJars.values()) {
            for (File baseline : jars) {
                baselineIndexes.put(baseline, indexJar(baseline));
            }
        }

        for (Ref r : refs) {
            checkRef(r, index, selfPrefixes, apiJars, baselineIndexes, problems);
        }

        return new Result(problems, refs.size());
    }

    /**
     * Indexes the classes of a baseline jar without collecting references.
     */
    static Map<String, ClassInfo> indexJar(File jar) throws IOException {
        Map<String, ClassInfo> index = new HashMap<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    new ClassReader(in)
                            .accept(
                                    new ScanCV(null, index),
                                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return index;
    }

    /**
     * Resolves one collected reference and adds any failure to {@code problems}.
     */
    static void checkRef(
            Ref r,
            Map<String, ClassInfo> index,
            List<String> selfPrefixes,
            Map<String, List<File>> apiJars,
            Map<File, Map<String, ClassInfo>> baselineIndexes,
            List<String> problems) {
        if (r.owner().startsWith("[")) {
            // Array receiver: the element type must resolve, and the only
            // members are Object's (including clone()) plus the length field.
            Type t = Type.getObjectType(r.owner());
            while (t.getSort() == Type.ARRAY) t = t.getElementType();
            if (t.getSort() == Type.OBJECT) {
                checkRef(
                        new Ref(Kind.TYPE, t.getInternalName(), "", ""),
                        index,
                        selfPrefixes,
                        apiJars,
                        baselineIndexes,
                        problems);
            }
            if (!arrayMemberOk(r)) problems.add("unresolved array member: " + r);
            return;
        }
        if (r.name().equals("<clinit>")) {
            // Not invocable; only the class must exist.
            checkRef(new Ref(Kind.TYPE, r.owner(), "", ""), index, selfPrefixes, apiJars, baselineIndexes, problems);
            return;
        }
        if (startsWithAny(r.owner(), selfPrefixes)) {
            if (!resolveSelf(index, r)) problems.add("unresolved self ref: " + r);
            return;
        }
        boolean handled = false;
        for (Map.Entry<String, List<File>> ap : apiJars.entrySet()) {
            if (!r.owner().startsWith(ap.getKey())) continue;
            handled = true;
            for (File baseline : ap.getValue()) {
                if (!baselineMember(baselineIndexes.get(baseline), r)) {
                    problems.add("missing in " + baseline.getName() + ": " + r);
                }
            }
        }
        if (!handled && !index.containsKey(r.owner()) && !jdkClassExists(r.owner())) {
            problems.add("unresolvable type: " + r);
        }
    }

    /**
     * Whether {@code r} is a member an array exposes: a public {@code Object}
     * method, {@code clone()} or {@code length}.
     */
    static boolean arrayMemberOk(Ref r) {
        if (r.kind() == Kind.FIELD) return r.name().equals("length");
        if (r.name().equals("<init>")) return false;
        if (r.name().equals("clone") && r.desc().equals("()Ljava/lang/Object;")) return true;
        return findMemberJdk(Object.class, r) != null;
    }

    /**
     * Whether an entry lives under an allowed prefix. Parent directories of an
     * allowed prefix are allowed too.
     */
    static boolean entryAllowed(String name, Rules rules) {
        for (String p : rules.entryPrefixes()) {
            if (name.startsWith(p)) return true;
            if (name.endsWith("/") && p.startsWith(name)) return true;
        }
        return false;
    }

    static boolean startsWithAny(String s, List<String> prefixes) {
        return prefixes.stream().anyMatch(s::startsWith);
    }

    static boolean endsWithAny(String s, List<String> suffixes) {
        return suffixes.stream().anyMatch(s::endsWith);
    }

    static boolean resolveSelf(Map<String, ClassInfo> index, Ref r) {
        if (r.kind() == Kind.TYPE) {
            return index.containsKey(r.owner()) || jdkClassExists(r.owner());
        }
        if (!index.containsKey(r.owner())) {
            // A self-prefixed owner missing from the jar is an error by
            // itself; still try the JDK to avoid noise for platform classes.
            Boolean jdk = jdkMember(r.owner(), r);
            return jdk != null && jdk;
        }
        return memberOk(index, r.owner(), r, new HashSet<>());
    }

    static boolean baselineMember(Map<String, ClassInfo> index, Ref r) {
        if (!index.containsKey(r.owner())) return false;
        if (r.kind() == Kind.TYPE) return true;
        return memberOk(index, r.owner(), r, new HashSet<>());
    }

    /**
     * Looks for a member along the class hierarchy (superclass and
     * interfaces), tracking visited types in {@code seen}.
     *
     * <p>An ancestor outside the index is checked against the JDK. If it is not
     * a JDK class either, the member counts as found, because the type-level
     * check reports the missing class itself.
     */
    static boolean memberOk(Map<String, ClassInfo> index, String owner, Ref r, Set<String> seen) {
        String cur = owner;
        while (cur != null && seen.add(cur)) {
            ClassInfo ci = index.get(cur);
            if (ci == null) return externalMember(cur, r);
            Set<String> members = r.kind() == Kind.METHOD ? ci.methods : ci.fields;
            if (members.contains(r.name() + r.desc())) {
                return true;
            }
            if (r.name().equals("<init>")) {
                return false; // constructors are not inherited
            }
            for (String ifc : ci.interfaces) {
                if (memberOk(index, ifc, r, seen)) return true;
            }
            cur = ci.superName;
        }
        return false;
    }

    /**
     * Member check on a class outside the jar: strict for JDK classes,
     * lenient otherwise.
     */
    static boolean externalMember(String owner, Ref r) {
        Boolean jdk = jdkMember(owner, r);
        return jdk == null || jdk;
    }

    /**
     * Resolves a member on a JDK class ({@code null} if unloadable) through
     * the platform class loader, which sees the JDK only — not build-logic's
     * own classpath (ASM, animal-sniffer, Gradle API).
     */
    static Boolean jdkMember(String owner, Ref r) {
        Class<?> c = loadJdkClass(owner);
        return c == null ? null : findMemberJdk(c, r) != null;
    }

    /**
     * Finds a member on a JDK class or its ancestors, public or protected: a
     * subclass may legitimately reach an inherited protected member, e.g.
     * {@code super.clone()}.
     */
    static Object findMemberJdk(Class<?> c, Ref r) {
        try {
            ClassLoader loader = c.getClassLoader();
            if (r.kind() == Kind.FIELD) return findDeclared(c, k -> k.getDeclaredField(r.name()));
            if (r.name().equals("<init>")) {
                return c.getDeclaredConstructor(paramTypes(r.desc(), loader));
            }
            Class<?>[] params = paramTypes(r.desc(), loader);
            return findDeclared(c, k -> k.getDeclaredMethod(r.name(), params));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Looks up a non-private member along the superclass chain and the
     * superinterfaces of every class on it.
     */
    private static Member findDeclared(Class<?> c, ReflectiveLookup lookup) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Member m = lookup.find(k);
                if (!Modifier.isPrivate(m.getModifiers())) return m;
            } catch (ReflectiveOperationException ignored) {
            }
            for (Class<?> ifc : k.getInterfaces()) {
                Member found = findDeclared(ifc, lookup);
                if (found != null) return found;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ReflectiveLookup {
        Member find(Class<?> c) throws ReflectiveOperationException;
    }

    static boolean jdkClassExists(String internal) {
        return loadJdkClass(internal) != null;
    }

    private static Class<?> loadJdkClass(String internal) {
        try {
            return Class.forName(internal.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static Class<?>[] paramTypes(String methodDesc, ClassLoader l) throws ClassNotFoundException {
        Type[] at = Type.getMethodType(methodDesc).getArgumentTypes();
        Class<?>[] pt = new Class<?>[at.length];
        for (int i = 0; i < at.length; i++) pt[i] = toClass(at[i], l);
        return pt;
    }

    static Class<?> toClass(Type t, ClassLoader l) throws ClassNotFoundException {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            case Type.VOID -> void.class;
            case Type.ARRAY -> Class.forName(t.getDescriptor().replace('/', '.'), false, l);
            default -> Class.forName(t.getClassName(), false, l);
        };
    }

    /**
     * Reports {@code entry} if its bytes contain a forbidden needle outside
     * the exempt prefixes and contexts of {@code rules}.
     */
    static void checkForbidden(String entry, byte[] b, Rules rules, List<String> problems) {
        for (String p : rules.scanExemptPrefixes()) {
            if (entry.startsWith(p)) return;
        }
        String s = new String(b, StandardCharsets.ISO_8859_1);
        for (Needle needle : rules.forbidden()) {
            int idx = s.indexOf(needle.text());
            while (idx >= 0) {
                // A needle that is the tail of an exempt context is payload,
                // not a leak.
                if (endsWithAny(s.substring(Math.max(0, idx - 64), idx), rules.exemptContexts())) {
                    idx = s.indexOf(needle.text(), idx + 1);
                    continue;
                }
                if (!needle.qualifiers().isEmpty()) {
                    String rest = s.substring(idx, Math.min(idx + 96, s.length()));
                    Matcher m = REF_TOKEN.matcher(rest);
                    String ref = m.find() ? m.group(1) : "";
                    boolean qualified = false;
                    for (String q : needle.qualifiers()) qualified |= ref.contains(q);
                    if (!qualified) {
                        idx = s.indexOf(needle.text(), idx + 1);
                        continue;
                    }
                }
                problems.add(entry + " references " + needle.text());
                break;
            }
        }
    }

    enum Kind {
        TYPE,
        FIELD,
        METHOD
    }

    /**
     * One collected reference; the record itself is the dedup key.
     */
    record Ref(Kind kind, String owner, String name, String desc) implements Comparable<Ref> {
        private static final Comparator<Ref> ORDER = Comparator.comparing(Ref::kind)
                .thenComparing(Ref::owner)
                .thenComparing(Ref::name)
                .thenComparing(Ref::desc);

        @Override
        public int compareTo(Ref o) {
            return ORDER.compare(this, o);
        }

        @Override
        public String toString() {
            return kind.name().toLowerCase() + " " + owner + "." + name + " " + desc;
        }
    }

    /**
     * A class's hierarchy and its members as name-plus-descriptor strings.
     */
    static class ClassInfo {
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
        String superName;
        String[] interfaces = new String[0];
    }

    /**
     * The scan outcome: the problems found and the number of references checked.
     */
    public record Result(List<String> problems, int refCount) {}

    /**
     * Collects the references a class makes and indexes its own members.
     */
    static final class ScanCV extends ClassVisitor {
        Set<Ref> refs;
        Map<String, ClassInfo> index;
        ClassInfo cur;

        /**
         * A null {@code refs} skips reference collection (baseline indexing).
         */
        ScanCV(Set<Ref> r, Map<String, ClassInfo> ix) {
            super(Opcodes.ASM9);
            refs = r;
            index = ix;
        }

        void typeRef(String internal) {
            if (internal == null || refs == null) return;
            if (internal.startsWith("[")) {
                // An array descriptor is not an internal name; only its
                // element type is a resolvable reference.
                descTypes(Type.getObjectType(internal));
                return;
            }
            // JDK types resolve through the platform loader at check time;
            // collecting them would only grow the ref set.
            if (internal.indexOf('/') >= 0 && !internal.startsWith("java/"))
                refs.add(new Ref(Kind.TYPE, internal, "", ""));
        }

        void memberRef(String owner, String name, String desc, Kind kind) {
            if (owner != null && refs != null) refs.add(new Ref(kind, owner, name, desc));
        }

        void descTypes(Type t) {
            while (t != null && t.getSort() == Type.ARRAY) t = t.getElementType();
            if (t != null && t.getSort() == Type.OBJECT) typeRef(t.getInternalName());
        }

        @Override
        public void visit(int v, int a, String n, String sig, String sup, String[] ifs) {
            cur = new ClassInfo();
            cur.superName = sup;
            cur.interfaces = ifs == null ? new String[0] : ifs;
            index.put(n, cur);
            typeRef(sup);
            if (ifs != null) for (String i : ifs) typeRef(i);
        }

        @Override
        public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
            cur.fields.add(n + d);
            descTypes(Type.getType(d));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
            cur.methods.add(n + d);
            descTypes(Type.getReturnType(d));
            for (Type t : Type.getArgumentTypes(d)) descTypes(t);
            if (ex != null) for (String x : ex) typeRef(x);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String o, String nm, String ds, boolean itf) {
                    memberRef(o, nm, ds, Kind.METHOD);
                }

                @Override
                public void visitFieldInsn(int op, String o, String nm, String ds) {
                    memberRef(o, nm, ds, Kind.FIELD);
                }

                @Override
                public void visitTypeInsn(int op, String t) {
                    descTypes(Type.getObjectType(t));
                }

                @Override
                public void visitLdcInsn(Object v) {
                    if (v instanceof Type t) {
                        if (t.getSort() == Type.METHOD) {
                            for (Type x : t.getArgumentTypes()) descTypes(x);
                            descTypes(t.getReturnType());
                        } else descTypes(t);
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle b, Object... args) {
                    // The call-site descriptor links its own parameter and return types.
                    Type site = Type.getMethodType(d);
                    for (Type t : site.getArgumentTypes()) descTypes(t);
                    descTypes(site.getReturnType());
                    handle(b);
                    for (Object x : args) {
                        if (x instanceof Handle) handle((Handle) x);
                        if (x instanceof Type) descTypes((Type) x);
                    }
                }

                void handle(Handle h) {
                    if (h == null) return;
                    typeRef(h.getOwner());
                    // Handle tags 1-4 are field refs, 5-9 method refs.
                    memberRef(h.getOwner(), h.getName(), h.getDesc(), h.getTag() <= 4 ? Kind.FIELD : Kind.METHOD);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    // A null type is a finally block.
                    typeRef(type);
                }

                @Override
                public void visitMultiANewArrayInsn(String d, int dims) {
                    descTypes(Type.getType(d));
                }
            };
        }
    }
}
