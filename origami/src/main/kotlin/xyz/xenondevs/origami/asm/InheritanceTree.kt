package xyz.xenondevs.origami.asm

import org.objectweb.asm.tree.ClassNode

class InheritanceTree(val clazz: ClassNode) {
    val superClasses = HashSet<ClassNode>()
    val subClasses = HashSet<ClassNode>()
}