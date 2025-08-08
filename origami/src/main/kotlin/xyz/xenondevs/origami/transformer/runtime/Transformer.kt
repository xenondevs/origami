package xyz.xenondevs.origami.transformer.runtime

import org.objectweb.asm.tree.ClassNode

interface Transformer {
    
    fun getTargetClasses(): Set<String>
    
    fun transform(clazz: ClassNode, original: ByteArray): ClassNode?
    
}