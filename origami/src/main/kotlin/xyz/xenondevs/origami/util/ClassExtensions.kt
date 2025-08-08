package xyz.xenondevs.origami.util

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import kotlin.reflect.KClass

fun ClassNode.getMethod(name: String, desc: String): MethodNode? {
    return methods?.find { it.name == name && it.desc == desc }
}

fun ClassNode.getMethod(name: String, includesDesc: Boolean = false): MethodNode? {
    return methods?.find { if (includesDesc) it.name + it.desc == name else it.name == name }
}

fun ClassNode.assemble(options: Int = ClassWriter.COMPUTE_FRAMES): ByteArray {
    val writer = ClassWriter(options)
    accept(writer)
    return writer.toByteArray()
}

fun ClassNode.isInterface() = access and Opcodes.ACC_INTERFACE != 0

val Class<*>.internalName get() = name.replace('.', '/')

val KClass<*>.internalName get() = java.internalName