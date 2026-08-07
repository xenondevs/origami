package xyz.xenondevs.origami

import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import xyz.xenondevs.origami.asm.LazyClassPath
import java.io.InputStream
import java.util.*

interface OrigamiEnvironment {
    
    val origamiClassLoader: ClassLoader
    val minecraftClasspath: LazyClassPath?
    val pluginLoader: PluginLoader?
    
    fun offer(factory: IMixinTransformerFactory)
    
    fun getLogger(name: String): ILogger
    
    fun getMinecraftResourceAsStream(name: String): InputStream?
    
    fun getTransformedBytecode(name: String): ByteArray?
    
    companion object : OrigamiEnvironment by ServiceLoader.load(
        OrigamiEnvironmentProvider::class.java,
        OrigamiEnvironment::class.java.classLoader
    ).single().environment
    
}