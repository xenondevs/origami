package xyz.xenondevs.origami;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import xyz.xenondevs.origami.asm.LookupProxy;

import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PluginProxy {
    
    public static Map<String, Map<String, ClassHandles>> PLUGIN_HANDLES = new ConcurrentHashMap<>();
    
    private static MethodHandle CLASS_INSTANCE_HANDLE;
    
    static {
        try {
            CLASS_INSTANCE_HANDLE = MethodHandles.lookup().findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to find Class.isInstance method handle", e);
        }
    }
    
    public enum HandleType {
        CONSTRUCTOR,
        VIRTUAL_METHOD,
        STATIC_METHOD,
        VIRTUAL_GETTER,
        STATIC_GETTER,
        VIRTUAL_SETTER,
        STATIC_SETTER;
        
        public static HandleType fromFieldOpcode(int opcode) {
            return switch (opcode) {
                case Opcodes.GETFIELD -> VIRTUAL_GETTER;
                case Opcodes.GETSTATIC -> STATIC_GETTER;
                case Opcodes.PUTFIELD -> VIRTUAL_SETTER;
                case Opcodes.PUTSTATIC -> STATIC_SETTER;
                default -> throw new IllegalArgumentException("Invalid field opcode");
            };
        }
        
        public static HandleType fromTag(int tag) {
            return switch (tag) {
                case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE -> VIRTUAL_METHOD;
                case Opcodes.H_INVOKESTATIC -> STATIC_METHOD;
                case Opcodes.H_NEWINVOKESPECIAL -> CONSTRUCTOR;
                case Opcodes.H_GETFIELD -> VIRTUAL_GETTER;
                case Opcodes.H_GETSTATIC -> STATIC_GETTER;
                case Opcodes.H_PUTFIELD -> VIRTUAL_SETTER;
                case Opcodes.H_PUTSTATIC -> STATIC_SETTER;
                default -> throw new IllegalArgumentException("Invalid handle tag: " + tag);
            };
        }
        
    }
    
    public record HandleKey(HandleType type, String name, String desc) {
        
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HandleKey(HandleType type2, String name2, String desc2))) return false;
            return type == type2 && Objects.equals(name, name2) && Objects.equals(desc, desc2);
        }
        
        @Override
        public @NotNull String toString() {
            return type + " " + name + desc;
        }
    }
    
    public static class ClassHandles {
        private volatile boolean initialized = false;
        private final Set<HandleKey> required = ConcurrentHashMap.newKeySet();
        private final Map<HandleKey, MethodHandle> handles = new ConcurrentHashMap<>();
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyMethod(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        String owner,
        String desc,
        int isStatic
    ) {
        var classHandles = checkInitialized(plugin, owner);
        var key = new HandleKey(isStatic == 1 ? HandleType.STATIC_METHOD : HandleType.VIRTUAL_METHOD, name, desc);
        var mh = classHandles.handles.get(key);
        if (mh == null)
            throw new BootstrapMethodError("Method call " + name + desc + " in class " + owner + " was not discovered during mixin scanning but is being accessed!");
        return new ConstantCallSite(mh.asType(type));
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyField(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        String owner,
        String desc,
        int opcode
    ) {
        var classHandles = checkInitialized(plugin, owner);
        var handleType = HandleType.fromFieldOpcode(opcode);
        var key = new HandleKey(handleType, name, desc);
        var mh = classHandles.handles.get(key);
        if (mh == null)
            throw new BootstrapMethodError("Access of field " + desc + " " + name + " owned by " + owner + " was not discovered during mixin scanning but is being accessed!");
        return new ConstantCallSite(mh.asType(type));
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyConstructor(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        String owner,
        String desc
    ) {
        var classHandles = checkInitialized(plugin, owner);
        var key = new HandleKey(HandleType.CONSTRUCTOR, "<init>", desc);
        var mh = classHandles.handles.get(key);
        if (mh == null)
            throw new BootstrapMethodError("Constructor call of " + owner + desc + " was not discovered during mixin scanning but is being accessed!");
        return new ConstantCallSite(mh.asType(type));
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyMetafactory(
        MethodHandles.Lookup caller,
        String interfaceMethod,
        MethodType factoryType,
        String plugin,
        MethodType interfaceMethodType,
        String targetOwner,
        String targetName,
        String originalTargetDesc,
        String originalDynamicDesc,
        int handleTag
    ) throws LambdaConversionException {
        var classHandles = checkInitialized(plugin, targetOwner);
        var key = new HandleKey(HandleType.fromTag(handleTag), targetName, originalTargetDesc);
        var mh = classHandles.handles.get(key);
        if (mh == null)
            throw new BootstrapMethodError("Method call " + targetName + originalTargetDesc + " in class " + targetOwner + " was not discovered during mixin scanning but is being accessed!");
        
        var pluginProxy = LookupProxy.getLookupFor(plugin);
        return LambdaMetafactory.metafactory(
            pluginProxy,
            interfaceMethod,
            factoryType,
            interfaceMethodType,
            mh,
            toMethodType(originalDynamicDesc, pluginProxy.lookupClass().getClassLoader())
        );
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyInstanceOf(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        String className
    ) {
        try {
            var lookup = LookupProxy.getLookupFor(plugin);
            var clazz = lookup.findClass(className.replace('/', '.'));
            var handle = CLASS_INSTANCE_HANDLE.bindTo(clazz);
            return new ConstantCallSite(handle);
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find class " + className + " for instanceof proxy in plugin " + plugin, e);
        }
    }
    
    private static ClassHandles checkInitialized(String plugin, String owner) {
        var pluginHandles = PLUGIN_HANDLES.get(plugin);
        if (pluginHandles == null) {
            throw new BootstrapMethodError("Plugin " + plugin + " invoked dynamically but isn't configured to use Origami!");
        }
        var classHandle = pluginHandles.get(owner);
        if (classHandle == null) {
            throw new BootstrapMethodError("Class " + owner + " was not discovered during mixin scanning!");
        }
        if (!classHandle.initialized) {
            try {
                var pluginLookup = LookupProxy.getLookupFor(plugin);
                pluginLookup.findClass(owner.replace('/', '.'));
            } catch (Exception e) {
                throw new BootstrapMethodError("Class " + owner + " can not been initialized yet!", e);
            }
        }
        return classHandle;
    }
    
    public static void addRequiredHandle(String plugin, String owner, HandleType type, String name, String desc) {
        var pluginHandles = PLUGIN_HANDLES.computeIfAbsent(plugin, k -> new ConcurrentHashMap<>());
        var classHandles = pluginHandles.computeIfAbsent(owner, k -> new ClassHandles());
        var key = new HandleKey(type, name, desc);
        classHandles.required.add(key);
    }
    
    @SuppressWarnings("unused") // Call injected by PaperPluginClassLoaderTransformer
    public static Set<String> getRequired(String plugin) {
        var pluginHandles = PLUGIN_HANDLES.get(plugin);
        return pluginHandles == null ? null : pluginHandles.keySet().stream().map(s -> s.replace('/', '.')).collect(Collectors.toSet());
    }
    
    @SuppressWarnings("unused") // Call injected by PaperPluginClassLoaderTransformer
    public static void initializeHandles(String plugin, Class<?> clazz) {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(clazz, LookupProxy.getLookupFor(plugin));
        } catch (IllegalAccessException | IllegalStateException e) {
            throw new IllegalStateException("Failed to gain trusted lookup for class " + clazz.getName(), e);
        }
        
        var ch = PLUGIN_HANDLES.get(plugin).get(clazz.getName().replace('.', '/'));
        var todo = new ArrayList<>(ch.required);
        todo.forEach(ch.required::remove);
        var loader = clazz.getClassLoader();
        
        for (var missing : todo) {
            try {
                var handle = switch (missing.type) {
                    case CONSTRUCTOR -> lookup.findConstructor(clazz, toMethodType(missing.desc, loader));
                    case VIRTUAL_METHOD -> lookup.findVirtual(clazz, missing.name, toMethodType(missing.desc, loader));
                    case STATIC_METHOD -> lookup.findStatic(clazz, missing.name, toMethodType(missing.desc, loader));
                    case VIRTUAL_GETTER -> lookup.findGetter(clazz, missing.name, toClass(missing.desc, loader));
                    case STATIC_GETTER -> lookup.findStaticGetter(clazz, missing.name, toClass(missing.desc, loader));
                    case VIRTUAL_SETTER -> lookup.findSetter(clazz, missing.name, toClass(missing.desc, loader));
                    case STATIC_SETTER -> lookup.findStaticSetter(clazz, missing.name, toClass(missing.desc, loader));
                };
                ch.handles.put(missing, handle);
            } catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException | ClassNotFoundException e) {
                throw new IllegalStateException("Failed to initialize handle " + missing.name + missing.desc + " in class " + clazz.getName(), e);
            }
        }
        ch.initialized = true;
    }
    
    private static MethodType toMethodType(String desc, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString(desc, loader);
    }
    
    private static Class<?> toClass(String desc, ClassLoader loader) throws ClassNotFoundException, IllegalAccessException {
        var t = Type.getType(desc);
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.ARRAY -> Class.forName(t.getDescriptor().replace('/', '.'), false, loader);
            case Type.OBJECT -> Class.forName(t.getClassName(), false, loader);
            default -> throw new IllegalStateException("Unexpected type sort: " + t.getSort());
        };
    }
    
}
