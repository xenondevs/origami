package xyz.xenondevs.origami.transformer.runtime

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.MixinEnvironment
import xyz.xenondevs.origami.asm.PatchClassWriter

class TransformerRegistry(transformers: List<Transformer>) {
    
    val targetClasses: Map<String, List<Transformer>> = buildMap<String, MutableList<Transformer>> {
        for (transformer in transformers) {
            for (targetClass in transformer.getTargetClasses()) {
                getOrPut(targetClass.replace('.', '/'), ::ArrayList) += transformer
            }
        }
    }
    
    fun transform(bytecode: ByteArray, name: String): ByteArray {
        if (targetClasses.isEmpty())
            return bytecode
        
        val classTransformers = targetClasses[name] ?: return bytecode
        return runTransformers(bytecode, name, classTransformers)
    }
    
    private fun runTransformers(bytecode: ByteArray, name: String, transformers: List<Transformer>): ByteArray {
        var clazz = ClassNode()
        
        if (bytecode.isNotEmpty()) {
            ClassReader(bytecode).accept(clazz, 0)
        } else {
            clazz.apply {
                this.name = name
                this.superName = "java/lang/Object"
                version = MixinEnvironment.getCompatibilityLevel().classVersion
            }
        }
        
        var reassemble = false
        
        for (transformer in transformers) {
            clazz = transformer.transform(clazz, bytecode) ?: continue
            reassemble = true
        }
        
        return if (reassemble) {
            val bytes = PatchClassWriter().also { clazz.accept(it) }.toByteArray()
            bytes
        } else {
            bytecode
        }
    }
    
}