package xyz.xenondevs.origami.task.run

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo

@DisableCachingByDefault
internal abstract class PatchRunServerJar : JavaExec() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverJar: RegularFileProperty
    
    @get:Internal // tracked via patchFingerprint
    abstract val plugins: ConfigurableFileCollection
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patchFingerprint: DirectoryProperty // purely for up-to-date checking
    
    @get:Classpath
    abstract val serverClasspath: ConfigurableFileCollection
    
    @get:OutputFile
    abstract val outputJar: RegularFileProperty
    
    init {
        workingDir(temporaryDir)
        jvmArgs("-Dmixin.debug=true")
        mainClass.set("xyz.xenondevs.origami.aot.OrigamiAot")
    }
    
    @TaskAction
    override fun exec() {
        val output = outputJar.get().asFile.toPath()
        output.createParentDirectories()
        
        val tmpOut = createTempFile(temporaryDir.toPath(), ".patch-run-server.", ".jar")
        try {
            setArgs(
                listOf(
                    serverJar.get().asFile.absolutePath,
                    tmpOut.absolutePathString(),
                    serverClasspath.asPath,
                    plugins.asPath
                )
            )
            
            super.exec()
            
            try {
                tmpOut.moveTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                tmpOut.moveTo(output, overwrite = true)
            }
        } finally {
            tmpOut.deleteIfExists()
        }
    }
    
}
