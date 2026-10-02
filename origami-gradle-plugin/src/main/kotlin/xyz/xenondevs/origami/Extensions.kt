package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import xyz.xenondevs.origami.extension.OrigamiExtension

internal fun Project.registerExtensions() {
    val oriExt = extensions.create<OrigamiExtension>(ORIGAMI_EXTENSION)
    oriExt.pluginId.convention(name)
}
