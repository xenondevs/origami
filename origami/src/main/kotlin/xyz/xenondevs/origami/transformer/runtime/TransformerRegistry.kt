package xyz.xenondevs.origami.transformer.runtime

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.MixinEnvironment
import xyz.xenondevs.origami.asm.PatchClassWriter
import java.util.concurrent.ConcurrentHashMap

object TransformerRegistry {
    
    private val transformers = listOf(MixinTransformer, AccessTransformer)
    private val toTransform = ConcurrentHashMap<String, MutableList<Transformer>>()
    
    fun init() {
        transformers.forEach { transformer ->
            transformer.getTargetClasses().forEach { clazz ->
                toTransform.computeIfAbsent(clazz.replace('.', '/')) { ArrayList() }.add(transformer)
            }
        }
    }
    
    @JvmStatic
    fun transform(bytecode: ByteArray, name: String): ByteArray {
        if (toTransform.isEmpty())
            return bytecode
        
        val classTransformers = toTransform.remove(name) ?: return bytecode
        return transformUnchecked(bytecode, name, classTransformers)
    }
    
    private fun transformUnchecked(bytecode: ByteArray, name: String, transformers: List<Transformer> = TransformerRegistry.transformers): ByteArray {
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