package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.asm.PatchClassWriter
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

class PaperTransformers(val instrumentation: Instrumentation, val classPath: LazyClassPath) : ClassFileTransformer {
    
    private val transformers = listOf(
        PaperPluginMetaTransformer, PaperPluginProviderFactoryTransformer, PaperPluginClassLoaderTransformer
    ).associateBy(PaperTransformer::className)
    private var transformedCount = 0
    
    init {
        classPath.codeNeeded.addAll(transformers.keys.map { "$it.class" })
    }
    
    override fun transform(loader: ClassLoader?, className: String, classBeingRedefined: Class<*>?, protectionDomain: ProtectionDomain, classfileBuffer: ByteArray): ByteArray? {
        try {
            val transformer = transformers[className] ?: return null
            val clazz = ClassNode().also { ClassReader(classfileBuffer).accept(it, ClassReader.SKIP_FRAMES) }
            transformer.transform(clazz)
            transformedCount++
            if (transformedCount == transformers.size)
                instrumentation.removeTransformer(this)
            
            val bytes = PatchClassWriter(classPath).also(clazz::accept).toByteArray()
            return bytes
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}