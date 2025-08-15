package xyz.xenondevs.origami.task.setup

import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.origami.service.DownloaderService
import xyz.xenondevs.origami.util.AsyncUtils
import xyz.xenondevs.origami.util.GSON
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject

private const val VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

@CacheableTask
abstract class VanillaDownloadTask @Inject constructor(private val layout: ProjectLayout) : DefaultTask() {
    
    @get:Input
    abstract val minecraftVersion: Property<String>
    
    @get:Internal
    abstract val downloader: Property<DownloaderService>
    
    @get:OutputFile
    abstract val bundleJar: RegularFileProperty
    
    @get:OutputFile
    abstract val serverMappings: RegularFileProperty
    
    @get:OutputFile
    abstract val serverJar: RegularFileProperty
    
    @get:OutputDirectory
    abstract val librariesDir: DirectoryProperty
    
    @TaskAction
    fun download() {
        val dl = downloader.get()
        val mcVersion = minecraftVersion.get()
        
        logger.lifecycle("Downloading vanilla server files for Minecraft version $mcVersion")
        
        val manifest = dl.getJsonObject(VERSION_MANIFEST)
        val versions = manifest.getAsJsonArray("versions")
        val versionJson = versions.first { it.asJsonObject.getAsJsonPrimitive("id").asString == mcVersion }.asJsonObject
        
        val versionManifest = dl.getJsonObject(versionJson.getAsJsonPrimitive("url").asString)
        val downloads = versionManifest.getAsJsonObject("downloads")
        
        val serverUrl = downloads.getAsJsonObject("server").getAsJsonPrimitive("url").asString
        val mappingsUrl = downloads.getAsJsonObject("server_mappings").getAsJsonPrimitive("url").asString
        
        val pool = AsyncUtils.createPool("vanilla-downloads", threadCount = 4)
        val bundleFuture = CompletableFuture<Unit>()
        
        pool.execute {
            dl.download(serverUrl, bundleJar.get().asFile)
            bundleFuture.complete(Unit)
        }
        pool.execute {
            dl.download(mappingsUrl, serverMappings.get().asFile)
        }
        pool.execute {
            bundleFuture.join()
            extractServerAndLibraries()
        }
        
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
    }
    
    private fun extractServerAndLibraries() {
        val zip = ZipFile(bundleJar.get().asFile)
        try {
            val mcVer = zip.getInputStream(zip.getEntry("version.json"))
                .reader().use { GSON.fromJson(it, JsonObject::class.java) }
                .get("id").asString
            
            val serverEntry = zip.getEntry("META-INF/versions/$mcVer/server-$mcVer.jar")
            zip.getInputStream(serverEntry).use { zis -> serverJar.get().asFile.outputStream().use(zis::copyTo) }
            
            val librariesDir = librariesDir.get().asFile.apply { deleteRecursively(); mkdirs() }
            val libraries = ArrayList<File>()
            zip.stream()
                .filter { !it.isDirectory && it.name.startsWith("META-INF/libraries/") && it.name.endsWith(".jar") }
                .forEach { entry ->
                    val libraryFile = librariesDir.resolve(entry.name.removePrefix("META-INF/libraries/"))
                    libraryFile.parentFile.mkdirs()
                    libraries.add(libraryFile)
                    zip.getInputStream(entry).use { zis -> libraryFile.outputStream().use(zis::copyTo) }
                }
        } finally {
            zip.close()
        }
    }
    
}