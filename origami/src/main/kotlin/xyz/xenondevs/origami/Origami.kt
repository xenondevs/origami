package xyz.xenondevs.origami

import com.llamalad7.mixinextras.MixinExtrasBootstrap
import kotlinx.coroutines.runBlocking
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.MixinEnvironment
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.asm.LookupProxy
import xyz.xenondevs.origami.mixin.OrigamiMixinService
import xyz.xenondevs.origami.transformer.paper.PaperTransformers
import xyz.xenondevs.origami.transformer.paper.PaperclipPatcher
import xyz.xenondevs.origami.transformer.runtime.TransformerRegistry
import xyz.xenondevs.origami.util.WriteOnlyArrayList
import java.lang.instrument.Instrumentation
import java.net.JarURLConnection
import java.net.URI
import java.net.URL

class Origami(
    val instrumentation: Instrumentation,
    appClassLoader: ClassLoader
) {
    
    lateinit var minecraftLoader: PatchingClassLoader
    
    lateinit var minecraftClasspath: LazyClassPath
    
    init {
        PaperclipPatcher.patch(instrumentation, appClassLoader)
    }
    
    @Suppress("unused") // call is injected by Origami
    fun init(urls: Array<URL>, minecraftLoader: ClassLoader) {
//        System.setProperty("mixin.debug", "true")
        check(minecraftLoader is PatchingClassLoader) { "Minecraft classloader was not initialized by Origami" }
        instance = this
        this.minecraftLoader = minecraftLoader
        
        val files = urls.mapNotNullTo(WriteOnlyArrayList()) {
            var url = it
            if (url.protocol != "jar") {
                if (url.protocol == "file" && url.path.endsWith(".jar")) {
                    url = URL("jar:${url.toExternalForm()}!/")
                } else {
                    return@mapNotNullTo null
                }
            }
            // This uses the cached JarFile from the URLClassLoader
            val connection = url.openConnection() as JarURLConnection
            connection.jarFile
        }
        minecraftClasspath = LazyClassPath(files)
        
        LookupProxy.init()
        instrumentation.addTransformer(PaperTransformers(instrumentation, minecraftClasspath))
        
        // Force mixin to not even check the other service implementation since they access invalid Minecraft classes
        System.setProperty("mixin.service", OrigamiMixinService::class.java.canonicalName)
        MixinBootstrap.init()
        runBlocking { PluginLoader.loadPlugins() }
        finishMixinPhases()
        MixinExtrasBootstrap.init()
        
        TransformerRegistry.init()
    }
    
    private fun finishMixinPhases() {
        val method = MixinEnvironment::class.java.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase::class.java)
        method.isAccessible = true
        method.invoke(null, MixinEnvironment.Phase.INIT)
        method.invoke(null, MixinEnvironment.Phase.DEFAULT)
    }
    
    companion object {
        @JvmStatic
        lateinit var instance: Origami
            private set
        
        @JvmStatic
        fun isMixinPlugin(name: String): Boolean {
            return PluginLoader.mixinPlugins.contains(name)
        }
    }
    
}