package xyz.xenondevs.origami.asm

import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.xenondevs.origami.OrigamiEnvironment

private typealias InsnIterator = MutableListIterator<AbstractInsnNode>

private val OBJECT_TYPE: Type = Type.getType(Object::class.java)
private val CLASS_TYPE: Type = Type.getType(Class::class.java)
private const val PLUGIN_PROXY_NAME = "xyz/xenondevs/origami/PluginProxy"
private val METHOD_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyMethod", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
private val CONSTRUCTOR_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyConstructor", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
private val FIELD_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyField", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
private val METAFACTORY_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyMetafactory", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
private val LOCAL_METAFACTORY_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyLocalMetafactory", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/invoke/MethodHandle;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
private val ALT_METAFACTORY_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyAltMetafactory", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II[Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
private val LOCAL_ALT_METAFACTORY_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyLocalAltMetafactory", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/invoke/MethodHandle;Ljava/lang/String;I[Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
private val SWITCH_BOOTSTRAPS_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxySwitch", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false)
private val INSTANCE_OF_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyInstanceOf", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
private val CLASS_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyClass", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)

// TODO: Referencing other plugins from within mixins
object DynamicInvoker {
    
    fun transform(clazz: ClassNode, pluginName: String, currentMixin: String = clazz.name) {
        clazz.methods.forEach { m ->
            val insns = m.instructions
            val iter = insns.iterator()
            while (iter.hasNext()) {
                when (val insn = iter.next()) {
                    is TypeInsnNode -> visitTypeInsn(pluginName, insns, iter, insn, currentMixin)
                    is MethodInsnNode -> visitMethodInsn(pluginName, insns, iter, insn, currentMixin)
                    is FieldInsnNode -> visitFieldInsn(pluginName, insns, iter, insn, currentMixin)
                    is MultiANewArrayInsnNode -> visitMultiANewArrayInsn(insn, currentMixin)
                    is InvokeDynamicInsnNode -> visitInvokeDynamic(pluginName, iter, insn, currentMixin)
                    is LdcInsnNode -> visitLdc(pluginName, iter, insn, currentMixin)
                }
            }
            m.desc = fixDesc(m.desc, currentMixin)
        }
        
        clazz.fields.forEach { f ->
            f.desc = fixType(Type.getType(f.desc), currentMixin).descriptor
        }
    }
    
    fun visitTypeInsn(pluginName: String, list: InsnList, iter: InsnIterator, insn: TypeInsnNode, currentClass: String) {
        if (!isPluginClass(insn.desc, currentClass))
            return
        
        when (insn.opcode) {
            Opcodes.NEW -> {
                if (insn.next?.opcode != Opcodes.DUP) {
                    // TODO: new call without doing anything with the result
                    throw IllegalStateException("Unknown object allocation pattern. Expected DUP after NEW")
                }
                // Plugin class allocations can be omitted since the constructor method handle will create the object.
                iter.remove() // Remove NEW
                iter.next()
                iter.remove() // Remove DUP
            }
            
            Opcodes.CHECKCAST -> {
                // All plugin casts are done by method handle layers. The mixin class only knows plugin classes as Objects.
                iter.remove()
            }
            
            Opcodes.ANEWARRAY -> insn.desc = OBJECT_TYPE.internalName
            
            Opcodes.INSTANCEOF -> {
                // Instanceof checks are replaced with a call to Class.isInstance(Object) method handle with the receiver
                // argument already bound as generated by PluginProxy.proxyInstanceOf.
                iter.set(InvokeDynamicInsnNode("instanceOf" + insn.desc.hashCode(), "(Ljava/lang/Object;)Z", INSTANCE_OF_PROXY_HANDLE, pluginName, insn.desc))
            }
            
            else -> throw IllegalStateException("Unexpected type insn opcode ${insn.opcode}")
        }
    }
    
    fun visitMethodInsn(pluginName: String, list: InsnList, iter: InsnIterator, insn: MethodInsnNode, currentClass: String) {
        if (!isPluginClass(insn.owner, currentClass)) {
            insn.desc = fixDesc(insn.desc, currentClass)
            return
        }
        
        val owner = insn.owner
        val name = insn.name
        val desc = insn.desc
        
        when (insn.opcode) {
            Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                val returnType = fixType(Type.getReturnType(desc), currentClass)
                val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf(), ::eraseProxyInputType)
                if (insn.opcode != Opcodes.INVOKESTATIC) {
                    argumentTypes.add(0, OBJECT_TYPE)
                }
                val newDesc = Type.getMethodDescriptor(returnType, *argumentTypes.toTypedArray())
                val isStatic = insn.opcode == Opcodes.INVOKESTATIC
                iter.set(InvokeDynamicInsnNode(name, newDesc, METHOD_PROXY_HANDLE, pluginName, owner, desc, if (isStatic) 1 else 0))
            }
            
            Opcodes.INVOKESPECIAL -> {
                if (name != "<init>")
                    return // TODO: plugin types in desc possible?
                
                val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf(), ::eraseProxyInputType)
                val newDesc = Type.getMethodDescriptor(OBJECT_TYPE, *argumentTypes.toTypedArray())
                iter.set(InvokeDynamicInsnNode("ctor" + desc.hashCode().toString(), newDesc, CONSTRUCTOR_PROXY_HANDLE, pluginName, owner, desc))
            }
            
            else -> throw IllegalStateException("Unexpected method insn opcode ${insn.opcode}")
        }
    }
    
    fun visitFieldInsn(pluginName: String, list: InsnList, iter: InsnIterator, insn: FieldInsnNode, currentClass: String) {
        val fieldDesc = fixType(Type.getType(insn.desc), currentClass).descriptor
        val isPluginType = fieldDesc != insn.desc
        val isPluginOwner = isPluginClass(insn.owner, currentClass)
        if (!isPluginType && !isPluginOwner)
            return
        
        if (isPluginType && !isPluginOwner) {
            insn.desc = fieldDesc
            return
        }
        val owner = OBJECT_TYPE.descriptor
        val inputDesc = eraseProxyInputType(Type.getType(insn.desc)).descriptor
        val newDesc = when (insn.opcode) {
            Opcodes.GETFIELD -> "($owner)$fieldDesc"
            Opcodes.PUTFIELD -> "($owner$inputDesc)V"
            Opcodes.GETSTATIC -> "()$fieldDesc"
            Opcodes.PUTSTATIC -> "($inputDesc)V"
            else -> throw IllegalStateException("Unexpected field insn opcode ${insn.opcode}")
        }
        
        iter.set(InvokeDynamicInsnNode(insn.name, newDesc, FIELD_PROXY_HANDLE, pluginName, insn.owner, insn.desc, insn.opcode))
    }
    
    fun visitMultiANewArrayInsn(insn: MultiANewArrayInsnNode, currentClass: String) {
        insn.desc = fixType(Type.getType(insn.desc), currentClass).descriptor
    }
    
    fun visitInvokeDynamic(pluginName: String, iter: InsnIterator, insn: InvokeDynamicInsnNode, currentClass: String) {
        val handle = insn.bsm
        if (handle.owner == PLUGIN_PROXY_NAME)
            return
        
        if (handle.owner == "java/lang/invoke/LambdaMetafactory" && handle.name == "metafactory") {
            val targetMethod = insn.bsmArgs[1] as Handle
            val interfaceType = insn.bsmArgs[0] as Type
            val originalDynamicDesc = (insn.bsmArgs[2] as Type).descriptor
            val fixedFactoryDesc = fixDesc(insn.desc, currentClass)
            val proxyFactoryDesc = eraseReferenceArguments(fixedFactoryDesc)
            val fixedInterfaceDesc = fixDesc(interfaceType.descriptor, currentClass)
            val fixedTargetDesc = fixDesc(targetMethod.desc, currentClass)
            val fixedDynamicDesc = fixDesc(originalDynamicDesc, currentClass)
            val targetIsCurrentClass = targetMethod.owner == currentClass
            val metadataChanged = fixedInterfaceDesc != interfaceType.descriptor
                || fixedTargetDesc != targetMethod.desc
                || fixedDynamicDesc != originalDynamicDesc
            
            // LambdaMetafactory requires the factory return type to be the actual functional interface.
            // If that interface belongs to the plugin (for example kotlin.jvm.functions.Function1), the
            // call site therefore has to go through our proxy: the transformed Minecraft class can only
            // expose Object in its descriptor, while the proxy restores the interface using the plugin loader.
            if (targetIsCurrentClass && fixedFactoryDesc != insn.desc) {
                iter.set(InvokeDynamicInsnNode(
                    insn.name,
                    proxyFactoryDesc,
                    LOCAL_METAFACTORY_PROXY_HANDLE,
                    pluginName,
                    insn.desc,
                    interfaceType.descriptor,
                    Handle(
                        targetMethod.tag,
                        targetMethod.owner,
                        targetMethod.name,
                        fixedTargetDesc,
                        targetMethod.isInterface
                    ),
                    originalDynamicDesc
                ))
            } else if (isPluginClass(targetMethod.owner, currentClass)
                || fixedFactoryDesc != insn.desc
                || (!targetIsCurrentClass && metadataChanged)
            ) {
                iter.set(InvokeDynamicInsnNode(
                    insn.name,
                    proxyFactoryDesc,
                    METAFACTORY_PROXY_HANDLE,
                    pluginName,
                    insn.desc,
                    interfaceType.descriptor,
                    targetMethod.owner,
                    targetMethod.name,
                    targetMethod.desc,
                    originalDynamicDesc,
                    targetMethod.tag
                ))
            } else if (targetIsCurrentClass) {
                insn.bsmArgs[1] = Handle(
                    targetMethod.tag,
                    targetMethod.owner,
                    targetMethod.name,
                    fixedTargetDesc,
                    targetMethod.isInterface
                )
                insn.bsmArgs[0] = Type.getType(fixedInterfaceDesc)
                insn.bsmArgs[2] = Type.getType(fixedDynamicDesc)
            }
        } else if (handle.owner == "java/lang/invoke/LambdaMetafactory" && handle.name == "altMetafactory") {
            visitAltMetafactory(pluginName, iter, insn, currentClass)
        } else if (handle.owner == "java/lang/runtime/SwitchBootstraps") {
            if (handle.name == "typeSwitch" || handle.name == "enumSwitch") {
                val hasPluginLabel = insn.bsmArgs
                    .filterIsInstance<Type>()
                    .any { it.sort == Type.OBJECT && isPluginClass(it.internalName, currentClass) }
                val fixedDesc = fixDesc(insn.desc, currentClass)
                if (hasPluginLabel || fixedDesc != insn.desc) {
                    val labels = insn.bsmArgs.map(::encodeSwitchArgument)
                    iter.set(InvokeDynamicInsnNode(
                        insn.name,
                        fixedDesc,
                        SWITCH_BOOTSTRAPS_PROXY_HANDLE,
                        pluginName,
                        insn.desc,
                        if (handle.name == "enumSwitch") 1 else 0,
                        *labels.toTypedArray()
                    ))
                }
            }
        } else if (handle.owner == "java/lang/invoke/StringConcatFactory"
            && (handle.name == "makeConcat" || handle.name == "makeConcatWithConstants")
        ) {
            insn.desc = fixDesc(insn.desc, currentClass)
        }
    }
    
    private fun visitAltMetafactory(
        pluginName: String,
        iter: InsnIterator,
        insn: InvokeDynamicInsnNode,
        currentClass: String
    ) {
        val interfaceType = insn.bsmArgs[0] as Type
        val targetMethod = insn.bsmArgs[1] as Handle
        val dynamicType = insn.bsmArgs[2] as Type
        val flags = insn.bsmArgs[3] as Int
        val fixedFactoryDesc = fixDesc(insn.desc, currentClass)
        val proxyFactoryDesc = eraseReferenceArguments(fixedFactoryDesc)
        val fixedInterfaceDesc = fixDesc(interfaceType.descriptor, currentClass)
        val fixedTargetDesc = fixDesc(targetMethod.desc, currentClass)
        val fixedDynamicDesc = fixDesc(dynamicType.descriptor, currentClass)
        val targetIsCurrentClass = targetMethod.owner == currentClass
        val optionalMetadataNeedsPluginLoader = insn.bsmArgs.drop(4)
            .filterIsInstance<Type>()
            .any { type ->
                when (type.sort) {
                    Type.METHOD -> !targetIsCurrentClass
                        && fixDesc(type.descriptor, currentClass) != type.descriptor
                    
                    Type.OBJECT -> isPluginClass(type.internalName, currentClass)
                    Type.ARRAY -> fixType(type, currentClass) != type
                    else -> false
                }
            }
        val metadataChanged = fixedInterfaceDesc != interfaceType.descriptor
            || fixedTargetDesc != targetMethod.desc
            || fixedDynamicDesc != dynamicType.descriptor
        
        if (targetIsCurrentClass && (fixedFactoryDesc != insn.desc || optionalMetadataNeedsPluginLoader)) {
            val optionalArgs = insn.bsmArgs.drop(4).map(::encodeBootstrapArgument)
            iter.set(InvokeDynamicInsnNode(
                insn.name,
                proxyFactoryDesc,
                LOCAL_ALT_METAFACTORY_PROXY_HANDLE,
                pluginName,
                insn.desc,
                interfaceType.descriptor,
                Handle(
                    targetMethod.tag,
                    targetMethod.owner,
                    targetMethod.name,
                    fixedTargetDesc,
                    targetMethod.isInterface
                ),
                dynamicType.descriptor,
                flags,
                *optionalArgs.toTypedArray()
            ))
        } else if (isPluginClass(targetMethod.owner, currentClass)
            || fixedFactoryDesc != insn.desc
            || optionalMetadataNeedsPluginLoader
            || (!targetIsCurrentClass && metadataChanged)
        ) {
            val optionalArgs = insn.bsmArgs.drop(4).map(::encodeBootstrapArgument)
            iter.set(InvokeDynamicInsnNode(
                insn.name,
                proxyFactoryDesc,
                ALT_METAFACTORY_PROXY_HANDLE,
                pluginName,
                insn.desc,
                interfaceType.descriptor,
                targetMethod.owner,
                targetMethod.name,
                targetMethod.desc,
                dynamicType.descriptor,
                targetMethod.tag,
                flags,
                *optionalArgs.toTypedArray()
            ))
        } else if (targetIsCurrentClass) {
            insn.bsmArgs[0] = Type.getMethodType(fixedInterfaceDesc)
            insn.bsmArgs[1] = Handle(
                targetMethod.tag,
                targetMethod.owner,
                targetMethod.name,
                fixedTargetDesc,
                targetMethod.isInterface
            )
            insn.bsmArgs[2] = Type.getMethodType(fixedDynamicDesc)
            for (index in 4 until insn.bsmArgs.size) {
                val type = insn.bsmArgs[index] as? Type ?: continue
                if (type.sort == Type.METHOD) {
                    insn.bsmArgs[index] = Type.getMethodType(fixDesc(type.descriptor, currentClass))
                }
            }
        }
    }
    
    private fun encodeBootstrapArgument(argument: Any): String {
        return when (argument) {
            is Type -> when (argument.sort) {
                Type.METHOD -> "M${argument.descriptor}"
                Type.OBJECT, Type.ARRAY -> "C${argument.descriptor}"
                else -> throw IllegalArgumentException("Unsupported bootstrap type argument: $argument")
            }
            
            is String -> "S$argument"
            is Int -> "I$argument"
            else -> throw IllegalArgumentException(
                "Unsupported bootstrap argument ${argument.javaClass.name}: $argument"
            )
        }
    }
    
    private fun encodeSwitchArgument(argument: Any): Any {
        return when (argument) {
            is Long -> "J$argument"
            is Float -> "F$argument"
            is Double -> "D$argument"
            is Boolean -> "Z$argument"
            is ConstantDynamic -> argument
            else -> encodeBootstrapArgument(argument)
        }
    }
    
    fun visitLdc(pluginName: String, iter: InsnIterator, insn: LdcInsnNode, currentClass: String) {
        val cst = insn.cst
        if (cst !is Type)
            return
        
        val referencedType = when (cst.sort) {
            Type.OBJECT -> cst
            Type.ARRAY -> cst.elementType
            else -> return
        }
        if (referencedType.sort != Type.OBJECT || !isPluginClass(referencedType.internalName, currentClass))
            return
        
        // replace ldc with an indy that will resolve the type at runtime
        iter.set(InvokeDynamicInsnNode(
            "class" + cst.internalName.hashCode(),
            Type.getMethodDescriptor(CLASS_TYPE),
            CLASS_PROXY_HANDLE,
            pluginName,
            if (cst.sort == Type.ARRAY) cst.descriptor else cst.internalName
        ))
    }
    
    private fun isPluginClass(internalName: String, currentClass: String): Boolean {
        return internalName != currentClass
            && !internalName.startsWith("$currentClass\$")
            && !internalName.startsWith("org/spongepowered/asm/mixin")
            && !internalName.startsWith("com/llamalad7/mixinextras")
            && OrigamiEnvironment.minecraftClasspath?.getClass(internalName) == null
    }
    
    private fun fixType(type: Type, currentClass: String): Type {
        return when (type.sort) {
            Type.OBJECT -> {
                if (isPluginClass(type.internalName, currentClass)) {
                    // TODO | this could in theory be optimized to instead search for the first superclass that is not a
                    // TODO | plugin class to support better frame optimizations by the JVM. In turn, this would obviously
                    // TODO | also require to build a class hierarchy for server and default library classes.
                    OBJECT_TYPE
                } else {
                    type
                }
            }
            
            Type.ARRAY -> {
                val elementType = fixType(type.elementType, currentClass)
                Type.getType("[".repeat(type.dimensions) + elementType.descriptor)
            }
            
            else -> type
        }
    }

    private fun eraseProxyInputType(type: Type): Type {
        // Removing a plugin CHECKCAST leaves Object as the verifier type. Proxy inputs therefore accept
        // every reference as Object; the original descriptor remains bootstrap metadata for MethodHandle.asType.
        return if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) OBJECT_TYPE else type
    }

    private fun eraseReferenceArguments(desc: String): String {
        val returnType = Type.getReturnType(desc)
        val argumentTypes = Type.getArgumentTypes(desc).map(::eraseProxyInputType).toTypedArray()
        return Type.getMethodDescriptor(returnType, *argumentTypes)
    }
    
    private fun fixDesc(desc: String, currentClass: String): String {
        val returnType = fixType(Type.getReturnType(desc), currentClass)
        val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf()) { fixType(it, currentClass) }
        return Type.getMethodDescriptor(returnType, *argumentTypes.toTypedArray())
    }
    
}
