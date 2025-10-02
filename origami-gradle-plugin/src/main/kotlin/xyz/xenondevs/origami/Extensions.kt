package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import xyz.xenondevs.origami.extension.OrigamiExtension

internal fun Project.registerExtensions() {
    val oriExt = extensions.create<OrigamiExtension>(ORIGAMI_EXTENSION)
    oriExt.pluginId.convention(name)
    
    val devBundleNotation = providers.provider {
        oriExt.devBundleVersion.orNull?.let { "${oriExt.devBundleGroup.get()}:${oriExt.devBundleArtifact.get()}:$it" }
    }
    dependencies.addProvider(DEV_BUNDLE_CONFIG, devBundleNotation)
    dependencies.addProvider(DEV_BUNDLE_COMPILE_CLASSPATH, devBundleNotation)
}