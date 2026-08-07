package xyz.xenondevs.origami.task.run

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

internal abstract class InvalidateAotCache : DefaultTask() {
    
    // inputs and outputs are only for up-to-date checking
    // if not up-to-date, that means we need to delete the AOT cache
    
    @get:InputDirectory
    abstract val patchFingerprint: DirectoryProperty
    
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection
    
    @get:OutputFile
    abstract val marker: RegularFileProperty
    
    @get:Inject
    abstract val projectLayout: ProjectLayout
    
    @TaskAction
    fun run() {
        projectLayout.buildDirectory.file("origami/server.aot").get().asFile.delete()
        marker.get().asFile
            .apply { parentFile.mkdirs() }
            .writeText("")
    }
    
}