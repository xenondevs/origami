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
        String interfaceMethodDesc,
        String targetOwner,
        String targetName,
        String originalTargetDesc,
        String originalDynamicDesc,
        int handleTag
    ) {
        try {
            var pluginLookup = LookupProxy.getLookupFor(plugin);
            var clazz = Class.forName(targetOwner.replace('/', '.'), false, pluginLookup.lookupClass().getClassLoader());
            var targetLookup = caller.lookupClass() == clazz
                ? caller
                : MethodHandles.privateLookupIn(clazz, pluginLookup);
            var mh = findTargetHandle(targetLookup, clazz, targetName, originalTargetDesc, handleTag);
            return createMetafactoryCallSite(
                targetLookup,
                interfaceMethod,
                factoryType,
                pluginLookup,
                originalFactoryDesc,
                interfaceMethodDesc,
                mh,
                originalDynamicDesc
            );
        } catch (Exception e) {
            throw new BootstrapMethodError(
                "Failed to create lambda for " + targetOwner + "." + targetName +
                originalTargetDesc + " in plugin " + plugin,
                e
            );
        }
    }

    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyLocalMetafactory(
        MethodHandles.Lookup caller,
        String interfaceMethod,
        MethodType factoryType,
        String plugin,
        String originalFactoryDesc,
        String interfaceMethodDesc,
        MethodHandle target,
        String originalDynamicDesc
    ) {
        try {
            return createMetafactoryCallSite(
                caller,
                interfaceMethod,
                factoryType,
                LookupProxy.getLookupFor(plugin),
                originalFactoryDesc,
                interfaceMethodDesc,
                target,
                originalDynamicDesc
            );
        } catch (Exception e) {
            throw new BootstrapMethodError(
                "Failed to create local lambda " + interfaceMethod + " in plugin " + plugin,
                e
            );
        }
    }

    private static CallSite createMetafactoryCallSite(
        MethodHandles.Lookup targetLookup,
        String interfaceMethod,
        MethodType factoryType,
        MethodHandles.Lookup pluginLookup,
        String originalFactoryDesc,
        String interfaceMethodDesc,
        MethodHandle target,
        String originalDynamicDesc
    ) throws Exception {
        var restoredFactoryType = restoreFactoryType(factoryType, originalFactoryDesc, pluginLookup);
        var interfaceMethodType = toMethodType(interfaceMethodDesc, pluginLookup);
        var dynamicMethodType = toMethodType(originalDynamicDesc, pluginLookup);

        if (!isVisibleFrom(restoredFactoryType.returnType(), targetLookup.lookupClass().getClassLoader())
            || !isVisibleFrom(interfaceMethodType, targetLookup.lookupClass().getClassLoader())
            || !isVisibleFrom(dynamicMethodType, targetLookup.lookupClass().getClassLoader())
        ) {
            return proxyInterfaceFactory(factoryType, restoredFactoryType.returnType(), target, dynamicMethodType);
        }

        var lambdaSite = LambdaMetafactory.metafactory(
            targetLookup,
            interfaceMethod,
            restoredFactoryType,
            interfaceMethodType,
            target,
            dynamicMethodType
        );
        return new ConstantCallSite(lambdaSite.getTarget().asType(factoryType));
    }
    
    private static CallSite proxyInterfaceFactory(
        MethodType factoryType,
        Class<?> interfaceType,
        MethodHandle target,
        MethodType dynamicMethodType
    ) throws NoSuchMethodException, IllegalAccessException {
        var factory = MethodHandles.lookup().findStatic(
            PluginProxy.class,
            "createInterfaceProxy",
            MethodType.methodType(
                Object.class,
                Class.class,
                MethodHandle.class,
                MethodType.class,
                Object[].class
            )
        );
        factory = MethodHandles.insertArguments(factory, 0, interfaceType, target, dynamicMethodType);
        factory = factory.asCollector(Object[].class, factoryType.parameterCount());
        return new ConstantCallSite(factory.asType(factoryType));
    }
    
    private static Object createInterfaceProxy(
        Class<?> interfaceType,
        MethodHandle target,
        MethodType dynamicMethodType,
        Object[] capturedArguments
    ) {
        var boundTarget = MethodHandles.insertArguments(target, 0, capturedArguments);
        return MethodHandleProxies.asInterfaceInstance(interfaceType, boundTarget.asType(dynamicMethodType));
    }
    
    private static boolean isVisibleFrom(MethodType type, ClassLoader loader) {
        if (!isVisibleFrom(type.returnType(), loader))
            return false;
        for (var parameter : type.parameterArray()) {
            if (!isVisibleFrom(parameter, loader))
                return false;
        }
        return true;
    }
    
    private static boolean isVisibleFrom(Class<?> type, ClassLoader loader) {
        if (type.isPrimitive())
            return true;
        try {
            return Class.forName(type.getName(), false, loader) == type;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
    
    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyAltMetafactory(
        MethodHandles.Lookup caller,
        String interfaceMethod,
        MethodType factoryType,
        String plugin,
        String originalFactoryDesc,
        String interfaceMethodDesc,
        String targetOwner,
        String targetName,
        String originalTargetDesc,
        String originalDynamicDesc,
        int handleTag,
        int flags,
        String... encodedOptionalArgs
    ) {
        try {
            var clazz = Class.forName(targetOwner.replace('/', '.'), false, LookupProxy.getLoaderFor(plugin));
            var lookup = LookupProxy.getPrivateLookupFor(plugin, clazz);
            var mh = findTargetHandle(lookup, clazz, targetName, originalTargetDesc, handleTag);
            var args = new Object[4 + encodedOptionalArgs.length];
            args[0] = toMethodType(interfaceMethodDesc, lookup);
            args[1] = mh;
            args[2] = toMethodType(originalDynamicDesc, lookup);
            args[3] = flags;
            for (int i = 0; i < encodedOptionalArgs.length; i++) {
                args[i + 4] = decodeBootstrapArgument(encodedOptionalArgs[i], lookup);
            }
            
            var lambdaSite = LambdaMetafactory.altMetafactory(
                lookup,
                interfaceMethod,
                restoreFactoryType(factoryType, originalFactoryDesc, lookup),
                args
            );
            return new ConstantCallSite(lambdaSite.getTarget().asType(factoryType));
        } catch (Exception e) {
            throw new BootstrapMethodError(
                "Failed to create alternate lambda for " + targetOwner + "." + targetName
                + originalTargetDesc + " in plugin " + plugin,
                e
            );
        }
    }

    @SuppressWarnings("unused") // indy to this created by DynamicInvoker
    public static CallSite proxyLocalAltMetafactory(
        MethodHandles.Lookup caller,
        String interfaceMethod,
        MethodType factoryType,
        String plugin,
        String originalFactoryDesc,
        String interfaceMethodDesc,
        MethodHandle target,
        String originalDynamicDesc,
        int flags,
        String... encodedOptionalArgs
    ) {
        try {
            var pluginLookup = LookupProxy.getLookupFor(plugin);
            var interfaceMethodType = toMethodType(interfaceMethodDesc, pluginLookup);
            var dynamicMethodType = toMethodType(originalDynamicDesc, pluginLookup);
            var restoredFactoryType = restoreFactoryType(factoryType, originalFactoryDesc, pluginLookup);

            if (flags == 0
                && (!isVisibleFrom(restoredFactoryType.returnType(), caller.lookupClass().getClassLoader())
                || !isVisibleFrom(interfaceMethodType, caller.lookupClass().getClassLoader())
                || !isVisibleFrom(dynamicMethodType, caller.lookupClass().getClassLoader()))
            ) {
                return proxyInterfaceFactory(
                    factoryType,
                    restoredFactoryType.returnType(),
                    target,
                    dynamicMethodType
                );
            }

            var args = new Object[4 + encodedOptionalArgs.length];
            args[0] = interfaceMethodType;
            args[1] = target;
            args[2] = dynamicMethodType;
            args[3] = flags;
            for (int i = 0; i < encodedOptionalArgs.length; i++) {
                args[i + 4] = decodeBootstrapArgument(encodedOptionalArgs[i], pluginLookup);
            }

            var lambdaSite = LambdaMetafactory.altMetafactory(
                caller,
                interfaceMethod,
                restoredFactoryType,
                args
            );
            return new ConstantCallSite(lambdaSite.getTarget().asType(factoryType));
        } catch (Exception e) {
            throw new BootstrapMethodError(
                "Failed to create local alternate lambda " + interfaceMethod + " in plugin " + plugin,
                e
            );
        }
    }
    
    private static MethodHandle findTargetHandle(
        MethodHandles.Lookup lookup,
        Class<?> clazz,
        String targetName,
        String targetDesc,
        int handleTag
    ) throws NoSuchFieldException, NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
        return switch (handleTag) {
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE ->
                lookup.findVirtual(clazz, targetName, toMethodType(targetDesc, lookup));
            case Opcodes.H_INVOKESTATIC -> lookup.findStatic(clazz, targetName, toMethodType(targetDesc, lookup));
            case Opcodes.H_NEWINVOKESPECIAL -> lookup.findConstructor(clazz, toMethodType(targetDesc, lookup));
            case Opcodes.H_GETFIELD -> lookup.findGetter(clazz, targetName, toClass(targetDesc, lookup));
            case Opcodes.H_GETSTATIC -> lookup.findStaticGetter(clazz, targetName, toClass(targetDesc, lookup));
            case Opcodes.H_PUTFIELD -> lookup.findSetter(clazz, targetName, toClass(targetDesc, lookup));
            case Opcodes.H_PUTSTATIC -> lookup.findStaticSetter(clazz, targetName, toClass(targetDesc, lookup));
            default -> throw new BootstrapMethodError("Unsupported handle tag: " + handleTag);
        };
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
        String originalDesc,
        int isEnum,
        Object... targets
    ) {
        try {
            var lookup = LookupProxy.getLookupFor(plugin);
            var labels = new Object[targets.length];
            for (int i = 0; i < targets.length; i++) {
                labels[i] = decodeSwitchArgument(targets[i], lookup);
            }
            var originalType = toMethodType(originalDesc, lookup);
            var switchSite = isEnum == 1
                ? SwitchBootstraps.enumSwitch(lookup, name, originalType, labels)
                : SwitchBootstraps.typeSwitch(lookup, name, originalType, labels);
            return new ConstantCallSite(switchSite.getTarget().asType(type));
        } catch (Exception e) {
            throw new BootstrapMethodError("Failed to resolve switch labels " + Arrays.toString(targets) + " for plugin " + plugin, e);
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
    
    private static Object decodeBootstrapArgument(
        String argument,
        MethodHandles.Lookup lookup
    ) throws ClassNotFoundException {
        if (argument.isEmpty())
            throw new IllegalArgumentException("Empty encoded bootstrap argument");
        
        return switch (argument.charAt(0)) {
            case 'C' -> toClass(argument.substring(1), lookup);
            case 'M' -> toMethodType(argument.substring(1), lookup);
            case 'S' -> argument.substring(1);
            case 'I' -> Integer.valueOf(argument.substring(1));
            default -> throw new IllegalArgumentException("Unknown encoded bootstrap argument: " + argument);
        };
    }
    
    private static Object decodeSwitchArgument(
        Object argument,
        MethodHandles.Lookup lookup
    ) throws ClassNotFoundException {
        if (!(argument instanceof String encoded))
            return argument;
        if (encoded.isEmpty())
            throw new IllegalArgumentException("Empty encoded switch argument");
        
        return switch (encoded.charAt(0)) {
            case 'J' -> Long.valueOf(encoded.substring(1));
            case 'F' -> Float.valueOf(encoded.substring(1));
            case 'D' -> Double.valueOf(encoded.substring(1));
            case 'Z' -> Boolean.valueOf(encoded.substring(1));
            default -> decodeBootstrapArgument(encoded, lookup);
        };
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
