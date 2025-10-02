package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.attributes
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiLoaderTask
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiMarkerTask

fun Project.registerPackagingTasks() {
    val ext = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    
    val prepareLoader = tasks.register<PrepareOrigamiLoaderTask>("_oriPrepareLoader") {
        group = ORIGAMI_TASK_GROUP
        
        origamiConfig.set(configurations[ORIGAMI_CONFIG])
        origamiLoaderConfig.set(configurations[ORIGAMI_LOADER_CONFIG])
        librariesDirectory.set(ext.librariesDirectory)
        outputDir.set(ext.cache.dir("loader-files"))
    }
    
    val prepareMarker = tasks.register<PrepareOrigamiMarkerTask>("_oriPrepareMarker") {
        group = ORIGAMI_TASK_GROUP
        
        origamiVersion.set(OrigamiPlugin.version)
        pluginId.set(ext.pluginId)
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

        from(ext.input) {
            exclude("MANIFEST.MF") // for some reason there is a MANIFEST.MF in the root dir
        }
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

fun Project.registerPackagingExtensions() {
    dependencies.addProvider(ORIGAMI_CONFIG, providers.provider { "xyz.xenondevs.origami:origami:${OrigamiPlugin.version}" })
    dependencies.addProvider(ORIGAMI_LOADER_CONFIG, providers.provider { "xyz.xenondevs.origami:origami-loader:${OrigamiPlugin.version}" })
}