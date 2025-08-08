package xyz.xenondevs.origami.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.net.URI
import kotlin.random.Random
import kotlin.random.nextUInt

abstract class DownloaderService : BuildService<DownloaderService.Parameters> {
    
    interface Parameters : BuildServiceParameters
    
    fun download(url: String, dest: File) {
        dest.parentFile.mkdirs()
        
        val tmpDir = File(dest.parentFile, "origami_download_-${Random.nextInt()}")
        tmpDir.mkdirs()
        val tmpFile = File(tmpDir, dest.name)
        
        try {
            URI(url).toURL().openStream().use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            tmpFile.copyTo(dest, overwrite = true)
        } finally {
            tmpFile.delete()
            tmpDir.delete()
        }
    }
    
    fun getJsonObject(url: String): JsonObject {
        val json = URI(url).toURL().openStream().bufferedReader().use(JsonParser::parseReader)
        return json as JsonObject
    }
}
