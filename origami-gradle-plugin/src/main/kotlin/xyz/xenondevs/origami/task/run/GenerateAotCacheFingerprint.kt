package xyz.xenondevs.origami.task.run

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

@DisableCachingByDefault(because = "The fingerprint intentionally includes absolute AOT classpath locations")
internal abstract class GenerateAotCacheFingerprint : DefaultTask() {
    
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patchFingerprint: DirectoryProperty
    
    @get:Classpath
    abstract val plugins: ConfigurableFileCollection
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javaReleaseFile: RegularFileProperty
    
    @get:Input
    abstract val jvmArgs: ListProperty<String>
    
    @get:Input
    abstract val args: ListProperty<String>
    
    @get:Input
    abstract val mainClass: Property<String>
    
    @get:Input
    abstract val workingDirectory: Property<String>
    
    @get:OutputFile
    abstract val outputFile: RegularFileProperty
    
    @TaskAction
    fun generate() {
        val fingerprint = calculateAotCacheFingerprint(
            classpath.files.toList(),
            patchFingerprint.get().asFile,
            plugins.files.toList(),
            javaReleaseFile.get().asFile,
            jvmArgs.get(),
            args.get(),
            mainClass.get(),
            workingDirectory.get(),
        )
        outputFile.get().asFile
            .apply { parentFile.mkdirs() }
            .writeText(fingerprint)
    }
    
}

private fun calculateAotCacheFingerprint(
    classpath: List<File>,
    patchFingerprint: File,
    plugins: List<File>,
    javaReleaseFile: File,
    jvmArgs: List<String>,
    args: List<String>,
    mainClass: String,
    workingDirectory: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    
    fun update(value: Int) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }
    
    fun update(value: Long) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }
    
    fun update(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(bytes.size)
        digest.update(bytes)
    }
    
    fun updateValue(name: String, value: String) {
        update(name)
        update(value)
    }
    
    fun updateValues(name: String, values: List<String>) {
        update(name)
        update(values.size)
        values.forEach(::update)
    }
    
    fun updateMetadata(file: File) {
        require(file.exists()) { "AOT cache fingerprint input does not exist: ${file.absolutePath}" }
        when {
            file.isDirectory -> update("directory")
            file.isFile -> {
                update("file")
                update(file.length())
                update(file.lastModified())
            }
            
            else -> error("Unsupported AOT cache fingerprint input: ${file.absolutePath}")
        }
    }
    
    fun updateFiles(name: String, files: List<File>, includeRootPaths: Boolean = true) {
        update(name)
        update(files.size)
        for (root in files) {
            update(if (includeRootPaths) root.normalizedAbsolutePath() else "<root>")
            updateMetadata(root)
            if (root.isDirectory) {
                val entries = root.walkTopDown()
                    .drop(1)
                    .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()
                update(entries.size)
                for (entry in entries) {
                    update(entry.relativeTo(root).invariantSeparatorsPath)
                    updateMetadata(entry)
                }
            }
        }
    }
    
    updateValue("formatVersion", "1")
    updateFiles("classpath", classpath)
    updateFiles("patchFingerprint", listOf(patchFingerprint), includeRootPaths = false)
    updateFiles("plugins", plugins)
    updateFiles("javaRelease", listOf(javaReleaseFile))
    updateValues("jvmArgs", jvmArgs)
    updateValues("args", args)
    updateValue("mainClass", mainClass)
    updateValue("workingDirectory", workingDirectory)
    return HexFormat.of().formatHex(digest.digest())
}

private fun File.normalizedAbsolutePath(): String =
    toPath().toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
