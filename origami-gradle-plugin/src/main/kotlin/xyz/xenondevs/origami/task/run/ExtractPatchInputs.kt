package xyz.xenondevs.origami.task.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.commons.gson.getAllStrings
import xyz.xenondevs.commons.gson.getArrayOrNull
import xyz.xenondevs.commons.gson.getStringOrNull
import java.util.zip.ZipInputStream

internal abstract class ExtractPatchInputs : DefaultTask() {
    
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val plugins: ConfigurableFileCollection
    
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.mkdirs()
        for (plugin in plugins.files) {
            val mixinClasses = HashSet<String>()
            
            // 1st pass: discover .mixins.json(s) and .aw(s)
            ZipInputStream(plugin.inputStream().buffered()).use { zin ->
                generateSequence { zin.nextEntry }
                    .filter { !it.isDirectory }
                    .filter { it.name.endsWith(".mixins.json") || it.name.endsWith(".aw") || it.name.endsWith(".accesswidener") }
                    .forEach { entry ->
                        if (entry.name.endsWith(".mixins.json")) {
                            val bin = zin.readAllBytes()
                            
                            val jsonObj = (JsonParser.parseString(bin.decodeToString()) as? JsonObject)
                            if (jsonObj != null) {
                                val pkg = jsonObj.getStringOrNull("package")?.replace('.', '/') ?: ""
                                jsonObj.getArrayOrNull("mixins")?.getAllStrings()
                                    ?.forEach { mixinClasses += pkg + "/" + it.replace('.', '/') + ".class" }
                            }
                            
                            out.resolve(entry.name)
                                .apply { parentFile.mkdirs() }
                                .writeBytes(bin)
                        } else {
                            out.resolve(entry.name)
                                .apply { parentFile.mkdirs() }
                                .outputStream()
                                .use { out -> zin.transferTo(out) }
                        }
                    }
            }
            
            // 2nd pass: copy mixin classes
            val innerMixinPrefixes = mixinClasses.map { it.removeSuffix(".class") + '$' }
            ZipInputStream(plugin.inputStream().buffered()).use { zin ->
                generateSequence { zin.nextEntry }
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        entry.name in mixinClasses
                            || entry.name.endsWith(".class") && innerMixinPrefixes.any(entry.name::startsWith)
                    }
                    .forEach { entry ->
                        out.resolve(entry.name)
                            .apply { parentFile.mkdirs() }
                            .outputStream()
                            .use { out -> zin.transferTo(out) }
                    }
            }
        }
    }
    
}