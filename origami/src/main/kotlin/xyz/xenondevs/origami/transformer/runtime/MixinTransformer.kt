package xyz.xenondevs.origami.transformer.runtime

import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import org.spongepowered.asm.service.ISyntheticClassRegistry
import xyz.xenondevs.origami.PluginLoader

object MixinTransformer : Transformer {
    
    lateinit var transformer: IMixinTransformer
    lateinit var runtimeClasses: ISyntheticClassRegistry
    
    fun offer(factory: IMixinTransformerFactory) {
        transformer = factory.createTransformer()
        runtimeClasses = transformer.extensions.syntheticClassRegistry
    }
    
    override fun getTargetClasses(): Set<String> {
        transformer.couldTransformClass(MixinEnvironment.getCurrentEnvironment(), "1")
        val mixinTargets = PluginLoader.mixinConfigs.flatMapTo(HashSet()) { it.targets }
        val classesField = runtimeClasses.javaClass.getDeclaredField("classes").apply { isAccessible = true }.get(runtimeClasses) as Map<*, *>
        
        @Suppress("UNCHECKED_CAST")
        val syntheticClasses = classesField.keys as Set<String>
        
        return mixinTargets + syntheticClasses
    }
    
    override fun transform(clazz: ClassNode, original: ByteArray): ClassNode? {
        if (original.isEmpty()) {
            val runtimeClass = runtimeClasses.findSyntheticClass(clazz.name)
            if (runtimeClass != null) {
                val node = ClassNode()
                val generated = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), clazz.name, node)
                if (generated) return node
            }
        } else {
            val canonical = clazz.name.replace('/', '.')
            val transformed = transformer.transformClass(MixinEnvironment.getCurrentEnvironment(), canonical, clazz)
            return if (transformed) clazz else null
        }
        return null
    }
}