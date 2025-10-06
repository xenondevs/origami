package xyz.xenondevs.origami.util

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories

internal inline fun withLock(lockFile: Provider<RegularFile>, action: () -> Unit) =
    withLock(lockFile.get().asFile.toPath()) { action() }

internal inline fun <T> withLock(lockFile: Path, action: () -> T): T {
    lockFile.createParentDirectories()
    return FileChannel.open(
        lockFile,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.DELETE_ON_CLOSE
    ).use { it.lock().use { action() } }
}