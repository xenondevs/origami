package xyz.xenondevs.origami.extension

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.named
import xyz.xenondevs.origami.DEV_BUNDLE_COMPILE_CLASSPATH
import xyz.xenondevs.origami.task.setup.WidenTask
import javax.inject.Inject

abstract class OrigamiDependenciesExtension @Inject constructor(private val project: Project) {
    
    fun patchedPaperServer(): FileCollection {
        val widenTask = project.tasks.named<WidenTask>("_oriWiden")
        val classpathConfig = project.configurations.named(DEV_BUNDLE_COMPILE_CLASSPATH)

        val classesJar = widenTask.flatMap(WidenTask::outputClassesJar)
        val sourcesJar = widenTask.flatMap(WidenTask::outputSourcesJar)
        val libs = classpathConfig.flatMap { config -> project.provider { project.files(config.files) } }

        val files = project.objects.fileCollection()
            .from(classesJar, sourcesJar, libs)
            .builtBy(widenTask)

        return files
    }
    
}