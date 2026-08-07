package xyz.xenondevs.origami;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.*;
import java.lang.runtime.SwitchBootstraps;
import java.util.Arrays;

/**
 * Contains the indy bootstraps targeted by DynamicInvoker.
 */
@SuppressWarnings("unused") // used by DynamicInvoker
public final class PluginProxy {
    
    private static final MethodHandle CLASS_INSTANCE_HANDLE;
    
    static {
        try {
            CLASS_INSTANCE_HANDLE = MethodHandles.lookup().findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to find Class.isInstance method handle", e);
        }
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
        try {
            var clazz = Class.forName(owner.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var lookup = LookupProxy.getPrivateLookupFor(plugin, clazz);
            var mh = isStatic == 1
                ? lookup.findStatic(clazz, name, toMethodType(desc, lookup))
                : lookup.findVirtual(clazz, name, toMethodType(desc, lookup));
            return new ConstantCallSite(mh.asType(type));
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find method " + name + desc + " in class " + owner + " for method proxy in plugin " + plugin, e);
        }
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
        try {
            var clazz = Class.forName(owner.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var lookup = LookupProxy.getPrivateLookupFor(plugin, clazz);
            var mh = switch (opcode) {
                case Opcodes.GETFIELD -> lookup.findGetter(clazz, name, toClass(desc, lookup));
                case Opcodes.GETSTATIC -> lookup.findStaticGetter(clazz, name, toClass(desc, lookup));
                case Opcodes.PUTFIELD -> lookup.findSetter(clazz, name, toClass(desc, lookup));
                case Opcodes.PUTSTATIC -> lookup.findStaticSetter(clazz, name, toClass(desc, lookup));
                default ->
                    throw new BootstrapMethodError("Unsupported opcode " + opcode + " for accessing field " + desc + " " + name + " in class " + owner + " in plugin " + plugin);
            };
            return new ConstantCallSite(mh.asType(type));
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find field " + desc + " " + name + " in class " + owner + " for field proxy in plugin " + plugin, e);
        }
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
        try {
            var clazz = Class.forName(owner.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var lookup = LookupProxy.getPrivateLookupFor(plugin, clazz);
            var mh = lookup.findConstructor(clazz, toMethodType(desc, lookup));
            return new ConstantCallSite(mh.asType(type));
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find constructor " + owner + desc + " for constructor proxy in plugin " + plugin, e);
        }
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyMetafactory(
        MethodHandles.Lookup caller,
        String interfaceMethod,
        MethodType factoryType,
        String plugin,
        String originalFactoryDesc,
        MethodType interfaceMethodType,
        String targetOwner,
        String targetName,
        String originalTargetDesc,
        String originalDynamicDesc,
        int handleTag
    ) {
        try {
            var clazz = Class.forName(targetOwner.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var lookup = LookupProxy.getPrivateLookupFor(plugin, clazz);
            var mh = switch (handleTag) {
                case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE ->
                    lookup.findVirtual(clazz, targetName, toMethodType(originalTargetDesc, lookup));
                case Opcodes.H_INVOKESTATIC ->
                    lookup.findStatic(clazz, targetName, toMethodType(originalTargetDesc, lookup));
                case Opcodes.H_NEWINVOKESPECIAL ->
                    lookup.findConstructor(clazz, toMethodType(originalTargetDesc, lookup));
                case Opcodes.H_GETFIELD -> lookup.findGetter(clazz, targetName, toClass(originalTargetDesc, lookup));
                case Opcodes.H_GETSTATIC ->
                    lookup.findStaticGetter(clazz, targetName, toClass(originalTargetDesc, lookup));
                case Opcodes.H_PUTFIELD -> lookup.findSetter(clazz, targetName, toClass(originalTargetDesc, lookup));
                case Opcodes.H_PUTSTATIC ->
                    lookup.findStaticSetter(clazz, targetName, toClass(originalTargetDesc, lookup));
                default -> throw new BootstrapMethodError("Unsupported handle tag: " + handleTag);
            };
            
            CallSite lambdaSite = LambdaMetafactory.metafactory(
                lookup,
                interfaceMethod,
                restoreFactoryType(factoryType, originalFactoryDesc, lookup),
                interfaceMethodType,
                mh,
                toMethodType(originalDynamicDesc, lookup)
            );
            
            return new ConstantCallSite(lambdaSite.getTarget().asType(factoryType));
        } catch (Exception e) {
            throw new BootstrapMethodError(
                "Failed to create lambda for " + targetOwner + "." + targetName +
                originalTargetDesc + " in plugin " + plugin,
                e
            );
        }
    }
    
    private static MethodType restoreFactoryType(
        MethodType erasedType,
        String originalDesc,
        MethodHandles.Lookup lookup
    ) throws ClassNotFoundException {
        var originalType = Type.getMethodType(originalDesc);
        var originalArgs = originalType.getArgumentTypes();
        
        if (originalArgs.length != erasedType.parameterCount()) {
            throw new IllegalArgumentException(
                "Factory parameter count changed: "
                + originalDesc + " -> " + erasedType
            );
        }
        
        var parameters = erasedType.parameterArray();
        
        for (int i = 0; i < parameters.length; i++) {
            String erasedDesc = Type.getDescriptor(parameters[i]);
            String originalArgDesc = originalArgs[i].getDescriptor();
            
            if (!erasedDesc.equals(originalArgDesc)) {
                parameters[i] = toClass(originalArgDesc, lookup);
            }
        }
        
        Class<?> returnType = erasedType.returnType();
        String erasedReturnDesc = Type.getDescriptor(returnType);
        String originalReturnDesc =
            originalType.getReturnType().getDescriptor();
        
        if (!erasedReturnDesc.equals(originalReturnDesc)) {
            returnType = toClass(originalReturnDesc, lookup);
        }
        
        return MethodType.methodType(returnType, parameters);
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxySwitch(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        int isEnum,
        String... targets
    ) {
        try {
            var lookup = LookupProxy.getLookupFor(plugin);
            var loader = LookupProxy.getLoaderFor(plugin);
            if (isEnum == 1)
                throw new BootstrapMethodError("Unsupported for now");
            
            var targetClasses = new Class<?>[targets.length];
            for (int i = 0; i < targets.length; i++) {
                targetClasses[i] = Class.forName(targets[i].replace('/', '.'), false, loader);
            }
            return SwitchBootstraps.typeSwitch(lookup, name, type, (Object[]) targetClasses);
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find classes " + Arrays.toString(targets) + " for switch bootstrap in plugin " + plugin, e);
        }
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
            var clazz = Class.forName(className.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var handle = CLASS_INSTANCE_HANDLE.bindTo(clazz);
            return new ConstantCallSite(handle);
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find class " + className + " for instanceof proxy in plugin " + plugin, e);
        }
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyClass(
        MethodHandles.Lookup caller,
        String name,
        MethodType type,
        String plugin,
        String className
    ) {
        try {
            var clazz = Class.forName(className.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            return new ConstantCallSite(MethodHandles.constant(Class.class, clazz));
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to find class " + className + " for class proxy in plugin " + plugin, e);
        }
    }
    
    private static MethodType toMethodType(String desc, MethodHandles.Lookup lookup) {
        return MethodType.fromMethodDescriptorString(desc, lookup.lookupClass().getClassLoader());
    }
    
    private static Class<?> toClass(String desc, MethodHandles.Lookup lookup) throws ClassNotFoundException {
        var t = Type.getType(desc);
        var loader = lookup.lookupClass().getClassLoader();
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
