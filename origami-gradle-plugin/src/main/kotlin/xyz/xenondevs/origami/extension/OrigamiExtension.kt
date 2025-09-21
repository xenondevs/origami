package xyz.xenondevs.origami.extension

import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.kotlin.dsl.setProperty
import javax.inject.Inject

abstract class OrigamiExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
    private val gradle: Gradle
) {
    
    val cache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.projectDirectory.dir(".gradle/caches/origami"))
    
    // TODO: make this optional and default to whatever is defined by the paper-plugin.yml in the marker task instead of
    //       the project name.
    val pluginId: Property<String> = objects.property(String::class.java)
    
    val devBundleGroup: Property<String> = objects.property(String::class.java)
        .convention("io.papermc.paper")
    val devBundleArtifact: Property<String> = objects.property(String::class.java)
        .convention("dev-bundle")
    val devBundleVersion: Property<String> = objects.property(String::class.java)
    
    val transitiveAccessWidenerConfigurations: SetProperty<Configuration> = objects.setProperty<Configuration>()
        .convention(emptySet())
    
    fun paperDevBundle(
        version: String,
        group: String = "io.papermc.paper",
        artifactId: String = "dev-bundle",
    ) {
        devBundleGroup.set(group)
        devBundleArtifact.set(artifactId)
        devBundleVersion.set(version)
    }
    
}