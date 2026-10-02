package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.attributes
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiLoaderTask
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiMarkerTask

internal fun Project.registerPackagingTasks(configs: OrigamiConfigurations) {
    val ext = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    
    val prepareLoader = tasks.register<PrepareOrigamiLoaderTask>("_oriPrepareLoader") {
        libraryPaths.set(
            configs.jit.incoming.artifacts.resolvedArtifacts.zip(
                configs.jitInjectables.incoming.artifacts.resolvedArtifacts
            ) { jitArtifacts, injectableArtifacts ->
                (jitArtifacts + injectableArtifacts).mapNotNull { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                        ?: return@mapNotNull null
                    val path = ext.librariesDirectory.get().removePrefix("/").removeSuffix("/") +
                        "/" + id.group.replace('.', '/') + "/" + id.module + "/" + id.version + "/" + artifact.file.name
                    artifact.file.absolutePath to path
                }.toMap()
            }
        )
        
        origamiConfig.from(configs.jit)
        injectablesConfig.from(configs.jitInjectables)
        origamiLoaderConfig.from(configs.jitLoader)
        librariesDirectory.set(ext.librariesDirectory)
        outputDir.set(layout.buildDirectory.dir("origami/loader-files"))
    }
    
    val prepareMarker = tasks.register<PrepareOrigamiMarkerTask>("_oriPrepareMarker") {
        origamiVersion.set(OrigamiPlugin.version)
        jsonOutput.set(layout.buildDirectory.file("origami/origami.json"))
    }
    
    tasks.register<Jar>("origamiJar") {
        group = LifecycleBasePlugin.BUILD_GROUP
        
        val jar = tasks.named<Jar>("jar")
        archiveBaseName.set(jar.flatMap { it.archiveBaseName })
        archiveAppendix.set(jar.flatMap { it.archiveAppendix })
        archiveVersion.set(jar.flatMap { it.archiveVersion })
        archiveExtension.set(jar.flatMap { it.archiveExtension })
        archiveClassifier.set("origami")
        
        with(ext.input.get())
        from(prepareMarker.flatMap { it.jsonOutput })
        from(prepareLoader.flatMap { it.outputDir })
        manifest {
            attributes(
                "Premain-Class" to "xyz.xenondevs.origami.OrigamiAgent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
            )
        }
    }
}