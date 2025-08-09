package xyz.xenondevs.origami

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.FabricUtil
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.mixin.extensibility.IMixinConfig
import org.spongepowered.asm.service.MixinService
import org.yaml.snakeyaml.Yaml
import xyz.xenondevs.origami.asm.DynamicInvoker
import xyz.xenondevs.origami.mixin.OrigamiMixinService
import xyz.xenondevs.origami.transformer.runtime.AccessTransformer
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

object PluginLoader {
    
    private data class ConfigOwner(val jar: JarFile, val id: String, val name: String)
    
    private val configOwners = HashMap<String, ConfigOwner>()
    
    internal val mixinPlugins by lazy { configOwners.values.mapTo(HashSet(), ConfigOwner::name) }
    
    internal val mixinConfigs = ArrayList<IMixinConfig>()
    
    internal val mixinClasses = HashMap<String, ClassNode>()
    
    @Suppress("UNCHECKED_CAST")
    suspend fun loadPlugins() = coroutineScope {
        val plugins = Path("plugins").listDirectoryEntries(glob = "*.jar")
        
        data class PluginInfo(val jar: JarFile, val origamiJson: JsonObject, val paperYml: Map<String, Any>)
        
        val origamiPlugins = plugins.map { path ->
            async(Dispatchers.IO) {
                val url = path.toUri().toURL()
                val jar = (URL("jar:${url.toExternalForm()}!/").openConnection() as JarURLConnection).jarFile
                
                val origamiEntry = jar.getJarEntry("origami.json") ?: return@async null
                val paperEntry = jar.getJarEntry("paper-plugin.yml") ?: return@async null
                
                try {
                    val origamiJson = jar.getInputStream(origamiEntry)
                        .bufferedReader()
                        .use(JsonParser::parseReader)
                    val paperYml = jar.getInputStream(paperEntry)
                        .bufferedReader()
                        .use { Yaml().load<Map<String, Any>>(it) }
                    PluginInfo(jar, origamiJson.asJsonObject, paperYml)
                } catch (e: Exception) {
                    System.err.println("Failed to parse plugin from ${path.fileName}: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }
        }.awaitAll().filterNotNull()
        
        (MixinService.getService() as OrigamiMixinService).pluginsClasspath.files.addAll(origamiPlugins.map { it.jar })
        
        origamiPlugins.map {
            async(Dispatchers.IO) {
                try {
                    loadPlugin(it.jar, it.origamiJson, it.paperYml)
                } catch (e: Exception) {
                    System.err.println("Failed to load plugin from ${it.jar.name}: ${e.message}")
                    e.printStackTrace()
                }
            }
        }.awaitAll()
        
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
            
            
            mixins.forEach { mixinPath ->
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
    
    private suspend fun loadPlugin(jar: JarFile, origamiJson: JsonObject, paperYml: Map<String, Any>) = coroutineScope {
        val pluginName = paperYml["name"]?.toString()
            ?: throw IllegalArgumentException("Plugin does not have a valid name in paper-plugin.yml")
        
        val pluginId = origamiJson.getAsJsonPrimitive("pluginId")?.asString ?: pluginName
        // TODO: check origami version mismatch and tell user to run the plugin with a newer version of Origami as the agent
        
        val accessWidenerEntry = jar.getJarEntry("$pluginId.accesswidener")
        if (accessWidenerEntry != null) {
            synchronized(AccessTransformer) {
                AccessTransformer.readAccessWidener(jar.getInputStream(accessWidenerEntry))
            }
        }
        
        val mixinEntry = jar.getJarEntry("$pluginId.mixins.json")
        if (mixinEntry != null) {
            synchronized(mixinConfigs) {
                configOwners[mixinEntry.name] = ConfigOwner(jar, pluginId, pluginName)
                Mixins.addConfiguration(mixinEntry.name)
            }
        }
    }
    
}