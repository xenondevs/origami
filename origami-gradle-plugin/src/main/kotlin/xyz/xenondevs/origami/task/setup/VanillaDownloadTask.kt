package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.origami.util.AsyncUtils
import xyz.xenondevs.origami.util.GSON
import xyz.xenondevs.origami.util.dto.VersionData
import xyz.xenondevs.origami.util.dto.VersionManifest
import xyz.xenondevs.origami.util.withLock
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject

private const val VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

internal abstract class VanillaDownloadTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
    
    @get:Internal
    abstract val lockFile: RegularFileProperty
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:Internal
    val workDir: DirectoryProperty = objects.directoryProperty()
    
    @get:Internal
    val serverMappings = workDir.map { it.file("server-mappings.txt") }
    
    @get:Internal
    val serverJar = workDir.map { it.file("server.jar") }
    
    @get:Internal
    val librariesDir = workDir.map { it.dir("libraries") }
    
    init {
        outputs.upToDateWhen { (it as VanillaDownloadTask).serverJar.get().asFile.exists() }
    }
    
    @TaskAction
    fun run() = withLock(lockFile) {
        if (serverJar.get().asFile.exists()) {
            logger.info("VanillaDownload already completed, skipping")
            return
        }
        
        logger.info("Downloading vanilla server files for Minecraft version ${minecraftVersion.get()}")
        
        val serverUrl: String
        val mappingsUrl: String
        run {
            val mcVersion = minecraftVersion.get()    
            val versionUrl = downloadJson<VersionManifest>(VERSION_MANIFEST).versions.first { it.id == mcVersion }.url
            val downloads = downloadJson<VersionData>(versionUrl).downloads
            serverUrl = downloads["server"]!!.url
            mappingsUrl = downloads["server_mappings"]!!.url
        }
        
        val pool = AsyncUtils.createPool("vanilla-downloads", threadCount = 2)
        pool.execute { downloadServerAndLibraries(serverUrl) }
        pool.execute { download(mappingsUrl, serverMappings.get().asFile) }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
    }
    
    private fun downloadServerAndLibraries(serverJarUrl: String) {
        ZipInputStream(URI(serverJarUrl).toURL().openConnection().getInputStream().buffered()).use { inp ->
            generateSequence { inp.nextEntry }
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith("META-INF/") }
                .forEach { entry ->
                    if (entry.name == "META-INF/versions/${minecraftVersion.get()}/server-${minecraftVersion.get()}.jar") {
                        serverJar.get().asFile.parentFile.mkdirs()
                        serverJar.get().asFile.outputStream().use { out -> inp.copyTo(out) }
                    } else if (entry.name.startsWith("META-INF/libraries/")) {
                        val libFile = librariesDir.get().asFile.resolve(entry.name.removePrefix("META-INF/libraries/"))
                        libFile.parentFile.mkdirs()
                        libFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                }
        }
    }
    
    private fun download(url: String, dest: File) {
        dest.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
    
    private inline fun <reified T> downloadJson(url: String): T =
        URI(url).toURL().openStream().bufferedReader().use { GSON.fromJson<T>(it, T::class.java)!! }
    
}