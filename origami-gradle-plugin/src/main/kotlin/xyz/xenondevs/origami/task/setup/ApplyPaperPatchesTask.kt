package xyz.xenondevs.origami.task.setup

import io.codechicken.diffpatch.cli.PatchOperation
import io.codechicken.diffpatch.util.Input.ArchiveMultiInput
import io.codechicken.diffpatch.util.Input.FolderMultiInput
import io.codechicken.diffpatch.util.LogLevel
import io.codechicken.diffpatch.util.Output
import io.codechicken.diffpatch.util.archiver.ArchiveFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.origami.util.withLock
import java.io.File
import java.io.PrintStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

internal abstract class ApplyPaperPatchesTask : DefaultTask() {
    
    @get:Internal
    abstract val lockFile: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val devBundleZip: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaSources: RegularFileProperty
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:Input
    abstract val patchesRootName: Property<String>
    
    @get:Internal
    abstract val patchedJar: RegularFileProperty
    
    @get:Internal
    abstract val newSources: DirectoryProperty
    
    @get:Internal
    abstract val patchedSources: DirectoryProperty
    
    init {
        outputs.upToDateWhen { (it as ApplyPaperPatchesTask).patchedJar.get().asFile.exists() }
    }
    
    @TaskAction
    fun run(): Unit = withLock(lockFile) {
        if (patchedJar.get().asFile.exists()) {
            logger.info("ApplyPaperPatches already completed, skipping")
            return
        }
        
        logger.info("Applying Paper patches for ${minecraftVersion.get()} server")
        val patches = temporaryDir.resolve("patches").apply { deleteRecursively(); mkdirs() }
        val newSources = this.newSources.get().asFile.apply { deleteRecursively(); mkdirs() }
        val patchedSources = this.patchedSources.get().asFile.apply { deleteRecursively(); mkdirs() }
        
        FileSystems.newFileSystem(devBundleZip.get().asFile.toPath()).use { fs ->
            val patchesRoot = fs.getPath(patchesRootName.get())
            
            Files.walk(patchesRoot).forEach { path ->
                val rel = path.relativeTo(patchesRoot)
                if (Files.isDirectory(path)) {
                    return@forEach
                }
                val targetFile = when (rel.extension) {
                    "patch" -> patches.resolve(rel.toString())
                    "java" -> newSources.resolve(rel.toString())
                    else -> return@forEach // Ignore other files
                }
                if (!targetFile.parentFile.exists())
                    targetFile.parentFile.mkdirs()
                Files.copy(path, targetFile.toPath())
            }
        }
        
        val diffpatchLog = PrintStream(temporaryDir.resolve("diffpatch.log"))
        val diffRes = PatchOperation.builder()
            .baseInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, vanillaSources.get().asFile.toPath()))
            .patchesInput(FolderMultiInput.folder(patches.toPath()))
            .patchedOutput(Output.FolderMultiOutput.folder(patchedSources.toPath()))
            .logTo(diffpatchLog)
            .level(LogLevel.ALL)
            .summary(true)
            .build()
            .operate()
        diffpatchLog.close()
        
        check(diffRes.exit == 0) { "Failed to apply paper patches, diffpatch exited with code ${diffRes.exit}" }
        val tempSources = temporaryDir.resolve("temp_patched.jar")
        
        JarOutputStream(tempSources.outputStream().buffered()).use { jos ->
            newSources.walkTopDown().asSequence().filter(File::isFile).forEach { f ->
                jos.putNextEntry(ZipEntry(f.relativeTo(newSources).toString().replace('\\', '/')))
                f.inputStream().use { it.copyTo(jos) }
            }
            patchedSources.walkTopDown().asSequence().filter(File::isFile).forEach { f ->
                jos.putNextEntry(ZipEntry(f.relativeTo(patchedSources).toString().replace('\\', '/')))
                f.inputStream().use { it.copyTo(jos) }
            }
        }
        
        tempSources.copyTo(patchedJar.get().asFile.apply { parentFile.mkdirs() }, true)
    }
}