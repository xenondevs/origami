package xyz.xenondevs.origami.task.run

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories

internal abstract class PatchRunServerJar : JavaExec() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverJar: RegularFileProperty
    
    @get:Internal // tracked via patchFingerprint
    abstract val plugins: ConfigurableFileCollection
    
    @get:InputDirectory
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
        
        val tmpOut = Files.createTempFile(temporaryDir.toPath(), ".patch-run-server.", ".jar")
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
            
            Files.move(tmpOut, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmpOut)
        }
    }
    
}
