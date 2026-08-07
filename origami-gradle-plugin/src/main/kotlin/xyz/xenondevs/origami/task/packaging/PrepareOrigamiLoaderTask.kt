package xyz.xenondevs.origami.task.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipInputStream

@CacheableTask
abstract class PrepareOrigamiLoaderTask : DefaultTask() {
    
    @get:Classpath
    abstract val origamiLoaderConfig: ConfigurableFileCollection
    
    @get:Classpath
    abstract val origamiConfig: ConfigurableFileCollection
    
    @get:Classpath
    abstract val injectablesConfig: ConfigurableFileCollection
    
    @get:Input
    abstract val libraryPaths: MapProperty<String, String>
        
    @get:Input
    abstract val librariesDirectory: Property<String>
    
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    @TaskAction
    fun run() {
        val outDir = outputDir.get().asFile
        includeOrigamiLoaderClasses(outDir)
        includeLibs(outDir, "origami-libraries", origamiConfig.files)
        includeLibs(outDir, "server-libraries", injectablesConfig.files)
    }
    
    private fun includeOrigamiLoaderClasses(out: File) {
        ZipInputStream(origamiLoaderConfig.singleFile.inputStream().buffered()).use { inp ->
            generateSequence { inp.nextEntry }
                .filter { entry -> !entry.isDirectory }
                .forEach { entry ->
                    val dst = out.resolve(entry.name)
                    dst.parentFile.mkdirs()
                    dst.outputStream().buffered().use { out -> inp.transferTo(out) }
                }
        }
    }
    
    private fun includeLibs(out: File, listName: String, files: Set<File>) {
        val libPaths = files.map { file ->
            val inZipPath = libraryPaths.get()[file.absolutePath]
            checkNotNull(inZipPath) { "Broken mapping" }
            val dst = out.resolve(inZipPath)
            dst.parentFile.mkdirs()
            file.copyTo(dst, true)
            "/$inZipPath"
        }
        
        out.resolve(listName).writeText(
            ("/" + librariesDirectory.get().removePrefix("/").removeSuffix("/") + "/\n")
                + libPaths.joinToString("\n")
        )
    }
    
}