package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.tree.ClassNode

interface PaperTransformer {
    
    val className: String
    
    fun transform(clazz: ClassNode)
    
}