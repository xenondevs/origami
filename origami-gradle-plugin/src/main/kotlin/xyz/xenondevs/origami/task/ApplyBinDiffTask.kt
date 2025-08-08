package xyz.xenondevs.origami.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@CacheableTask
abstract class ApplyBinDiffTask @Inject constructor() : DefaultTask() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaServer: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val devBundleZip: RegularFileProperty
    
    @get:Input
    abstract val paperclipInternalPath: Property<String>
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>
    
    @get:OutputFile
    abstract val patchedJar: RegularFileProperty
    
    @TaskAction
    fun apply() {
        val mcVersion = minecraftVersion.get()
        val tempPaperclip = temporaryDir.resolve("paperclip-$mcVersion.jar")
        logger.lifecycle("Running Paperclip bin diff for $mcVersion")
        
        FileSystems.newFileSystem(devBundleZip.get().asFile.toPath()).use { fs ->
            val root = fs.getPath("/")
            val paperclipPath = root.resolve(paperclipInternalPath.get())
            Files.copy(paperclipPath, tempPaperclip.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        
        val paperclipTarget = temporaryDir.resolve("cache/mojang_$mcVersion.jar")
        paperclipTarget.parentFile.mkdirs()
        Files.copy(vanillaServer.get().asFile.toPath(), paperclipTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        
        val paperclipLog = temporaryDir.resolve("paperclip.log")
        
        val process = ProcessBuilder(
            javaLauncher.get().executablePath.asFile.absolutePath,
            "-Dpaperclip.patchonly=true",
            "-jar",
            tempPaperclip.absolutePath
        ).directory(tempPaperclip.parentFile).redirectOutput(paperclipLog).start()
        check(process.waitFor() == 0) { "Failed to apply bin diff, Paperclip exited with code ${process.exitValue()}" }
        
        val patched = temporaryDir.resolve("versions/$mcVersion/paper-$mcVersion.jar")
        check(patched.exists()) { "Patched jar not found at ${patched.absolutePath}" }
        
        Files.copy(patched.toPath(), patchedJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    
}