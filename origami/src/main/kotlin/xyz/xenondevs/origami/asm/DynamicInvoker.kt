package xyz.xenondevs.origami.asm

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
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.PluginProxy
import xyz.xenondevs.origami.PluginProxy.HandleType
import xyz.xenondevs.origami.asm.DynamicInvoker.fixType
import xyz.xenondevs.origami.util.internalName

private typealias InsnIterator = MutableListIterator<AbstractInsnNode>

/**
 * Transforms mixin classes to replace all references to plugin classes with `java/lang/Object` and replaces method
 * calls/field accesses with `invokedynamics` that will be linked to method handles provided by [PluginProxy] at runtime.
 * Also tells [PluginProxy] which method handles will actually be needed for the plugin.
 *
 * TODO:
 * - Handle plugin class hierarchies properly to allow passing plugin class instances to methods expecting vanilla super
 *   classes (see [fixType])
 * - Run during compile time to speed up startup time. We'll still need to keep it present in runtime for legacy versions
 *   (origami-common?). Can use some annotation to mark transformed mixins to distinguish.
 * - Accessing other plugin classes from within mixins. Currently this invoker always assumes any non-Minecraft class is
 *   a class from the plugin the mixin belongs to. Obviously could also be a class from another plugin the current one
 *   depends on.
 * - Interfaces: From my understanding, this would require full codebase scanning and stack frame analysis to fully track
 *   where and how any instance of a plugin interface is being used to then replace whatever object with a proxy defined
 *   in the plugin's classloader. This proxy would implement said interface and just forward all calls to some handler
 *   that contains the original implementation from the Mixin. This would obviously be quite complex to do across huge
 *   plugins so doing it during compile time would be best. However, when some other plugin depends on the plugin that
 *   owns the interface but doesn't itself have the origami gradle plugin, we would pretty much always be forced to scan
 *   the entire thing at runtime. I am in general not sure if there are remaining use cases of adding interfaces to
 *   Minecraft classes given we generate accessors for any added members anyway and support access wideners that would
 *   justify this somewhat heavy performance hit (be that during build or runtime).
 */
object DynamicInvoker {
    
    val minecraftClassPath
        get() = Origami.instance.minecraftClasspath
    
    val PLUGIN_PROXY_NAME = PluginProxy::class.internalName
    
    val METHOD_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyMethod", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
    
    val CONSTRUCTOR_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyConstructor", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
    
    val FIELD_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyField", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
    
    val METAFACTORY_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyMetafactory", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false)
    
    val SWITCH_BOOTSTRAPS_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxySwitch", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;I[Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
    
    val INSTANCE_OF_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyInstanceOf", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
    
    val CLASS_PROXY_HANDLE = Handle(Opcodes.H_INVOKESTATIC, PLUGIN_PROXY_NAME, "proxyClass", $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false)
    
    fun transform(clazz: ClassNode, pluginName: String) {
        val currentMixin = clazz.name
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
                if (insn.next.opcode != Opcodes.DUP) {
                    // TODO: new call without doing anything with the result might be optimized to drop the dup in the
                    //       future. Haven't seen javac ever produce this yet tho.
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
                val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf()) { fixType(it, currentClass) }
                if (insn.opcode != Opcodes.INVOKESTATIC) {
                    argumentTypes.add(0, OBJECT_TYPE)
                }
                val newDesc = Type.getMethodDescriptor(returnType, *argumentTypes.toTypedArray())
                val isStatic = insn.opcode == Opcodes.INVOKESTATIC
                iter.set(InvokeDynamicInsnNode(name, newDesc, METHOD_PROXY_HANDLE, pluginName, owner, desc, if (isStatic) 1 else 0))
                PluginProxy.addRequiredHandle(pluginName, owner, if (isStatic) HandleType.STATIC_METHOD else HandleType.VIRTUAL_METHOD, name, desc)
            }
            
            Opcodes.INVOKESPECIAL -> {
                if (name != "<init>")
                    return // TODO: plugin types in desc possible?
                
                val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf()) { fixType(it, currentClass) }
                val newDesc = Type.getMethodDescriptor(OBJECT_TYPE, *argumentTypes.toTypedArray())
                iter.set(InvokeDynamicInsnNode("ctor" + desc.hashCode().toString(), newDesc, CONSTRUCTOR_PROXY_HANDLE, pluginName, owner, desc))
                PluginProxy.addRequiredHandle(pluginName, owner, HandleType.CONSTRUCTOR, name, desc)
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
            insn.desc = OBJECT_TYPE.descriptor
            return
        }
        val owner = OBJECT_TYPE.descriptor
        val newDesc = when (insn.opcode) {
            Opcodes.GETFIELD -> "($owner)$fieldDesc"
            Opcodes.PUTFIELD -> "($owner$fieldDesc)V"
            Opcodes.GETSTATIC -> "()$fieldDesc"
            Opcodes.PUTSTATIC -> "($fieldDesc)V"
            else -> throw IllegalStateException("Unexpected field insn opcode ${insn.opcode}")
        }
        
        iter.set(InvokeDynamicInsnNode(insn.name, newDesc, FIELD_PROXY_HANDLE, pluginName, insn.owner, insn.desc, insn.opcode))
        PluginProxy.addRequiredHandle(pluginName, insn.owner, HandleType.fromFieldOpcode(insn.opcode), insn.name, insn.desc)
    }
    
    fun visitMultiANewArrayInsn(insn: MultiANewArrayInsnNode, currentClass: String) {
        if (isPluginClass(insn.desc, currentClass))
            insn.desc = OBJECT_TYPE.internalName
    }
    
    fun visitInvokeDynamic(pluginName: String, iter: InsnIterator, insn: InvokeDynamicInsnNode, currentClass: String) {
        val handle = insn.bsm
        if (handle.owner == PLUGIN_PROXY_NAME)
            return
        
        if (handle.owner == "java/lang/invoke/LambdaMetafactory" && handle.name == "metafactory") {
            val targetMethod = insn.bsmArgs[1] as Handle
            val originalDynamicDesc = (insn.bsmArgs[2] as Type).descriptor
            if (targetMethod.owner == currentClass) {
                insn.bsmArgs[1] = Handle(
                    targetMethod.tag,
                    targetMethod.owner,
                    targetMethod.name,
                    fixDesc(targetMethod.desc, currentClass),
                    targetMethod.isInterface
                )
                insn.bsmArgs[2] = Type.getType(fixDesc(originalDynamicDesc, currentClass))
            } else if (isPluginClass(targetMethod.owner, currentClass)) {
                val interfaceType = insn.bsmArgs[0] as Type
                iter.set(InvokeDynamicInsnNode(
                    insn.name,
                    insn.desc,
                    METAFACTORY_PROXY_HANDLE,
                    pluginName,
                    interfaceType,
                    targetMethod.owner,
                    targetMethod.name,
                    targetMethod.desc,
                    originalDynamicDesc,
                    targetMethod.tag
                ))
                PluginProxy.addRequiredHandle(pluginName, targetMethod.owner, HandleType.fromTag(targetMethod.tag), targetMethod.name, targetMethod.desc)
            }
        } else if (handle.owner == "java/lang/runtime/SwitchBootstraps") {
            if (handle.name == "typeSwitch") {
                val types = insn.bsmArgs.map { (it as Type).internalName }
                if (types.any { isPluginClass(it, currentClass) }) {
                    iter.set(InvokeDynamicInsnNode(
                        insn.name,
                        insn.desc,
                        SWITCH_BOOTSTRAPS_PROXY_HANDLE,
                        pluginName,
                        0,
                        *types.toTypedArray()
                    ))
                }
            }
        }
    }
    
    fun visitLdc(pluginName: String, iter: InsnIterator, insn: LdcInsnNode, currentClass: String) {
        val cst = insn.cst
        if (cst !is Type)
            return
        
        if (cst.sort != Type.OBJECT || !isPluginClass(cst.internalName, currentClass))
            return
        
        // replace ldc with an indy that will resolve the type at runtime
        iter.set(InvokeDynamicInsnNode(
            "class" + cst.internalName.hashCode(),
            Type.getMethodDescriptor(CLASS_TYPE),
            CLASS_PROXY_HANDLE,
            pluginName,
            cst.internalName
        ))
    }
    
    private fun isPluginClass(internalName: String, currentClass: String): Boolean {
        return minecraftClassPath.getClass(internalName) == null && internalName != currentClass
    }
    
    /**
     * TODO:
     *
     * This currently leads to issues when passing a plugin class instance that has some vanilla super class in its
     * hierarchy to a method that expects the vanilla type, since the frame asm generates will have a `java/lang/Object`
     * type instead of the actual vanilla super class. One common example where this issue can occur is calling custom
     * Bukkit events from within a Mixin. To fix this properly, we'll need to build class hierarchies for plugins and
     * find the first non-plugin super class to use that as the type. Problem with that is that Paper plugins can
     * [configure their own library loaders](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/#loaders)
     * (another benefit of doing it during compile time).
     */
    private fun fixType(type: Type, currentClass: String): Type {
        return when (type.sort) {
            Type.OBJECT -> {
                if (isPluginClass(type.internalName, currentClass)) {
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
    
    private fun fixDesc(desc: String, currentClass: String): String {
        val returnType = fixType(Type.getReturnType(desc), currentClass)
        val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf()) { fixType(it, currentClass) }
        return Type.getMethodDescriptor(returnType, *argumentTypes.toTypedArray())
    }
    
}

val OBJECT_TYPE: Type = Type.getType(Object::class.java)
val CLASS_TYPE: Type = Type.getType(Class::class.java)