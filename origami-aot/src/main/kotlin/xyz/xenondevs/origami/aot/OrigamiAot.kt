package xyz.xenondevs.origami.aot

import com.llamalad7.mixinextras.MixinExtrasBootstrap
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.slf4j.LoggerFactory
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import xyz.xenondevs.origami.OrigamiEnvironment
import xyz.xenondevs.origami.OrigamiEnvironmentProvider
import xyz.xenondevs.origami.PluginLoader
import xyz.xenondevs.origami.asm.LazyClassPath
import xyz.xenondevs.origami.asm.PatchClassWriter
import xyz.xenondevs.origami.mixin.OrigamiMixinService
import xyz.xenondevs.origami.transformer.paper.PaperPluginClassLoaderTransformer
import xyz.xenondevs.origami.transformer.runtime.AccessTransformer
import xyz.xenondevs.origami.transformer.runtime.MixinTransformer
import xyz.xenondevs.origami.transformer.runtime.TransformerRegistry
import xyz.xenondevs.origami.util.WriteOnlyArrayList
import xyz.xenondevs.origami.util.finishMixinPhases
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.Path

class OrigamiAotProvider : OrigamiEnvironmentProvider {
    override val environment: OrigamiEnvironment
        get() = OrigamiAot
}

object OrigamiAot : OrigamiEnvironment {
    
    override val origamiClassLoader: ClassLoader
        get() = javaClass.classLoader
    override lateinit var minecraftClasspath: LazyClassPath
    override lateinit var pluginLoader: PluginLoader
    private var mixinTransformer: IMixinTransformer? = null
    
    private var transformerRegistry: TransformerRegistry? = null
    
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Usage: java -jar OrigamiAot.jar <input (server jar)> <output> <classpath w/o server jar> <plugins>" }
        
        val inputJarFile = JarFile(File(args[0]))
        
        val classpathJarFiles = args[2].split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map(::File)
            .mapTo(WriteOnlyArrayList(), ::JarFile)
        classpathJarFiles.add(0, inputJarFile)
        minecraftClasspath = LazyClassPath(classpathJarFiles)
        
        // Force mixin to not even check the other service implementation since they access invalid Minecraft classes
        System.setProperty("mixin.service", OrigamiMixinService::class.java.canonicalName)
        
        MixinBootstrap.init()
        
        pluginLoader = PluginLoader()
        pluginLoader.loadPlugins(args[3].split(File.pathSeparator).filter(String::isNotBlank).map(::Path))
        
        finishMixinPhases()
        MixinExtrasBootstrap.init()
        
        val mt = MixinTransformer(mixinTransformer!!, pluginLoader.mixinConfigs)
        transformerRegistry = TransformerRegistry(listOf(
            AccessTransformer(pluginLoader.accessWidener),
            mt
        ))
        
        val exported = HashSet<String>()
        JarOutputStream(File(args[1]).outputStream().buffered()).use { out ->
            // transform existing classes
            for (entry in inputJarFile.entries()) {
                if (entry.isDirectory)
                    continue
                out.putNextEntry(entry)
                if (entry.name.endsWith(".class")) {
                    val data = getTransformedBytecode(entry.name.substringBeforeLast(".class"))
                        ?: throw IllegalStateException("Couldn't get transformed data for server class: ${entry.name}")
                    out.write(data)
                } else {
                    inputJarFile.getInputStream(entry).transferTo(out)
                }
                exported += entry.name
            }
            
            // generate synth classes
            for (synthName in mt.syntheticClasses) {
                val synthClassName = "$synthName.class"
                val node = mt.generate(synthName)
                checkNotNull(node) { "did not generate synth class $synthName" }
                out.putNextEntry(ZipEntry(synthClassName))
                out.write(PatchClassWriter(minecraftClasspath).also(node::accept).toByteArray())
                exported += synthClassName
            }
            
            // add exported generated classes (like LocalRefImpl)
            for ((name, node) in mt.exported) {
                val className = "$name.class"
                if (className in exported)
                    continue
                out.putNextEntry(ZipEntry(className))
                out.write(PatchClassWriter(minecraftClasspath).also(node::accept).toByteArray())
            }
        }
    }
    
    override fun offer(factory: IMixinTransformerFactory) {
        mixinTransformer = factory.createTransformer()
    }
    
    override fun getLogger(name: String) = Slf4jLoggerAdapter(LoggerFactory.getLogger(name))
    
    override fun getMinecraftResourceAsStream(name: String): InputStream? =
        minecraftClasspath.findResourceStream(name)
    
    override fun getTransformedBytecode(name: String): ByteArray? {
        val resourceName = "$name.class";
        val stream = if (name.startsWith("org/spongepowered/asm") || name.startsWith("com/llamalad7/mixinextras")) {
            origamiClassLoader.getResourceAsStream(resourceName)
        } else {
            minecraftClasspath.findResourceStream(resourceName)
        }
        
        var bytecode = stream?.readBytes()
            ?: return null
        
        if (name == PaperPluginClassLoaderTransformer.className) {
            val node = ClassNode().also { ClassReader(bytecode).accept(it, ClassReader.SKIP_FRAMES) }
            PaperPluginClassLoaderTransformer.transform(node)
            bytecode = PatchClassWriter(minecraftClasspath).also(node::accept).toByteArray()
        }
        
        return transformerRegistry?.transform(bytecode, name) ?: bytecode
    }
    
}