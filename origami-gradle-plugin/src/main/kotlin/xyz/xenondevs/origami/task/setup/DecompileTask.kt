package xyz.xenondevs.origami.task.setup

import io.codechicken.diffpatch.cli.PatchOperation
import io.codechicken.diffpatch.util.Input.ArchiveMultiInput
import io.codechicken.diffpatch.util.LogLevel
import io.codechicken.diffpatch.util.Output.ArchiveMultiOutput
import io.codechicken.diffpatch.util.archiver.ArchiveFormat
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
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@CacheableTask
abstract class DecompileTask @Inject constructor() : DefaultTask() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val remappedJar: RegularFileProperty
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaLibraries: DirectoryProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val decompiler: RegularFileProperty
    
    @get:Input
    abstract val decompilerArgs: ListProperty<String>
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val macheFile: RegularFileProperty
    
    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:OutputFile
    abstract val decompiledSources: RegularFileProperty
    
    @TaskAction
    fun run() {
        val mcVer = minecraftVersion.get()
        val tempOut = temporaryDir.resolve("decompiled-$mcVer.jar")
        val config = temporaryDir.resolve("$mcVer.cfg")
        config.writeText(buildString {
            vanillaLibraries.get().asFile.walkTopDown().forEach { lib ->
                if (!lib.isFile || !lib.name.endsWith(".jar")) return@forEach
                appendLine("-e=${lib.absolutePath}")
            }
        })
        
        val args = buildList {
            addAll(decompilerArgs.get())
            add("-cfg")
            add(config.absolutePath)
            add(remappedJar.get().asFile.absolutePath)
            add(tempOut.absolutePath)
        }
        val decompilerLog = temporaryDir.resolve("decompiler.log")
        logger.lifecycle("Decompiling vanilla $mcVer server")
        val start = System.currentTimeMillis()
        val process = ProcessBuilder(
            javaLauncher.get().executablePath.asFile.absolutePath,
            "-Xmx4G",
            "-jar",
            decompiler.get().asFile.absolutePath,
            "--thread-count=8",
            *args.toTypedArray()
        ).directory(temporaryDir).redirectOutput(decompilerLog).start()
        
        check(process.waitFor() == 0) { "Failed to decompile, decompiler exited with code ${process.exitValue()}. Logs: ${decompilerLog.absolutePath}" }
        check(tempOut.exists()) { "Failed to decompile, decompiled jar not found at ${tempOut.absolutePath}" }
        
        val diffpatchLog = PrintStream(temporaryDir.resolve("diffpatch.log"))
        val fixedOut = temporaryDir.resolve("decompiled-fixed-$mcVer.jar")
        
        val diffRes = PatchOperation.builder()
            .baseInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, tempOut.toPath()))
            .patchesInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, macheFile.get().asFile.toPath()))
            .patchedOutput(ArchiveMultiOutput.archive(ArchiveFormat.ZIP, fixedOut.toPath()))
            .patchesPrefix("patches")
            .logTo(diffpatchLog)
            .level(LogLevel.ALL)
            .summary(true)
            .build()
            .operate()
        diffpatchLog.close()
        
        check(diffRes.exit == 0) { "Failed to apply mache patches, diffpatch exited with code ${diffRes.exit}" }
        check(fixedOut.exists()) { "Failed to apply mache patches, fixed jar not found at ${fixedOut.absolutePath}" }
        
        Files.move(fixedOut.toPath(), decompiledSources.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Decompiled server in ${(System.currentTimeMillis() - start).milliseconds}")
    }
    
}