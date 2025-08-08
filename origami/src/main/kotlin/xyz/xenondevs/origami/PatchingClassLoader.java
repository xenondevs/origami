package xyz.xenondevs.origami;

import xyz.xenondevs.origami.transformer.runtime.TransformerRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;

// TODO: block any loads that would lead to a class of the plugin that ships the agent being loaded by the agent classloader
public class PatchingClassLoader extends URLClassLoader {
    
    static {
        ClassLoader.registerAsParallelCapable();
    }
    
    private final ClassLoader origamiLoader;
    
    public PatchingClassLoader(URL[] classpathUrls, ClassLoader parent) {
        super(classpathUrls, parent);
        this.origamiLoader = Origami.class.getClassLoader();
    }
    
    public record ClassData(byte[] bytecode, CodeSource codeSource) {
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.startsWith("xyz.xenondevs.origami")
            || name.startsWith("org.spongepowered.asm")
            || name.startsWith("com.llamalad7.mixinextras")
        ) {
            try {
                return origamiLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                throw new ClassNotFoundException("Class not found in origamiLoader: " + name, e);
            }
        }
        
        var internalName = name.replace('.', '/');
        var classData = getTransformedData(internalName, false);
        
        if (classData != null) {
            // Transformer might've loaded the class already
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                // TODO is this safe
                return loaded;
            }
            
            return defineClass(name, classData.bytecode, 0, classData.bytecode.length, classData.codeSource);
        }
        
        return super.findClass(name);
    }
    
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            
            if (c == null) {
                try {
                    c = super.loadClass(name, false);
                } catch (ClassNotFoundException e) {
                    if (name.startsWith("xyz.xenondevs.origami")) {
                        c = origamiLoader.loadClass(name);
                    } else {
                        throw e;
                    }
                }
            }
            
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
    
    
    public ClassData getClassData(String internalName, boolean initiating) {
        URL url;
        var resourceName = internalName + ".class";
        if (internalName.startsWith("xyz/xenondevs/origami")
            || internalName.startsWith("org/spongepowered/asm")
            || internalName.startsWith("com/llamalad7/mixinextras")
        ) {
            if (initiating)
                throw new IllegalArgumentException("Cannot initiate classes in origamiLoader: " + internalName);
            url = origamiLoader.getResource(resourceName);
        } else {
            url = initiating ? findResource(resourceName) : getResource(resourceName);
        }
        if (url == null)
            return null;
        
        InputStream in = null;
        try {
            var conn = url.openConnection();
            in = conn.getInputStream();
            
            var bytecode = in.readAllBytes();
            var codeSource = new CodeSource(((JarURLConnection) conn).getJarFileURL(), (CodeSigner[]) null);
            return new ClassData(bytecode, codeSource);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    public ClassData getTransformedData(String internalName, boolean initiating) {
        var classData = getClassData(internalName, initiating);
        if (classData == null) return null;
        
        var transformed = TransformerRegistry.transform(classData.bytecode, internalName);
        
        return new ClassData(transformed, classData.codeSource);
    }
    
    public Class<?> createClass(String name, byte[] classData) {
        synchronized (getClassLoadingLock(name)) {
            return defineClass(name, classData, 0, classData.length);
        }
    }
    
}
