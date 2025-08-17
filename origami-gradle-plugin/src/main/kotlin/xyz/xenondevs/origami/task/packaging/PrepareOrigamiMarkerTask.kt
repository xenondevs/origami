package xyz.xenondevs.origami.task.packaging

import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

@CacheableTask
abstract class PrepareOrigamiMarkerTask @Inject constructor()  : DefaultTask() {
    
    @get:Input
    abstract val origamiVersion: Property<String>
    
    @get:Input
    abstract val pluginId: Property<String>
    
    @get:OutputFile
    abstract val jsonOutput: RegularFileProperty
    
    @TaskAction
    fun run() {
        val json = JsonObject()
        json.addProperty("pluginId", pluginId.get())
        json.addProperty("origamiVersion", origamiVersion.get())
        
        jsonOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(json.toString())
        }
    }
    
}