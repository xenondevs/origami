package xyz.xenondevs.origami;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

public class OrigamiAgent {
    
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    
    private static ClassLoader origamiLoader;
    
    private static Object origami;
    
    static {
        System.setProperty("origami.agent.url", OrigamiAgent.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
    }
    
    public static void premain(String agentArgs, Instrumentation instrumentation) throws Throwable {
        // In case a server admin specifies multiple plugins with Origami as agents
        // TODO: still add the agents classloader to origami.agent.url to still block class access
        if (Boolean.getBoolean("origami.agent.loaded")) {
            return;
        }
        System.setProperty("origami.agent.loaded", "true");
        
        var classpath = buildClasspath();
        origamiLoader = new AgentBlockingClassLoader(classpath.toArray(new URL[0]), OrigamiAgent.class.getClassLoader());
        var origamiClass = Class.forName("xyz.xenondevs.origami.Origami", true, origamiLoader);
        origami = origamiClass.getConstructor(Instrumentation.class).newInstance(instrumentation);
    }
    
    public static ArrayList<URL> buildClasspath() {
        var urls = new ArrayList<URL>();
        try {
            Set<String> libraries;
            try (var reader = new BufferedReader(new InputStreamReader(OrigamiAgent.class.getResourceAsStream("/origami-libraries")))) {
                libraries = reader.lines().collect(Collectors.toSet());
            }
            
            for (var entry : libraries) {
                if (entry.isEmpty()) continue;
                // /lib/a/b/c/b-c.jar -> a/b/c/b-c.jar
                var libName = entry.split("/", 3)[2];
                var libFile = new File("libraries/" + libName);
                urls.add(libFile.toURI().toURL());
//                if (libFile.exists()) continue;
                libFile.getParentFile().mkdirs();
                
                try (var libStream = OrigamiAgent.class.getResourceAsStream(entry)) {
                    Files.copy(libStream, libFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract Origami libraries", e);
        }
        
        return urls;
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