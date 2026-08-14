package xyz.xenondevs.origami;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class OrigamiAgent {
    
    private static final Path LIBRARIES_DIR = Path.of("libraries/");
    
    private static ClassLoader origamiLoader;
    private static Class<?> origamiJit;
    
    public static void premain(String agentArgs, Instrumentation instrumentation) throws Throwable {
        // In case a server admin specifies multiple plugins with Origami as agents
        if (Boolean.getBoolean("origami.agent.loaded"))
            return;
        System.setProperty("origami.agent.loaded", "true");
        
        origamiLoader = new URLClassLoader(buildClasspath("origami-libraries"), OrigamiAgent.class.getClassLoader().getParent());
        origamiJit = Class.forName("xyz.xenondevs.origami.jit.OrigamiJit", true, origamiLoader);
        
        origamiJit.getMethod("premain", Instrumentation.class, ClassLoader.class)
            .invoke(null, instrumentation, OrigamiAgent.class.getClassLoader());
    }
    
    /**
     * Extracts the files mentioned in the given list to the server's libraries folder and
     * returns an array of URLs that point to them.
     *
     * @param listName The name of the file that lists the libraries
     * @return An array of URLs that point to the extracted libraries
     */
    public static URL[] buildClasspath(String listName) {
        try {
            List<String> lines;
            try (var libsStream = OrigamiAgent.class.getResourceAsStream("/" + listName)) {
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
    
    /**
     * Called by Paperclip (patched via PaperclipPatcher) to create the PatchingClassLoader
     * instead of the standard URLClassLoader.
     *
     * @param urls   The URLs to be put on the loader's class path.
     * @param parent The parent class loader.
     * @return The PatchingClassLoader.
     */
    @SuppressWarnings("unused")
    public static ClassLoader createClassLoader(URL[] urls, ClassLoader parent) {
        try {
            var injectables = buildClasspath("server-libraries");
            var newUrls = new URL[urls.length + injectables.length];
            System.arraycopy(urls, 0, newUrls, 0, urls.length);
            System.arraycopy(injectables, 0, newUrls, urls.length, injectables.length);
            
            return (ClassLoader) Class.forName("xyz.xenondevs.origami.jit.PatchingClassLoader", true, origamiLoader)
                .getConstructor(URL[].class, ClassLoader.class)
                .newInstance(newUrls, parent);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load Origami class loader", e);
        }
    }
    
    /**
     * Called by Paperclip (patched via PaperclipPatcher) after the class loader is created.
     *
     * @param urls        The URLs in the class loader.
     * @param classLoader The PatchingClassLoader.
     * @param args        The Paperclip main method arguments.
     */
    @SuppressWarnings("unused")
    public static void initOrigami(URL[] urls, ClassLoader classLoader, String[] args) {
        try {
            origamiJit.getMethod("init", URL[].class, ClassLoader.class, String[].class)
                .invoke(null, (Object) urls, classLoader, (Object) args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize Origami", e);
        }
    }
    
    /**
     * Called by Paperclip (patched via PaperclipPatcher) to invoke the server main method
     * with class loader that is not an {@link URLClassLoader}.
     *
     * @param className   The name of the main class.
     * @param classLoader The PatchingClassLoader.
     * @param args        The start args.
     */
    @SuppressWarnings("unused")
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