package dev.morphismx.retrodfu.patch;

import dev.morphismx.retrodfu.Utils;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The patch declarations scanned off the patch classes: {@code @Replaces}
 * type renames and {@code @Redirect} call-site handlers, keyed by target
 * internal name.
 */
final class PatchSpec {
    static final String REPLACES_DESC = "Ldev/morphismx/retrodfu/annotation/Replaces;";
    static final String REDIRECT_DESC = "Ldev/morphismx/retrodfu/annotation/Redirect;";

    // replaced target internal name -> replacement internal name
    final Map<String, String> replaces = new LinkedHashMap<>();
    // redirect target internal name -> handlers
    final Map<String, List<Redirect>> redirects = new LinkedHashMap<>();

    /**
     * Reads the patch annotations off every class in {@code jar}; CLASS
     * retention keeps them out of runtime sight but not ASM's.
     */
    static PatchSpec scan(File jar) throws IOException {
        PatchSpec spec = new PatchSpec();
        try (JarFile in = new JarFile(jar)) {
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements();) {
                JarEntry je = e.nextElement();
                String name = je.getName();
                if (!Utils.isClassFile(name)) {
                    continue;
                }
                try (InputStream is = in.getInputStream(je)) {
                    new ClassReader(is).accept(
                            new Scanner(spec),
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
                    );
                }
            }
        }
        return spec;
    }

    private void addReplaces(String target, String className) {
        String targetName = Utils.slash(target);
        if (targetName.equals(className)) {
            throw new IllegalArgumentException(
                    "@Replaces target must differ from replacement class: " + className);
        }
        if (replaces.containsKey(targetName)) {
            throw new IllegalArgumentException("duplicate @Replaces for " + targetName);
        }

        replaces.put(targetName, className);
    }

    private void addRedirect(String handlerOwner, String handlerName, String handlerDesc,
                             String targetClass, String targetMethod) {
        List<Redirect> handlers = redirects.computeIfAbsent(targetClass, _ -> new ArrayList<>());

        // A (target method, handler descriptor) pair may only be handled once;
        // distinct overloads of the same target method are fine.
        for (Redirect handler : handlers) {
            if (handler.targetMethod().equals(targetMethod)
                    && handler.handlerDesc().equals(handlerDesc)) {
                throw new IllegalArgumentException(
                        "duplicate @Redirect handlers for " + targetClass + "." + targetMethod
                                + ": " + handler.handlerOwner() + "." + handler.handlerName()
                                + " and " + handlerOwner + "." + handlerName
                                + " share descriptor " + handlerDesc
                );
            }
        }

        handlers.add(new Redirect(handlerOwner, handlerName, handlerDesc, targetMethod));
    }

    record Redirect(String handlerOwner, String handlerName,
                    String handlerDesc, String targetMethod) {
    }

    private static final class Scanner extends ClassVisitor {
        private final PatchSpec spec;
        private String className;

        Scanner(PatchSpec spec) {
            super(Opcodes.ASM9);
            this.spec = spec;
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName, String[] interfaces) {
            className = name;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (!REPLACES_DESC.equals(desc)) {
                return null;
            }

            return new AnnotationVisitor(Opcodes.ASM9) {
                private String target;

                @Override
                public void visit(String annName, Object annValue) {
                    if ("value".equals(annName)) {
                        target = (String) annValue;
                    }
                }

                @Override
                public void visitEnd() {
                    if (target == null || target.isEmpty()) {
                        throw new IllegalArgumentException(
                                "@Replaces on " + className + " is missing its target");
                    }

                    spec.addReplaces(target, className);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDesc, boolean visible) {
                    if (!REDIRECT_DESC.equals(annotationDesc)) {
                        return null;
                    }
                    // call sites are retargeted to a plain INVOKESTATIC, so the handler must be
                    // static, public and concrete
                    if (!Modifier.isStatic(access)) {
                        throw new IllegalArgumentException(
                                "@Redirect handler " + className + "." + name + desc
                                        + " must be static");
                    }
                    if (!Modifier.isPublic(access)) {
                        throw new IllegalArgumentException(
                                "@Redirect handler " + className + "." + name + desc
                                        + " must be public");
                    }
                    if (Modifier.isAbstract(access)) {
                        throw new IllegalArgumentException(
                                "@Redirect handler " + className + "." + name + desc
                                        + " must have a method body");
                    }

                    return new AnnotationVisitor(Opcodes.ASM9) {
                        private Type targetClass;
                        private String targetMethod;

                        @Override
                        public void visit(String annName, Object value) {
                            if ("value".equals(annName)) {
                                targetClass = (Type) value;
                            } else if ("method".equals(annName)) {
                                targetMethod = (String) value;
                            }
                        }

                        @Override
                        public void visitEnd() {
                            if (targetClass == null) {
                                throw new IllegalArgumentException(
                                        "@Redirect on " + className + "." + name + desc
                                                + " is missing its target type");
                            }

                            // method defaults to "": the handler and target share the name
                            spec.addRedirect(className, name, desc,
                                    targetClass.getInternalName(),
                                    targetMethod == null || targetMethod.isEmpty()
                                            ? name : targetMethod);
                        }
                    };
                }
            };
        }
    }
}
