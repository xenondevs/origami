package xyz.xenondevs.origami.transform.action

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.xenondevs.origami.transform.SharedTransformCache
import xyz.xenondevs.origami.util.GSON
import xyz.xenondevs.origami.util.dto.VersionData
import xyz.xenondevs.origami.util.dto.VersionManifest
import xyz.xenondevs.origami.value.DevBundle
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.readLines

private const val VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

/**
 * Performs the first setup step shared by the binary and source paths.
 *
 * It downloads the official Minecraft server and mappings, extracts its libraries, and applies the Paperclip patches
 * from the dev bundle. The resulting patched server and supporting files are stored in the shared cache.
 */
@CacheableTransform
internal abstract class PrepareServerTransform : TransformAction<PrepareServerTransform.Parameters> {
    
    internal companion object {
        const val DEV_BUNDLE_FILE = "dev-bundle.zip"
        const val PATCHED_SERVER_FILE = "server-patched.jar"
        const val VANILLA_DIR = "vanilla"
        const val VANILLA_LIBRARIES_DIR = "libraries"
    }
    
    interface Parameters : TransformParameters {
        @get:Input
        val devBundleId: Property<String>
        
        @get:Input
        val javaExecutable: Property<String>
        
        @get:Internal
        val sharedCache: DirectoryProperty
    }
    
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    
    override fun transform(outputs: TransformOutputs) {
        val logger = Logging.getLogger(PrepareServerTransform::class.java)
        val bundle = inputArtifact.get().asFile
        val state = outputs.dir("server-base")
        val cache = SharedTransformCache(
            parameters.sharedCache.get().asFile,
            parameters.devBundleId.get().substringAfterLast(':'),
            "server-base",
        )
        
        val reused = cache.getOrCreate(state) { cacheState ->
            prepare(bundle, cacheState, logger)
        }
        if (reused) {
            logger.lifecycle("[Origami] Reusing shared server base for ${parameters.devBundleId.get()}")
        }
    }
    
    private fun prepare(bundle: File, state: File, logger: Logger) {
        val work = state.resolve("_work").apply { mkdirs() }
        var completed = false
        
        try {
            bundle.copyTo(state.resolve(DEV_BUNDLE_FILE), overwrite = true)
            val config = DevBundle.read(bundle)
            val vanillaDir = state.resolve(VANILLA_DIR)
            val vanillaServer = vanillaDir.resolve("server.jar")
            val libraries = vanillaDir.resolve(VANILLA_LIBRARIES_DIR)
            val mappings = vanillaDir.resolve("server-mappings.txt")
            
            logger.lifecycle("[Origami] Preparing ${parameters.devBundleId.get()} (Minecraft ${config.minecraftVersion})")
            val versionManifest = downloadJson<VersionManifest>(VERSION_MANIFEST)
            val versionUrl = versionManifest.versions.single { it.id == config.minecraftVersion }.url
            val downloads = downloadJson<VersionData>(versionUrl).downloads
            val serverDownload = checkNotNull(downloads["server"]) { "No server download exists for Minecraft ${config.minecraftVersion}" }
            
            val pool = Executors.newFixedThreadPool(2)
            pool.execute {
                extractServer(serverDownload.url, config.minecraftVersion, vanillaServer, libraries)
            }
            downloads["server_mappings"]?.let { download ->
                pool.execute { download(download.url, mappings) }
            }
            pool.shutdown()
            check(pool.awaitTermination(10, TimeUnit.MINUTES)) { "Timed out downloading vanilla server files" }
            
            logger.lifecycle("[Origami] Applying Paperclip patches for ${parameters.devBundleId.get()}")
            applyPaperclip(bundle, config, vanillaServer, work, state.resolve(PATCHED_SERVER_FILE))
            logger.lifecycle("[Origami] Prepared ${parameters.devBundleId.get()}")
            completed = true
        } finally {
            if (completed) {
                work.deleteRecursively()
            }
        }
    }
    
    private fun applyPaperclip(
        bundle: File,
        config: DevBundle,
        vanillaServer: File,
        work: File,
        output: File,
    ) {
        val paperclip = work.resolve("paperclip-${config.minecraftVersion}.jar")
        FileSystems.newFileSystem(bundle.toPath()).use { fs ->
            fs.getPath(config.mojangMappedPaperclipFile).copyTo(paperclip.toPath(), overwrite = true)
        }
        
        val paperclipTarget = work.resolve("cache/mojang_${config.minecraftVersion}.jar")
        paperclipTarget.parentFile.mkdirs()
        vanillaServer.copyTo(paperclipTarget, overwrite = true)
        
        val log = work.resolve("paperclip.log")
        val process = ProcessBuilder(
            parameters.javaExecutable.get(),
            "-Dpaperclip.patchonly=true",
            "-jar",
            paperclip.absolutePath
        ).directory(work).redirectOutput(log).start()
        
        check(process.waitFor() == 0) { "Failed to apply Paperclip bin diff, exit code ${process.exitValue()}. Logs: ${log.absolutePath}" }
        
        val jarPath = FileSystems.newFileSystem(paperclip.toPath()).use { fs ->
            val versionsList = fs.getPath("/META-INF/versions.list")
            if (!versionsList.exists()) {
                null
            } else {
                versionsList.readLines()
                    .map { it.split('\t') }
                    .firstOrNull { it.size >= 3 && it[1] == config.minecraftVersion }
                    ?.get(2)
            }
        }
        
        check(!jarPath.isNullOrEmpty()) { "Failed to find the ${config.minecraftVersion} server path in Paperclip" }
        
        val patched = work.resolve("versions/$jarPath")
        check(patched.isFile) { "Patched server was not produced at ${patched.absolutePath}" }
        output.parentFile.mkdirs()
        patched.copyTo(output, overwrite = true)
    }
    
    private fun extractServer(
        serverJarUrl: String,
        minecraftVersion: String,
        serverJar: File,
        librariesDir: File,
    ) {
        ZipInputStream(URI(serverJarUrl).toURL().openConnection().getInputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith("META-INF/") }
                .forEach { entry ->
                    when {
                        entry.name == "META-INF/versions/$minecraftVersion/server-$minecraftVersion.jar" -> {
                            serverJar.parentFile.mkdirs()
                            serverJar.outputStream().use(input::copyTo)
                        }
                        
                        entry.name.startsWith("META-INF/libraries/") -> {
                            val file = librariesDir.resolve(entry.name.removePrefix("META-INF/libraries/"))
                            file.parentFile.mkdirs()
                            file.outputStream().use(input::copyTo)
                        }
                    }
                }
        }
        
        check(serverJar.isFile) { "The downloaded Minecraft server did not contain the $minecraftVersion server JAR" }
    }
}

private inline fun <reified T> downloadJson(url: String): T =
    URI(url).toURL().openStream().bufferedReader().use { GSON.fromJson(it, T::class.java) }

private fun download(url: String, destination: File) {
    destination.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    }
}