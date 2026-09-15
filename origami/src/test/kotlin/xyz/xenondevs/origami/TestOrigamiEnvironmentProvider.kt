package xyz.xenondevs.origami

import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import xyz.xenondevs.origami.asm.LazyClassPath
import java.io.InputStream

class TestOrigamiEnvironmentProvider : OrigamiEnvironmentProvider {
    override val environment: OrigamiEnvironment = TestOrigamiEnvironment
}

private object TestOrigamiEnvironment : OrigamiEnvironment {
    override val origamiClassLoader: ClassLoader = javaClass.classLoader
    override val minecraftClasspath = LazyClassPath()
    override val pluginLoader: PluginLoader? = null

    override fun offer(factory: IMixinTransformerFactory) = Unit

    override fun getLogger(name: String): ILogger =
        error("Logging is not used by DynamicInvoker tests")

    override fun getMinecraftResourceAsStream(name: String): InputStream? = null

    override fun getTransformedBytecode(name: String): ByteArray? = null
}
