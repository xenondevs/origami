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
import xyz.xenondevs.origami.util.internalName

private typealias InsnIterator = MutableListIterator<AbstractInsnNode>

// TODO: Referencing other plugins from within mixins
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
    
    private fun fixDesc(desc: String, currentClass: String): String {
        val returnType = fixType(Type.getReturnType(desc), currentClass)
        val argumentTypes = Type.getArgumentTypes(desc).mapTo(mutableListOf()) { fixType(it, currentClass) }
        return Type.getMethodDescriptor(returnType, *argumentTypes.toTypedArray())
    }
    
}

val OBJECT_TYPE: Type = Type.getType(Object::class.java)
val CLASS_TYPE: Type = Type.getType(Class::class.java)