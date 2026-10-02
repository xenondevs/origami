package xyz.xenondevs.origami.transform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.copyTo
import kotlin.io.path.moveTo

private val LOCAL_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

/**
 * Reuses expensive setup results across unrelated Gradle projects.
 *
 * Each dev-bundle version has separate entries for the patched server and patched sources. A file lock ensures that
 * only one build creates an entry, after which its files are copied into the output directory Gradle assigned to the
 * current transform.
 */
internal class SharedTransformCache(
    root: File,
    devBundleVersion: String,
    private val outputName: String,
) {
    
    private val versionDirectory = root.resolve(devBundleVersion.toCachePathSegment())
    private val entry = versionDirectory.resolve(outputName)
    private val lockFile = versionDirectory.resolve("$outputName.lock")
    
    /**
     * Gets or creates this cache entry and copies it into [output] and
     * returns whether an existing cache entry was used.
     */
    fun getOrCreate(output: File, producer: (File) -> Unit): Boolean {
        val localLock = LOCAL_LOCKS.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
        return localLock.withLock {
            lockFile.parentFile.mkdirs()
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    if (entry.isDirectory) {
                        copy(entry, output)
                        return@withLock true
                    }
                    
                    val staging = versionDirectory.resolve("$outputName.tmp")
                    if (staging.exists())
                        staging.deleteRecursively()
                    check(staging.mkdirs()) { "Could not create shared cache staging directory ${staging.absolutePath}" }
                    
                    try {
                        producer(staging)
                        try {
                            staging.toPath().moveTo(entry.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            staging.toPath().moveTo(entry.toPath())
                        }
                    } catch (exception: Exception) {
                        throw IllegalStateException(
                            "Failed to populate Origami's shared $outputName cache. Temporary files: ${staging.absolutePath}",
                            exception,
                        )
                    }
                    
                    copy(entry, output)
                    false
                }
            }
        }
    }
    
    private fun copy(source: File, output: File) {
        if (output.exists()) {
            check(output.deleteRecursively()) { "Could not clear transform output ${output.absolutePath}" }
        }
        
        source.walkTopDown().forEach { sourceFile ->
            val outputFile = output.resolve(sourceFile.relativeTo(source).path)
            if (sourceFile.isDirectory) {
                check(outputFile.mkdirs() || outputFile.isDirectory) { "Could not create transform output directory ${outputFile.absolutePath}" }
            } else {
                sourceFile.toPath().copyTo(outputFile.toPath())
            }
        }
    }
    
}

private fun String.toCachePathSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")
