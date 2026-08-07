package xyz.xenondevs.origami

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerReader
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.spongepowered.asm.mixin.FabricUtil
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.mixin.extensibility.IMixinConfig
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import xyz.xenondevs.origami.asm.DynamicInvoker
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.transformer.runtime.AccessTransformer
import xyz.xenondevs.origami.transformer.runtime.MixinTransformer
import xyz.xenondevs.origami.transformer.runtime.TransformerRegistry
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.Path
import java.util.jar.JarFile

private data class PluginInfo(val jar: JarFile, val paperYml: Map<String, Any>) {
    val pluginName = paperYml["name"]?.toString()
        ?: throw IllegalArgumentException("Plugin does not have a valid name in paper-plugin.yml")
}

private data class ConfigOwner(val jar: JarFile, val id: String, val name: String)

class PluginLoader {
    
    private val configOwners = HashMap<String, ConfigOwner>()
    
    val mixinConfigs: List<IMixinConfig>
        field = ArrayList()
    
    val mixinClasses: Map<String, ClassNode>
        field = HashMap<String, ClassNode>()
    
    val pluginClasspath = LazyClassPath(includeAllCode = true)
    val pluginJars: Map<String, JarFile>
        field = HashMap()
    
    val accessWidener = AccessWidener()
    
    @Suppress("UNCHECKED_CAST")
    fun loadPlugins(pluginPaths: List<Path>) {
        // TODO: run in parallel again
        val origamiPlugins = pluginPaths.mapNotNull { path ->
            val url = path.toUri().toURL()
            val jar = (URI("jar:${url.toExternalForm()}!/").toURL().openConnection() as JarURLConnection).jarFile
            
            // origami.json is only used to mark origami plugins atm
            if (jar.getJarEntry("origami.json") == null)
                return@mapNotNull null
            val pluginYmlEntry = jar.getJarEntry("paper-plugin.yml")
                ?: return@mapNotNull null
            
            try {
                val pluginYml = jar.getInputStream(pluginYmlEntry)
                    .bufferedReader()
                    .use { Load(LoadSettings.builder().build()).loadFromReader(it) as Map<String, Any> }
                PluginInfo(jar, pluginYml)
            } catch (e: Exception) {
                System.err.println("Failed to parse plugin from ${path.fileName}: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        for (plugin in origamiPlugins) {
            pluginClasspath.files += plugin.jar
            pluginJars[plugin.pluginName] = plugin.jar
        }
        
        // TODO: run in parallel again
        for (plugin in origamiPlugins) {
            try {
                loadPlugin(plugin)
            } catch (e: Exception) {
                System.err.println("Failed to load plugin from ${plugin.jar.name}: ${e.message}")
                e.printStackTrace()
            }
        }
        
        for (config in Mixins.getConfigs()) {
            val plugin = configOwners[config.name] ?: continue
            val jar = plugin.jar
            val mixinConfig = config.config
            mixinConfig.decorate(FabricUtil.KEY_MOD_ID, plugin.id)
            mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST)
            
            val mixinPackage = mixinConfig.mixinPackage
            val field = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig")
                .getDeclaredField("mixinClasses").apply { isAccessible = true }
            val mixins = (field.get(mixinConfig) as List<String>).map { "$mixinPackage/$it".replace('.', '/') }
            
            for (mixinPath in mixins) {
                val jarPath = "$mixinPath.class"
                val je = jar.getJarEntry(jarPath)
                    ?: throw IllegalStateException("Mixin class '$mixinPath' not found in plugin '${plugin.name}' (${plugin.id})")
                
                val clazz = ClassNode()
                jar.getInputStream(je).use { inp ->
                    ClassReader(inp).accept(clazz, ClassReader.SKIP_FRAMES)
                }
                DynamicInvoker.transform(clazz, plugin.name)
                mixinClasses[mixinPath] = clazz
            }
            
            mixinConfigs.add(mixinConfig)
        }
    }
    
    private fun loadPlugin(info: PluginInfo) {
        // TODO: check origami version mismatch and tell user to run the plugin with a newer version of Origami as the agent
        
        info.jar.entries().asSequence()
            .filter { it.name.endsWith(".accesswidener") || it.name.endsWith(".aw") }
            .forEach { awEntry ->
                info.jar.getInputStream(awEntry)
                    .bufferedReader()
                    .use(AccessWidenerReader(accessWidener)::read)
            }
        
        info.jar.entries().asSequence()
            .filter { it.name.endsWith(".mixins.json") }
            .forEach { mixinEntry ->
                val cfgName = "${info.pluginName}:${mixinEntry.name}"
                configOwners[cfgName] = ConfigOwner(info.jar, info.pluginName, info.pluginName)
                Mixins.addConfiguration(cfgName)
            }
    }
    
}