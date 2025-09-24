package xyz.xenondevs.origami.extension

import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import javax.inject.Inject

abstract class OrigamiExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout
) {
    
    val cache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.projectDirectory.dir(".gradle/caches/origami"))
    
    // TODO: make this optional and default to whatever is defined by the paper-plugin.yml in the marker task instead of
    //       the project name.
    val pluginId: Property<String> = objects.property<String>()
    
    val devBundleGroup: Property<String> = objects.property<String>()
        .convention("io.papermc.paper")
    val devBundleArtifact: Property<String> = objects.property<String>()
        .convention("dev-bundle")
    val devBundleVersion: Property<String> = objects.property<String>()
    
    val transitiveAccessWidenerConfigurations: SetProperty<Configuration> = objects.setProperty<Configuration>()
        .convention(emptySet())
    
    /**
     * The input fils sto be used for the `origamiJar` task.
     * 
     * Defaults the input of the `jar` task.
     */
    val input: ConfigurableFileCollection = objects.fileCollection()
    
    /**
     * The name of the directory inside the jar containing origami's bundled libraries.
     * 
     * Defaults to `libs`.
     */
    val librariesDirectory: Property<String> = objects.property<String>()
        .convention("libs")
    
    /**
     * Configures [devBundleGroup], [devBundleArtifact], [devBundleVersion] based
     * on [group], [artifactId], and [version].
     */
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