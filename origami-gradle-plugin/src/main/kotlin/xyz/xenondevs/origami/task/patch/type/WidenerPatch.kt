package xyz.xenondevs.origami.task.patch.type

import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerClassVisitor
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.ForwardingVisitor
import net.fabricmc.accesswidener.TransitiveOnlyFilter
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import xyz.xenondevs.origami.AccessWidenerConfig
import xyz.xenondevs.origami.ProjectAccessWidener
import xyz.xenondevs.origami.task.patch.source.WidenerSourceVisitor
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class WidenerPatch(
    accessWidenerFile: RegularFileProperty,
    transitiveAccessWidenerSources: ConfigurableFileCollection,
    logger: Logger
) : PatchType() {
    
    val widener: ProjectAccessWidener?

    init {
        val accessWidener = AccessWidener()
        val config = AccessWidenerConfig()
        
        if (accessWidenerFile.isPresent) {
            val projectAw = accessWidenerFile.asFile.get()
            logger.info("Using project access wideners from ${projectAw.name}")
            projectAw.bufferedReader().use { reader ->
                val awr = AccessWidenerReader(ForwardingVisitor(config, accessWidener))
                awr.read(reader)
            }
        }
        
        transitiveAccessWidenerSources.asSequence()
            .filter { file -> file.extension.equals("jar", true) }
            .forEach { jarFile ->
                ZipInputStream(jarFile.inputStream().buffered()).use zip@{ zin ->
                    generateSequence { zin.nextEntry }
                        .filter { entry -> entry.name.endsWith(".accesswidener") || entry.name.endsWith(".aw") }
                        .forEach { entry ->
                            logger.info("Using transitive access wideners from ${jarFile.name} (${entry.name})")
                            val awr = AccessWidenerReader(TransitiveOnlyFilter(ForwardingVisitor(config, accessWidener)))
                            awr.read(zin.bufferedReader())
                            return@zip
                        }
                }
            }
        
        widener = ProjectAccessWidener(config, accessWidener).takeIf { !it.isEmpty() }
    }
    
    override fun isEnabled(): Boolean {
        return widener != null
    }
    
    override fun isApplicable(entry: ZipEntry): Boolean {
        return widener?.hasClass(entry.name) ?: false
    }
    
    override fun getClassVisitor(parent: ClassVisitor): ClassVisitor {
        return AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, parent, widener!!.accessWidener)
    }
    
    override fun getSourceVisitor(serverFacade: JavaParserFacade): SourceVisitor {
        return WidenerSourceVisitor(widener!!.config)
    }
    
}