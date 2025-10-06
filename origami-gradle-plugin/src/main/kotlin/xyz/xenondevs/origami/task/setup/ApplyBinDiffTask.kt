package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import xyz.xenondevs.origami.util.withLock
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal abstract class ApplyBinDiffTask : DefaultTask() {
    
    @get:Internal
    abstract val lockFile: RegularFileProperty
    
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
    
    @get:Internal
    abstract val patchedJar: RegularFileProperty
    
    init {
        outputs.upToDateWhen { (it as ApplyBinDiffTask).patchedJar.get().asFile.exists() }
    }
    
    @TaskAction
    fun apply(): Unit = withLock(lockFile) {
        if (patchedJar.get().asFile.exists()) {
            logger.info("ApplyBinDiff already completed, skipping")
            return
        }
        
        val mcVersion = minecraftVersion.get()
        val tempPaperclip = temporaryDir.resolve("paperclip-$mcVersion.jar")
        logger.info("Running Paperclip bin diff for $mcVersion")
        
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

        val jarPath = FileSystems.newFileSystem(tempPaperclip.toPath()).use { fs ->
            val versionsListPath = fs.getPath("/META-INF/versions.list")
            if (Files.exists(versionsListPath)) {
                val lines = Files.readAllLines(versionsListPath)
                lines.find { line ->
                    val parts = line.split("\t")
                    parts.size >= 3 && parts[1] == mcVersion
                }?.split("\t")?.get(2) ?: ""
            } else {
                ""
            }
        }

        check(jarPath.isNotEmpty()) { "Failed to find jar for $mcVersion in Paperclip jar" }

        val patched = temporaryDir.resolve("versions/$jarPath")
        check(patched.exists()) { "Patched jar not found at ${patched.absolutePath}" }
        
        patched.copyTo(patchedJar.get().asFile.apply { parentFile.mkdirs() }, true)
    }
    
}