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

internal fun Project.registerPackagingTasks() {
    val ext = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    
    val prepareLoader = tasks.register<PrepareOrigamiLoaderTask>("_oriPrepareLoader") {
        val origamiJitConfig = configurations.named(ORIGAMI_JIT_CONFIG)
        val origamiJitInjectablesConfig = configurations.named(ORIGAMI_JIT_INJECTABLES_CONFIG)
        
        libraryPaths.set(
            origamiJitConfig.zip(origamiJitInjectablesConfig) { c1, c2 ->
                c1.incoming.artifacts.resolvedArtifacts.zip(c2.incoming.artifacts.resolvedArtifacts) { a1, a2 ->
                    (a1 + a2).mapNotNull { artifact ->
                        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                            ?: return@mapNotNull null
                        val path = ext.librariesDirectory.get().removePrefix("/").removeSuffix("/") +
                            "/" + id.group.replace('.', '/') + "/" + id.module + "/" + id.version + "/" + artifact.file.name
                        artifact.file.absolutePath to path
                    }.toMap()
                }
            }.flatMap { it }
        )
        
        origamiConfig.from(origamiJitConfig)
        injectablesConfig.from(origamiJitInjectablesConfig)
        origamiLoaderConfig.from(configurations.named(ORIGAMI_JIT_LOADER_CONFIG))
        librariesDirectory.set(ext.librariesDirectory)
        outputDir.set(ext.cache.dir("loader-files"))
    }
    
    val prepareMarker = tasks.register<PrepareOrigamiMarkerTask>("_oriPrepareMarker") {
        origamiVersion.set(OrigamiPlugin.version)
        jsonOutput.set(ext.cache.file("origami.json"))
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