package xyz.xenondevs.origami;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class OrigamiAgent {
    
    private static final Path LIBRARIES_DIR = Path.of("libraries/");
    
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static ClassLoader origamiLoader;
    private static Object origami;
    
    public static void premain(String agentArgs, Instrumentation instrumentation) throws Throwable {
        // In case a server admin specifies multiple plugins with Origami as agents
        if (Boolean.getBoolean("origami.agent.loaded"))
            return;
        System.setProperty("origami.agent.loaded", "true");
        
        origamiLoader = new URLClassLoader(buildClasspath(), OrigamiAgent.class.getClassLoader().getParent());
        var origamiClass = Class.forName("xyz.xenondevs.origami.Origami", true, origamiLoader);
        origami = origamiClass.getConstructor(Instrumentation.class, ClassLoader.class)
            .newInstance(instrumentation, OrigamiAgent.class.getClassLoader());
    }
    
    public static URL[] buildClasspath() {
        try {
            List<String> lines;
            try (var libsStream = OrigamiAgent.class.getResourceAsStream("/origami-libraries")) {
                Objects.requireNonNull(libsStream);
                var reader = new BufferedReader(new InputStreamReader(libsStream));
                lines = reader.lines().toList();
            }
            
            String inZipLibsDir = lines.getFirst();
            URL[] urls = new URL[lines.size() - 1];
            
            for (int i = 1; i < lines.size(); i++) {
                var src = lines.get(i);
                var dst = LIBRARIES_DIR.resolve(src.substring(inZipLibsDir.length()));
                if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    try (var srcStream = OrigamiAgent.class.getResourceAsStream(src)) {
                        Objects.requireNonNull(srcStream);
                        Files.copy(srcStream, dst);
                    }
                }
                urls[i - 1] = dst.toUri().toURL();
            }
            
            return urls;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract Origami libraries", e);
        }
    }
    
    @SuppressWarnings("unused") // call is injected by Origami
    public static void initOrigami(URL[] urls, ClassLoader classLoader) {
        try {
            var handle = LOOKUP.findVirtual(origami.getClass(), "init", MethodType.methodType(void.class, URL[].class, ClassLoader.class));
            handle.invoke(origami, (Object) urls, classLoader);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize Origami", e);
        }
    }
    
    @SuppressWarnings("unused") // call is injected by Origami
    public static ClassLoader createClassLoader(URL[] urls, ClassLoader parent) {
        try {
            var loaderClass = Class.forName("xyz.xenondevs.origami.PatchingClassLoader", true, origamiLoader);
            var handle = LOOKUP.findConstructor(loaderClass, MethodType.methodType(void.class, URL[].class, ClassLoader.class));
            return (ClassLoader) handle.invoke(urls, parent);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load Origami class loader", e);
        }
    }
    
    @SuppressWarnings("unused") // call is injected by Origami
    public static void startMain(String className, ClassLoader classLoader, String[] args) {
        try {
            var mainClass = Class.forName(className, true, classLoader);
            var mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to start main method in " + className, e);
        }
    }
    
}