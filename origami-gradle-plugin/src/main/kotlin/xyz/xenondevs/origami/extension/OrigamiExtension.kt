package xyz.xenondevs.origami.extension

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
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
    
    @Deprecated("Setup artifacts are stored in Gradle's artifact transform cache")
    val cache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.projectDirectory.dir(".gradle/caches/origami"))
    
    /**
     * The system-wide cache for project-independent server setup artifacts.
     */
    val sharedCache: DirectoryProperty = objects.directoryProperty()
        .convention(layout.dir(project.provider { gradle.gradleUserHomeDir.resolve("caches/origami") }))
    
    @Deprecated("pluginId is unused")
    val pluginId: Property<String> = objects.property<String>()
    
    /**
     * The group of the dev-bundle. Default: `io.papermc.paper`.
     */
    val devBundleGroup: Property<String> = objects.property<String>()
        .convention("io.papermc.paper")
    
    /**
     * The name of the dev-bundle. Default: `dev-bundle`.
     */
    val devBundleArtifact: Property<String> = objects.property<String>()
        .convention("dev-bundle")
    
    /**
     * The version of the dev-bundle. No default.
     */
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
    
    /**
     * The [RunServerExtension].
     */
    val runServer: RunServerExtension = objects.newInstance()
    
    /**
     * Configures [RunServerExtension]
     */
    fun runServer(configure: Action<in RunServerExtension>) {
        configure.execute(runServer)
    }
    
    abstract class RunServerExtension @Inject constructor(objects: ObjectFactory) {
        
        /**
         * Additional application classpath of the server. JARs on this classpath
         * will take part in AOT-caching.
         */
        val classpath: ConfigurableFileCollection = objects.fileCollection()
        
        /**
         * The plugins to load.
         *
         * All plugins that use origami must be in this collection, otherwise
         * their mixins and access wideners won't be applied.
         *
         * Plugins that don't use origami can also just be in the `plugins/` directory.
         */
        val plugins: ConfigurableFileCollection = objects.fileCollection()
        
        /**
         * The working directory of the server, i.e. where worlds and other
         * files are stored.
         */
        val workingDirectory: DirectoryProperty = objects.directoryProperty()
        
        /**
         * Overrides the [JavaLauncher] used to start the server.
         * Requires Java 26+
         */
        val javaLauncher: Property<JavaLauncher> = objects.property()
        
        /**
         * Additional JVM arguments.
         */
        val jvmArgs: ListProperty<String> = objects.listProperty()
        
        /**
         * Additional server arguments.
         */
        val args: ListProperty<String> = objects.listProperty()
        
        /**
         * The main class of the server.
         * Defaults to `org.bukkit.craftbukkit.Main`.
         */
        val mainClass: Property<String> = objects.property()
        
    }
    
}