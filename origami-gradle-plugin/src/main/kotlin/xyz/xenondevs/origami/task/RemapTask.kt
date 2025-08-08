package xyz.xenondevs.origami.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@CacheableTask
abstract class RemapTask @Inject constructor() : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaServer: RegularFileProperty
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaLibraries: DirectoryProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val paramMappings: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val codebook: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val remapper: RegularFileProperty
    
    @get:Input
    abstract val remapperArgs: ListProperty<String>
    
    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:OutputFile
    abstract val remappedJar: RegularFileProperty
    
    @TaskAction
    fun run() {
        val mcVersion = minecraftVersion.get()
        logger.lifecycle("Remapping obfuscated vanilla server $mcVersion jar")
        val tempOut = temporaryDir.resolve("vanilla-remapped-$mcVersion.jar")
        
        val libraries = vanillaLibraries.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .toList()
        
        val args = remapperArgs.get().map { arg ->
            arg
                .replace("{tempDir}", temporaryDir.absolutePath)
                .replace("{remapperFile}", remapper.get().asFile.absolutePath)
                .replace("{mappingsFile}", mappings.get().asFile.absolutePath)
                .replace("{paramsFile}", paramMappings.get().asFile.absolutePath)
                .replace("{output}", tempOut.absolutePath)
                .replace("{input}", vanillaServer.get().asFile.absolutePath)
                .replace("{inputClasspath}", libraries.joinToString(":") { it.absolutePath })
            //                .replace("{constantsFile}", "")
        }
        
        val codebookLog = temporaryDir.resolve("codebook.log")
        val process = ProcessBuilder(
            javaLauncher.get().executablePath.asFile.absolutePath,
            "-Xmx2G",
            "-jar",
            codebook.get().asFile.absolutePath,
            *args.toTypedArray()
        ).directory(temporaryDir).redirectOutput(codebookLog).start()
        
        check(process.waitFor() == 0) { "Failed to remap, Codebook exited with code ${process.exitValue()}" }
        check(tempOut.exists()) { "Failed to remap, remapped jar not found at ${tempOut.absolutePath}" }
        
        Files.move(tempOut.toPath(), remappedJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    
}