package xyz.xenondevs.origami.util

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode

/**
 * Converts an integer to the corresponding [LdcInsnNode] instruction or uses ``iconst``/``bipush``/``sipush`` if possible
 */
fun Int.toLdcInsn(): AbstractInsnNode {
    return when (this) {
        in -1..5 -> InsnNode(this + 3)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, this)
        in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, this)
        else -> LdcInsnNode(this)
    }
}

/**
 * Converts a long to the corresponding [LdcInsnNode] instruction or uses ``lconst`` if possible
 */
fun Long.toLdcInsn(): AbstractInsnNode {
    return when (this) {
        in 0..1 -> InsnNode((this + 9).toInt())
        else -> LdcInsnNode(this)
    }
}

/**
 * Converts a float to the corresponding [LdcInsnNode] instruction or uses ``fconst`` if possible
 */
fun Float.toLdcInsn(): AbstractInsnNode {
    return when {
        this % 1 == 0f && this in 0f..2f -> InsnNode((this + 11).toInt())
        else -> LdcInsnNode(this)
    }
}

/**
 * Converts a double to the corresponding [LdcInsnNode] or uses ``dconst`` if possible
 */
fun Double.toLdcInsn(): AbstractInsnNode {
    return when {
        this % 1 == 0.0 && this in 0.0..1.0 -> InsnNode((this + 14).toInt())
        else -> LdcInsnNode(this)
    }
}

fun AbstractInsnNode.findPreviousLabel(): AbstractInsnNode? {
    var current: AbstractInsnNode? = this.previous
    while (current != null) {
        if (current is LabelNode) return current
        current = current.previous
    }
    return null
}