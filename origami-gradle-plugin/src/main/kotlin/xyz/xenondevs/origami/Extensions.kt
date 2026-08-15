package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import xyz.xenondevs.origami.extension.OrigamiExtension

internal fun Project.registerExtensions(configs: OrigamiConfigurations) {
    val oriExt = extensions.create<OrigamiExtension>(ORIGAMI_EXTENSION)
    oriExt.pluginId.convention(name)
    
    val devBundleNotation = providers.provider {
        oriExt.devBundleVersion.orNull?.let { "${oriExt.devBundleGroup.get()}:${oriExt.devBundleArtifact.get()}:$it" }
    }
    val devBundleDependency = devBundleNotation.map(dependencies::create)
    configs.devBundle.configure { dependencies.addLater(devBundleDependency) }
    configs.devBundleCompileClasspath.configure { dependencies.addLater(devBundleDependency) }
    configs.devBundleRuntimeClasspath.configure { dependencies.addLater(devBundleDependency) }
}