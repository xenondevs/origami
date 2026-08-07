package xyz.xenondevs.origami.jit

import com.llamalad7.mixinextras.MixinExtrasBootstrap
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import xyz.xenondevs.origami.OrigamiEnvironment
import xyz.xenondevs.origami.OrigamiEnvironmentProvider
import xyz.xenondevs.origami.PluginLoader
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.mixin.OrigamiMixinService
import xyz.xenondevs.origami.transformer.runtime.AccessTransformer
import xyz.xenondevs.origami.transformer.runtime.MixinTransformer
import xyz.xenondevs.origami.transformer.runtime.TransformerRegistry
import xyz.xenondevs.origami.util.WriteOnlyArrayList
import xyz.xenondevs.origami.util.finishMixinPhases
import java.io.InputStream
import java.lang.instrument.Instrumentation
import java.lang.invoke.MethodHandles
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

class OrigamiJitProvider : OrigamiEnvironmentProvider {
    override val environment: OrigamiEnvironment
        get() = OrigamiJit
}

object OrigamiJit : OrigamiEnvironment {
    
    private lateinit var instrumentation: Instrumentation
    private var mixinTransformer: IMixinTransformer? = null
    
    override val origamiClassLoader: ClassLoader
        get() = javaClass.classLoader
    var minecraftLoader: PatchingClassLoader? = null
        private set
    var minecraftLookup: MethodHandles.Lookup? = null
        private set
    override var minecraftClasspath: LazyClassPath? = null
        private set
    override var pluginLoader: PluginLoader? = null
        private set
    
    var transformerRegistry: TransformerRegistry? = null
        private set
    
    @Suppress("unused") // called from OrigamiAgent#premain
    @JvmStatic
    fun premain(instrumentation: Instrumentation, appClassLoader: ClassLoader) {
        this.instrumentation = instrumentation
        PaperclipPatcher.patch(instrumentation, appClassLoader)
    }
    
    @Suppress("unused") // called from OrigamiAgent#initOrigami
    @JvmStatic
    fun init(urls: Array<URL>, minecraftLoader: ClassLoader) {
        check(minecraftLoader is PatchingClassLoader) { "Minecraft classloader was not initialized by Origami" }
        
        this.minecraftLoader = minecraftLoader
        minecraftClasspath = LazyClassPath(urls.mapNotNullTo(WriteOnlyArrayList()) { url ->
            val con = when (url.protocol) {
                "jar" -> url
                "file" if url.path.endsWith(".jar") -> URI("jar:${url.toExternalForm()}!/").toURL()
                else -> null
            }?.openConnection()
            // This uses the cached JarFile from the URLClassLoader
            (con as? JarURLConnection)?.jarFile
        })
        minecraftLookup = Class.forName("xyz.xenondevs.origami.LookupProxy", true, minecraftLoader)
            .getMethod("getLookupForMinecraft")
            .invoke(null) as MethodHandles.Lookup
        
        instrumentation.addTransformer(PaperTransformers(instrumentation, minecraftClasspath!!))
        
        // Force mixin to not even check the other service implementation since they access invalid Minecraft classes
        System.setProperty("mixin.service", OrigamiMixinService::class.java.canonicalName)
        MixinBootstrap.init()
        
        pluginLoader = PluginLoader()
        // TODO: support command-line plugins
        val pluginJars = Path("plugins/").listDirectoryEntries().filter { it.extension.equals("jar", true) }
        pluginLoader!!.loadPlugins(pluginJars)
        
        finishMixinPhases()
        MixinExtrasBootstrap.init()
        
        transformerRegistry = TransformerRegistry(listOf(
            AccessTransformer(pluginLoader!!.accessWidener),
            MixinTransformer(mixinTransformer!!, pluginLoader!!.mixinConfigs)
        ))
    }
    
    override fun offer(factory: IMixinTransformerFactory) {
        mixinTransformer = factory.createTransformer()
    }
    
    override fun getMinecraftResourceAsStream(name: String): InputStream? =
        minecraftLoader?.getResourceAsStream(name)
    
    override fun getTransformedBytecode(name: String): ByteArray? =
        minecraftLoader?.getTransformedData(name, false)?.bytecode
    
    override fun getLogger(name: String) = getLoggerAdapter(name)
    
}