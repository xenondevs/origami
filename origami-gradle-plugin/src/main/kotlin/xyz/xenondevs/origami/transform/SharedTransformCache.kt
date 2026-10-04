package xyz.xenondevs.origami.transform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.moveTo

private const val CACHE_VERSION = 1
private val LOCAL_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

/**
 * Reuses expensive dev bundle setup results across unrelated Gradle projects.
 */
internal class SharedTransformCache(
    cacheRoot: File,
    devBundleId: String,
    private val outputName: String,
) {
    
    private val versionDirectory = cacheRoot.resolve("v$CACHE_VERSION/${devBundleId.replace(':', '/')}")
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
                    
                    val tmp = versionDirectory.resolve("$outputName.tmp")
                    if (tmp.exists())
                        tmp.deleteRecursively()
                    check(tmp.mkdirs()) { "Could not create shared cache staging directory ${tmp.absolutePath}" }
                    
                    try {
                        producer(tmp)
                        try {
                            tmp.toPath().moveTo(entry.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            tmp.toPath().moveTo(entry.toPath())
                        }
                    } catch (exception: Exception) {
                        throw IllegalStateException(
                            "Failed to populate Origami's shared $outputName cache. Temporary files: ${tmp.absolutePath}",
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
        if (output.exists())
            check(output.deleteRecursively()) { "Could not clear transform output ${output.absolutePath}" }
        check(source.copyRecursively(output)) { "Could not copy shared cache entry to ${output.absolutePath}" }
    }
    
}
