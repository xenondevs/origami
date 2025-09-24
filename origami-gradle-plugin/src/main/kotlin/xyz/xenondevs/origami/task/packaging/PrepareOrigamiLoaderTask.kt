package xyz.xenondevs.origami.task.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.copyTo

@CacheableTask
abstract class PrepareOrigamiLoaderTask : DefaultTask() {
    
    @get:Internal
    abstract val origamiLoaderConfig: Property<Configuration>
    
    @get:Internal
    abstract val origamiConfig: Property<Configuration>
    
    @get:Input
    abstract val librariesDirectory: Property<String>
    
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    @TaskAction
    fun run() {
        val outDir = outputDir.get().asFile
        includeOrigamiLoaderClasses(outDir)
        includeOrigamiLibs(outDir)
    }
    
    private fun includeOrigamiLoaderClasses(out: File) {
        ZipInputStream(origamiLoaderConfig.get().singleFile.inputStream().buffered()).use { inp ->
            generateSequence { inp.nextEntry }
                .filter { entry -> !entry.isDirectory }
                .forEach { entry ->
                    val dst = out.resolve(entry.name)
                    dst.parentFile.mkdirs()
                    dst.outputStream().buffered().use { out -> inp.transferTo(out) }
                }
        }
    }
    
    private fun includeOrigamiLibs(out: File) {
        val libPaths = origamiConfig.get().incoming.artifacts.artifacts.mapNotNull { copyToLibs(it, out) }
        
        out.resolve("origami-libraries").writeText(
            ("/" + librariesDirectory.get().removePrefix("/").removeSuffix("/") + "/\n")
                + libPaths.joinToString("\n")
        )
    }
    
    private fun copyToLibs(artifact: ResolvedArtifactResult, out: File): String? {
        val file = artifact.file
        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            ?: return null
        
        val path = librariesDirectory.get().removePrefix("/").removeSuffix("/") +
            "/" + id.group.replace('.', '/') +
            "/" + id.module +
            "/" + id.version +
            "/" + file.name
        
        val dst = out.resolve(path)
        dst.parentFile.mkdirs()
        file.copyTo(dst, true)
        
        return "/$path"
    }
    
}