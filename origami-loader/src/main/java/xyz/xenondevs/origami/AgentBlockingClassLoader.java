package xyz.xenondevs.origami;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

/**
 * {@link URLClassLoader} implementation that prevents plugin classes from being accessed through the agent url in the
 * {@code AppClassLoader}.
 */
public class AgentBlockingClassLoader extends URLClassLoader {
    
    private static final String AGENT_PATH = System.getProperty("origami.agent.url");
    
    static {
        ClassLoader.registerAsParallelCapable();
    }
    
    public AgentBlockingClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }
    
    public static boolean isFromAgent(URL url) {
        if (url == null) return false;
        
        String s = url.toExternalForm();
        return switch (url.getProtocol()) {
            case "jar" -> {
                // jar:<file-url>!/entry -> <file-url>
                int bang = s.indexOf('!');
                if (bang < 0) yield false;
                String base = s.substring(4, bang); // jar:
                yield base.equals(AGENT_PATH);
            }
            case "file" -> s.equals(AGENT_PATH);
            default -> false;
        };
    }
    
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;
            
            if (!name.startsWith("xyz.xenondevs.origami")) {
                URL classUrl = getParent().getResource(name.replace('.', '/') + ".class");
                if (isFromAgent(classUrl)){
                    try {
                        c = super.findClass(name);
                        if (resolve) resolveClass(c);
                        return c;
                    } catch (ClassNotFoundException e) {
                        throw new ClassNotFoundException("Blocking access to agent class: " + name + " from Origami.");
                    }
                }
            }
            
            return super.loadClass(name, resolve);
        }
    }
    
    @Nullable
    @Override
    public URL getResource(String name) {
        URL resource = super.getResource(name);
        return isFromAgent(resource) ? null : resource;
    }
    
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        var out = new ArrayList<URL>();
        var resources = super.getResources(name);
        while (resources.hasMoreElements()) {
            var resource = resources.nextElement();
            if (!isFromAgent(resource))
                out.add(resource);
        }
        return Collections.enumeration(out);
    }
}