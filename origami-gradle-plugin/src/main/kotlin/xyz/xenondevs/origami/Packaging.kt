package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.extension.OrigamiJarExtension
import xyz.xenondevs.origami.task.build.PrepareOrigamiLoaderTask
import xyz.xenondevs.origami.task.build.PrepareOrigamiMarkerTask

fun Project.registerPackagingTasks() {
    val ext = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    
    tasks.register<PrepareOrigamiLoaderTask>("_oriPrepareLoader") {
        origamiConfig.set(configurations[ORIGAMI_CONFIG])
        origamiLoaderConfig.set(configurations[ORIGAMI_LOADER_CONFIG])
        version.set(OrigamiPlugin.version)
        libsFolder.set("libs")
        
        outputDir.set(ext.cache.dir("loader-files"))
    }
    
    tasks.register<PrepareOrigamiMarkerTask>("_oriPrepareMarker") {
        origamiVersion.set(OrigamiPlugin.version)
        pluginId.set(ext.pluginId)
        
        jsonOutput.set(ext.cache.file("origami.json"))
    }
    
}

fun Project.registerPackagingExtensions() {
    tasks.withType<Jar>().configureEach { extensions.create<OrigamiJarExtension>(ORIGAMI_EXTENSION, this) }
    dependencies.addProvider(ORIGAMI_CONFIG, providers.provider { "xyz.xenondevs.origami:origami:${OrigamiPlugin.version}" })
    dependencies.addProvider(ORIGAMI_LOADER_CONFIG, providers.provider { "xyz.xenondevs.origami:origami-loader:${OrigamiPlugin.version}" })
}