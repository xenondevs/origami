package xyz.xenondevs.origami.transformer.runtime

import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.extensibility.IMixinConfig
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.ext.Extensions
import org.spongepowered.asm.mixin.transformer.ext.IExtension
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext
import org.spongepowered.asm.service.ISyntheticClassRegistry
import xyz.xenondevs.origami.asm.PatchClassWriter

class MixinTransformer(
    private val transformer: IMixinTransformer,
    private val configs: List<IMixinConfig>
) : Transformer {
    
    private val synthRegistry: ISyntheticClassRegistry = transformer.extensions.syntheticClassRegistry
    
    /**
     * Set of synthetic internal class names in the format of `org/example/MyClass`
     */
    var syntheticClasses: Set<String> = emptySet()
        private set
    
    /**
     * Map generated/patched classes in the format of
     * `org/example/MyClass` -> [ClassNode]
     */
    val exported: Map<String, ClassNode> 
        field = HashMap<String, ClassNode>()
    
    init {
        (transformer.extensions as Extensions).add(object : IExtension {
            override fun checkActive(environment: MixinEnvironment?) = true
            override fun preApply(context: ITargetClassContext?) = Unit
            override fun postApply(context: ITargetClassContext?) = Unit
            override fun export(env: MixinEnvironment?, name: String, force: Boolean, classNode: ClassNode) {
                exported[name.replace('.', '/')] = classNode
            }
        })
    }
    
    override fun getTargetClasses(): Set<String> {
        transformer.couldTransformClass(MixinEnvironment.getCurrentEnvironment(), "1")

        val mixinTargets = configs.flatMapTo(HashSet()) { it.targets }
        
        @Suppress("UNCHECKED_CAST") 
        syntheticClasses = (synthRegistry.javaClass.getDeclaredField("classes")
            .apply { isAccessible = true }
            .get(synthRegistry) as Map<String, ISyntheticClassRegistry>).keys
        
        return mixinTargets + syntheticClasses
    }
    
    override fun transform(clazz: ClassNode, original: ByteArray): ClassNode? {
        if (original.isEmpty()) {
            return generate(clazz.name)
        } else {
            val canonical = clazz.name.replace('/', '.')
            val transformed = transformer.transformClass(MixinEnvironment.getCurrentEnvironment(), canonical, clazz)
            return if (transformed) clazz else null
        }
    }
    
    fun generate(name: String): ClassNode? {
        val runtimeClass = synthRegistry.findSyntheticClass(name)
        if (runtimeClass != null) {
            val node = ClassNode()
            val generated = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name, node)
            if (generated)
                return node
        }
        return null
    }
    
}