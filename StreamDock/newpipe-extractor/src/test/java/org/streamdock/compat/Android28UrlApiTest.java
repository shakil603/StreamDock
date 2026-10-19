package org.streamdock.compat;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Inspect real compiled bytecode against SDK 28 stubs. This deliberately does NOT
 * rely on the host JDK's URLDecoder, where the broken Charset overload exists. */
public class Android28UrlApiTest {
    @Test public void compiledHelpersOnlyInvokeAndroid28Methods() throws Exception {
        final File androidJar = new File(System.getProperty("android28.jar", ""));
        assertTrue("Install Android SDK Platform 28 for this regression check: " + androidJar,
            androidJar.isFile());
        final Map<String, Set<String>> platformMethods = new HashMap<>();
        try (ZipFile jar = new ZipFile(androidJar)) {
            for (String owner : new String[] {"java/net/URLDecoder", "java/net/URLEncoder"}) {
                final Set<String> methods = new HashSet<>();
                try (InputStream source = jar.getInputStream(jar.getEntry(owner + ".class"))) {
                    new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int access, String name,
                                String descriptor, String signature, String[] exceptions) {
                            if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0) {
                                methods.add(name + descriptor);
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                platformMethods.put(owner, methods);
            }
        }
        // Demonstrate that this SDK would reject the exact previous invocation.
        assertFalse(platformMethods.get("java/net/URLDecoder").contains(
            "decode(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"));
        assertFalse(platformMethods.get("java/net/URLEncoder").contains(
            "encode(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"));

        final int[] visited = {0};
        try (InputStream source = Utils.class.getResourceAsStream("Utils.class")) {
            assertNotNull(source);
            new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner,
                                String method, String desc, boolean isInterface) {
                            if (platformMethods.containsKey(owner)) {
                                visited[0]++;
                                assertTrue("Unsupported Android 28 call: " + owner + "." + method + desc,
                                    platformMethods.get(owner).contains(method + desc));
                                assertEquals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", desc);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals("Both decode AND encode must be covered", 2, visited[0]);
    }
}
