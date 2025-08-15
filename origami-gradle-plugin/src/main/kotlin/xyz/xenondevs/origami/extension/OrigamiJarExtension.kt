package xyz.xenondevs.origami.extension

import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.attributes
import org.gradle.kotlin.dsl.getByName
import xyz.xenondevs.origami.task.build.PrepareOrigamiLoaderTask
import xyz.xenondevs.origami.task.build.PrepareOrigamiMarkerTask

abstract class OrigamiJarExtension(private val jar: Jar) {
    
    fun addOrigamiLoader(librariesFolder: String) {
        jar.manifest {
            attributes(
                "Premain-Class" to "xyz.xenondevs.origami.OrigamiAgent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
            )
        }
        
        val prepTask = jar.project.tasks.getByName<PrepareOrigamiLoaderTask>("_oriPrepareLoader")
        prepTask.libsFolder.set(librariesFolder)
        val prepMarkerTask = jar.project.tasks.getByName<PrepareOrigamiMarkerTask>("_oriPrepareMarker")
        jar.dependsOn(prepTask, prepMarkerTask)
        jar.from(prepTask.outputDir, prepMarkerTask.jsonOutput)
    }
    
    fun addOrigamiJson() {
        val prepMarkerTask = jar.project.tasks.getByName<PrepareOrigamiMarkerTask>("_oriPrepareMarker")
        jar.dependsOn(prepMarkerTask)
        jar.from(prepMarkerTask.jsonOutput)
    }
    
}