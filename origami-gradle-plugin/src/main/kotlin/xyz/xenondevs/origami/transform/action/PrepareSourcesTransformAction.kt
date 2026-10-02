package xyz.xenondevs.origami.transform.action

import io.codechicken.diffpatch.cli.PatchOperation
import io.codechicken.diffpatch.util.Input.ArchiveMultiInput
import io.codechicken.diffpatch.util.LogLevel
import io.codechicken.diffpatch.util.Output
import io.codechicken.diffpatch.util.archiver.ArchiveFormat
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.xenondevs.origami.transform.SharedTransformCache
import xyz.xenondevs.origami.value.DevBundle
import java.io.File
import java.io.PrintStream
import java.nio.file.FileSystems
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.copyTo
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.time.Duration.Companion.milliseconds

/**
 * Creates readable Paper server sources when they are requested.
 *
 * Starting with the files produced by [PrepareServerTransform], it remaps the vanilla server with Codebook,
 * decompiles it, and applies the Mache and Paper source patches. This expensive step is skipped during normal builds
 * unless sources are explicitly resolved.
 */
@CacheableTransform
internal abstract class PrepareSourcesTransformAction : TransformAction<PrepareSourcesTransformAction.Parameters> {
    
    internal companion object {
        const val PATCHED_SOURCES_FILE = "server-patched-sources.jar"
        const val LIBRARIES_DIR = "libraries"
        const val NEW_SOURCES_DIR = "new-sources"
        const val PATCHED_SOURCES_DIR = "patched-sources"
    }
    
    interface Parameters : TransformParameters {
        @get:Input
        val devBundleId: Property<String>
        
        @get:Input
        val javaExecutable: Property<String>
        
        @get:Internal
        val sharedCache: DirectoryProperty
        
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val macheFile: RegularFileProperty
        
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val codebook: RegularFileProperty
        
        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        val paramMappings: RegularFileProperty
        
        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        val constants: RegularFileProperty
        
        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        val remapper: RegularFileProperty
        
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val decompiler: RegularFileProperty
        
        @get:Input
        val remapperArgs: ListProperty<String>
        
        @get:Input
        val decompilerArgs: ListProperty<String>
    }
    
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    
    override fun transform(outputs: TransformOutputs) {
        val logger = Logging.getLogger(PrepareSourcesTransformAction::class.java)
        val base = inputArtifact.get().asFile
        val state = outputs.dir("sources-base")
        val bundle = base.resolve(PrepareServerTransform.DEV_BUNDLE_FILE)
        val cache = SharedTransformCache(
            parameters.sharedCache.get().asFile,
            parameters.devBundleId.get().substringAfterLast(':'),
            "sources-base",
        )
        
        val reused = cache.getOrCreate(state) { cacheState ->
            prepare(base, bundle, cacheState, logger)
        }
        if (reused) {
            logger.lifecycle("[Origami] Reusing shared sources base for ${parameters.devBundleId.get()}")
        }
    }
    
    private fun prepare(base: File, bundle: File, state: File, logger: Logger) {
        val work = state.resolve("_work").apply { mkdirs() }
        var completed = false
        
        try {
            val config = DevBundle.read(bundle)
            val vanilla = base.resolve(PrepareServerTransform.VANILLA_DIR)
            val libraries = vanilla.resolve(PrepareServerTransform.VANILLA_LIBRARIES_DIR)
            
            logger.lifecycle("[Origami] Remapping ${parameters.devBundleId.get()} with Codebook")
            val remapped = runCodebook(vanilla.resolve("server.jar"), libraries, work)
            
            logger.lifecycle("[Origami] Decompiling ${parameters.devBundleId.get()}")
            val decompiled = runDecompiler(config, remapped, libraries, work)
            
            logger.lifecycle("[Origami] Applying Paper source patches for ${parameters.devBundleId.get()}")
            applyPaperPatches(
                config,
                bundle,
                decompiled,
                work,
                state.resolve(PATCHED_SOURCES_FILE),
                state.resolve(NEW_SOURCES_DIR),
                state.resolve(PATCHED_SOURCES_DIR),
            )
            
            libraries.copyRecursively(state.resolve(LIBRARIES_DIR), overwrite = true)
            logger.lifecycle("[Origami] Prepared sources for ${parameters.devBundleId.get()}")
            completed = true
        } finally {
            if (completed) {
                work.deleteRecursively()
            }
        }
    }
    
    private fun runCodebook(vanillaServer: File, libraries: File, work: File): File {
        val output = work.resolve("server-remapped.jar")
        val libraryJars = libraries.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .toList()
        
        val args = parameters.remapperArgs.get().map { argument ->
            var resolved = argument
                .replace("{tempDir}", work.absolutePath)
                .replace("{output}", output.absolutePath)
                .replace("{input}", vanillaServer.absolutePath)
                .replace("{inputClasspath}", libraryJars.joinToString(":") { it.absolutePath })
            
            if (resolved.contains("{mappingsFile}")) {
                val mappings = vanillaServer.parentFile.resolve("server-mappings.txt")
                check(mappings.isFile) {
                    "Codebook arguments reference {mappingsFile}, but no server mappings were downloaded"
                }
                resolved = resolved.replace("{mappingsFile}", mappings.absolutePath)
            }
            if (resolved.contains("{remapperFile}")) {
                check(parameters.remapper.isPresent) {
                    "Codebook arguments reference {remapperFile}, but no remapper is configured"
                }
                resolved = resolved.replace("{remapperFile}", parameters.remapper.get().asFile.absolutePath)
            }
            if (resolved.contains("{paramsFile}")) {
                check(parameters.paramMappings.isPresent) {
                    "Codebook arguments reference {paramsFile}, but no parameter mappings are configured"
                }
                resolved = resolved.replace("{paramsFile}", parameters.paramMappings.get().asFile.absolutePath)
            }
            if (parameters.constants.isPresent) {
                resolved = resolved.replace("{constantsFile}", parameters.constants.get().asFile.absolutePath)
            }
            
            resolved
        }
        
        val log = work.resolve("codebook.log")
        val process = ProcessBuilder(
            parameters.javaExecutable.get(),
            "-Xmx2G",
            "-jar",
            parameters.codebook.get().asFile.absolutePath,
            "--force",
            *args.toTypedArray()
        ).directory(work).redirectError(log).redirectOutput(log).start()
        
        check(process.waitFor() == 0) {
            "Codebook exited with code ${process.exitValue()}. Logs: ${log.absolutePath}"
        }
        check(output.isFile) { "Codebook did not produce ${output.absolutePath}" }
        return output
    }
    
    private fun runDecompiler(config: DevBundle, remappedJar: File, libraries: File, work: File): File {
        val rawOutput = work.resolve("server-decompiled-raw.jar")
        val configFile = work.resolve("${config.minecraftVersion}.cfg")
        configFile.writeText(buildString {
            libraries.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jar") }
                .forEach { appendLine("-e=${it.absolutePath}") }
        })
        
        val args = buildList {
            addAll(parameters.decompilerArgs.get())
            add("-cfg")
            add(configFile.absolutePath)
            add(remappedJar.absolutePath)
            add(rawOutput.absolutePath)
        }
        
        val log = work.resolve("decompiler.log")
        val start = System.currentTimeMillis()
        val process = ProcessBuilder(
            parameters.javaExecutable.get(),
            "-Xmx4G",
            "-jar",
            parameters.decompiler.get().asFile.absolutePath,
            "--thread-count=8",
            *args.toTypedArray()
        ).directory(work).redirectOutput(log).start()
        
        check(process.waitFor() == 0) {
            "Decompiler exited with code ${process.exitValue()}. Logs: ${log.absolutePath}"
        }
        check(rawOutput.isFile) { "Decompiler did not produce ${rawOutput.absolutePath}" }
        
        val logger = Logging.getLogger(PrepareSourcesTransformAction::class.java)
        logger.lifecycle("[Origami] Applying Mache source patches for ${parameters.devBundleId.get()}")
        val fixedOutput = work.resolve("server-decompiled.jar")
        val diffpatchLog = PrintStream(work.resolve("mache-patches.log"))
        val result = try {
            PatchOperation.builder()
                .baseInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, rawOutput.toPath()))
                .patchesInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, parameters.macheFile.get().asFile.toPath()))
                .patchedOutput(Output.ArchiveMultiOutput.archive(ArchiveFormat.ZIP, fixedOutput.toPath()))
                .patchesPrefix("patches")
                .logTo(diffpatchLog)
                .level(LogLevel.ALL)
                .summary(true)
                .build()
                .operate()
        } finally {
            diffpatchLog.close()
        }
        
        check(result.exit == 0) { "Applying Mache patches failed with exit code ${result.exit}" }
        check(fixedOutput.isFile) { "Mache patches did not produce ${fixedOutput.absolutePath}" }
        logger.lifecycle(
            "[Origami] Decompiled and applied Mache patches in ${(System.currentTimeMillis() - start).milliseconds}"
        )
        return fixedOutput
    }
    
    private fun applyPaperPatches(
        config: DevBundle,
        bundle: File,
        vanillaSources: File,
        work: File,
        patchedJar: File,
        newSources: File,
        patchedSources: File,
    ) {
        val patches = work.resolve("paper-patches").apply { mkdirs() }
        newSources.mkdirs()
        patchedSources.mkdirs()
        
        FileSystems.newFileSystem(bundle.toPath()).use { fs ->
            val patchesRoot = fs.getPath(config.patchDir)
            patchesRoot.walk().filter { it.isRegularFile() }.forEach { path ->
                val relative = path.relativeTo(patchesRoot)
                val target = when (relative.extension) {
                    "patch" -> patches.resolve(relative.toString())
                    "java" -> newSources.resolve(relative.toString())
                    else -> return@forEach
                }
                target.parentFile.mkdirs()
                path.copyTo(target.toPath(), overwrite = true)
            }
        }
        
        val diffpatchLog = PrintStream(work.resolve("paper-patches.log"))
        val result = try {
            PatchOperation.builder()
                .baseInput(ArchiveMultiInput.archive(ArchiveFormat.ZIP, vanillaSources.toPath()))
                .patchesInput(io.codechicken.diffpatch.util.Input.FolderMultiInput.folder(patches.toPath()))
                .patchedOutput(Output.FolderMultiOutput.folder(patchedSources.toPath()))
                .logTo(diffpatchLog)
                .level(LogLevel.ALL)
                .summary(true)
                .build()
                .operate()
        } finally {
            diffpatchLog.close()
        }
        
        check(result.exit == 0) { "Applying Paper patches failed with exit code ${result.exit}" }
        
        patchedJar.parentFile.mkdirs()
        JarOutputStream(patchedJar.outputStream().buffered()).use { output ->
            sequenceOf(newSources, patchedSources).forEach { root ->
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    val entry = ZipEntry(file.relativeTo(root).path.replace('\\', '/'))
                    output.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }
}