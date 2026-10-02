package xyz.xenondevs.origami

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject

internal const val ORIGAMI_TASK_GROUP = "origami"
internal const val ORIGAMI_EXTENSION = "origami"

abstract class OrigamiPlugin : Plugin<Project> {
    
    private lateinit var configurations: OrigamiConfigurations
    
    /**
     * The server's compile dependencies, excluding the server JAR itself.
     */
    val serverCompileClasspath: Configuration
        get() = configurations.devBundleCompileClasspath
    
    /**
     * The server's runtime dependencies, excluding the server JAR itself.
     */
    val serverRuntimeClasspath: Configuration
        get() = configurations.devBundleRuntimeClasspath
    
    @get:Inject
    internal abstract val javaToolchainService: JavaToolchainService
    
    internal fun javaLauncherFor(version: Int): Provider<JavaLauncher> =
        javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(version)) }
    
    internal fun javaLauncherFor(
        project: Project,
        fallbackVersion: Int = 25
    ): Provider<JavaLauncher> = javaToolchainService
        .launcherFor(project.extensions.getByType<JavaPluginExtension>().toolchain)
        .orElse(javaLauncherFor(fallbackVersion))
    
    override fun apply(target: Project) {
        target.plugins.apply("java")
        configurations = OrigamiConfigurations(target)
        target.registerExtensions()
        target.registerSetupPipeline(this, configurations)
        target.registerPackagingTasks(configurations)
        target.registerRunTasks(this, configurations)
    }
    
    internal companion object {
        val version = this::class.java.classLoader.getResourceAsStream("xyz.xenondevs.origami.version")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not read origami plugin version from resources")
    }
    
}
