package xyz.xenondevs.origami.task

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.inject.Inject

@CacheableTask
abstract class PrepareOrigamiLoaderTask @Inject constructor()  : DefaultTask() {
    
    @get:Input
    abstract val origamiLoaderConfig: Property<Configuration>
    
    @get:Input
    abstract val origamiConfig: Property<Configuration>
    
    @get:Input
    abstract val version: Property<String>
    
    @get:Input
    abstract val libsFolder: Property<String>
    
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        if(!out.exists()) out.mkdirs()
        JarFile(origamiLoaderConfig.get().singleFile).use { jar ->
            jar.entries().asSequence()
                .filterNot { it.isDirectory || it.name.equals("META-INF/MANIFEST.MF") }
                .forEach { entry ->
                    val outputFile = out.resolve(entry.name)
                    if (!outputFile.parentFile.exists()) outputFile.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
        }
        
        val libsList = StringBuilder()
        val libsDir = out.resolve(libsFolder.get())
        if (!libsDir.exists()) libsDir.mkdirs()
        origamiConfig.get().resolvedConfiguration.resolvedArtifacts.forEach { afs ->
            val coordinates = afs.moduleVersion.id
            val folder = coordinates.group.replace('.', '/') + "/" + coordinates.name + "/" + coordinates.version
            val jar = coordinates.name + "-" + coordinates.version + ".jar"
            val outputFile = libsDir.resolve(folder).resolve(jar)
            
            if (!outputFile.parentFile.exists()) outputFile.parentFile.mkdirs()
            Files.copy(afs.file.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            libsList.append("/", libsFolder.get(), "/", folder, '/', jar, '\n')
        }
        out.resolve("origami-libraries").writeText(libsList.toString())
    }
    
}