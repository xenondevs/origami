package xyz.xenondevs.origami.mixin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual
import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel
import org.spongepowered.asm.mixin.MixinEnvironment.Phase
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import org.spongepowered.asm.service.IClassBytecodeProvider
import org.spongepowered.asm.service.IClassProvider
import org.spongepowered.asm.service.IMixinInternal
import org.spongepowered.asm.service.IMixinService
import org.spongepowered.asm.util.Constants
import org.spongepowered.asm.util.ReEntranceLock
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.PluginLoader
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.transformer.runtime.MixinTransformer
import xyz.xenondevs.origami.util.WriteOnlyArrayList
import java.io.InputStream
import java.net.URL

class OrigamiMixinService : IMixinService, IClassProvider, IClassBytecodeProvider {
    
    val pluginsClasspath = LazyClassPath(WriteOnlyArrayList(), includeAllCode = true)
    
    val lock = ReEntranceLock(1)
    val origami = Origami.instance
    
    val container = ContainerHandleVirtual("Origami")
    
    override fun getName(): String = "Origami"
    
    override fun isValid() = true
    
    override fun getInitialPhase(): Phase = Phase.PREINIT
    
    override fun offer(internal: IMixinInternal) {
        if (internal is IMixinTransformerFactory) {
            MixinTransformer.offer(internal)
        }
    }
    
    override fun getReEntranceLock() = lock
    
    override fun getClassProvider() = this
    
    override fun getBytecodeProvider() = this
    
    override fun getResourceAsStream(name: String): InputStream? {
        return pluginsClasspath.findResourceStream(name)
            ?: origami.minecraftLoader.getResourceAsStream(name)
    }
    
    override fun getTransformerProvider() = null
    
    /**
     * While we could support this, it would prevent us from being able to apply mixins through instrumentation.
     */
    override fun getClassTracker() = null
    
    override fun getSideName() = Constants.SIDE_SERVER
    
    override fun getMinCompatibilityLevel() = CompatibilityLevel.JAVA_21
    
    override fun getMaxCompatibilityLevel() = CompatibilityLevel.JAVA_22
    
    override fun getLogger(name: String) = MixinLoggers.getLogger(name)
    
    override fun prepare() = Unit
    
    override fun init() = Unit
    
    override fun beginPhase() = Unit
    
    override fun checkEnv(bootSource: Any?) = Unit
    
    override fun getAuditTrail() = null
    
    override fun getPlatformAgents() = emptyList<String>()
    
    override fun getPrimaryContainer() = container
    
    override fun getMixinContainers() = emptyList<IContainerHandle>()
    
    @Deprecated("As of 0.8, use of this method is not a sensible way to access available containers. ")
    override fun getClassPath() = emptyArray<URL>()
    
    override fun findClass(name: String): Class<*> {
        return findClass(name, true)
    }
    
    override fun findClass(name: String, initialize: Boolean): Class<*> {
        return Class.forName(name, initialize, origami.javaClass.classLoader)
    }
    
    override fun findAgentClass(name: String, initialize: Boolean): Class<*>? {
        return null
    }
    
    override fun getClassNode(name: String): ClassNode? {
        return getClassNode(name, true, 0)
    }
    
    override fun getClassNode(name: String, runTransformers: Boolean): ClassNode? {
        return getClassNode(name, runTransformers, 0)
    }
    
    override fun getClassNode(name: String, runTransformers: Boolean, readerFlags: Int): ClassNode? {
        // TODO(?) if this is ever actually needed, have a second instance of LazyClassPath with shared package hints
        //         to get the original class files.
        require(runTransformers) { "Getting original class nodes is currently not supported in Origami." }
        
        val internal = name.replace('.', '/')
        
        val transformed = origami.minecraftLoader.getTransformedData(internal, false)
        if (transformed != null) { // TODO cache
            val node = ClassNode()
            val reader = ClassReader(transformed.bytecode())
            reader.accept(node, readerFlags)
            return node
        }
        
        val mixinClazz = PluginLoader.mixinClasses[internal]
        if (mixinClazz != null)
            return mixinClazz
        
        // TODO: leave this out?
        return pluginsClasspath.getClass(internal)
    }
    
    
}