package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import xyz.xenondevs.origami.util.withLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal abstract class RemapTask : DefaultTask() {
    
    @get:Internal
    abstract val lockFile: RegularFileProperty
    
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
    @get:Optional
    abstract val constants: RegularFileProperty
    
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
    
    @get:Internal
    abstract val remappedJar: RegularFileProperty
    
    init {
        outputs.upToDateWhen { (it as RemapTask).remappedJar.get().asFile.exists() }
    }
    
    @TaskAction
    fun run(): Unit = withLock(lockFile) {
        if (remappedJar.get().asFile.exists()) {
            logger.info("Remap already completed, skipping")
            return
        }
        
        val mcVersion = minecraftVersion.get()
        logger.info("Remapping obfuscated vanilla server $mcVersion jar")
        val tempOut = temporaryDir.resolve("vanilla-remapped-$mcVersion.jar")
        
        val libraries = vanillaLibraries.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .toList()
        
        val args = remapperArgs.get().map { arg ->
            var newArg = arg
                .replace("{tempDir}", temporaryDir.absolutePath)
                .replace("{remapperFile}", remapper.get().asFile.absolutePath)
                .replace("{mappingsFile}", mappings.get().asFile.absolutePath)
                .replace("{paramsFile}", paramMappings.get().asFile.absolutePath)
                .replace("{output}", tempOut.absolutePath)
                .replace("{input}", vanillaServer.get().asFile.absolutePath)
                .replace("{inputClasspath}", libraries.joinToString(":") { it.absolutePath })
            if (constants.isPresent)
                newArg = newArg.replace("{constantsFile}", constants.get().asFile.absolutePath)
            
            newArg
        }
        
        val codebookLog = temporaryDir.resolve("codebook.log")
        val process = ProcessBuilder(
            javaLauncher.get().executablePath.asFile.absolutePath,
            "-Xmx2G",
            "-jar",
            codebook.get().asFile.absolutePath,
            "--force", // overwrite existing output file
            *args.toTypedArray()
        ).directory(temporaryDir).redirectError(codebookLog).redirectOutput(codebookLog).start()
        
        check(process.waitFor() == 0) { "Failed to remap, Codebook exited with code ${process.exitValue()}" }
        check(tempOut.exists()) { "Failed to remap, remapped jar not found at ${tempOut.absolutePath}" }
        
        remappedJar.get().asFile.parentFile.mkdirs()
        Files.move(tempOut.toPath(), remappedJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    
}