package xyz.xenondevs.origami.extension

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import xyz.xenondevs.origami.util.providerSet
import javax.inject.Inject

abstract class OrigamiExtension @Inject constructor(
    gradle: Gradle,
    project: Project,
    objects: ObjectFactory,
    layout: ProjectLayout
) {
    
    val cache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.projectDirectory.dir(".gradle/caches/origami"))
    
    val sharedCache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.dir(project.provider { gradle.gradleUserHomeDir.resolve("caches/origami/0/") })) // increment number when cache structure changes
    
    // TODO: make this optional and default to whatever is defined by the paper-plugin.yml in the marker task instead of
    //       the project name.
    val pluginId: Property<String> = objects.property<String>()
    
    val devBundleGroup: Property<String> = objects.property<String>()
        .convention("io.papermc.paper")
    val devBundleArtifact: Property<String> = objects.property<String>()
        .convention("dev-bundle")
    val devBundleVersion: Property<String> = objects.property<String>()
    
    /**
     * A collection of files from which transitive access wideners should be read and applied.
     *
     * Defaults to none.
     */
    val transitiveAccessWidenerSources: ConfigurableFileCollection = objects.fileCollection()
    
    /**
     * The [CopySpec] to merge into `origamiJar`.
     *
     * Defaults to the `jar` task.
     */
    val input: Property<CopySpec> = objects.property<CopySpec>()
        .convention(project.tasks.named<Jar>("jar"))
    
    /**
     * The name of the directory inside the jar containing origami's bundled libraries.
     *
     * Defaults to `libs`.
     */
    val librariesDirectory: Property<String> = objects.property<String>()
        .convention("libs")
    
    /**
     * The configurations to which the server dependency should be added.
     */
    val targetConfigurations: SetProperty<Configuration> = objects.setProperty<Configuration>()
        .convention(objects.providerSet(
            project.configurations.named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME),
            project.configurations.named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME)
        ))
    
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