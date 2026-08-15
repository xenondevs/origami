package xyz.xenondevs.origami

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import xyz.xenondevs.origami.extension.OrigamiExtension
import javax.inject.Inject

internal const val ORIGAMI_TASK_GROUP = "origami"
internal const val ORIGAMI_EXTENSION = "origami"

abstract class OrigamiPlugin : Plugin<Project> {
    
    @get:Inject
    abstract val javaToolchainService: JavaToolchainService
    
    lateinit var localRepo: Provider<Directory>

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
        val configurations = OrigamiConfigurations(target)
        target.registerExtensions(configurations)
        val ext = target.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
        localRepo = ext.cache.dir("local-repo")
        
        target.registerTasks(this, configurations)
        target.registerPackagingTasks(configurations)
        target.registerRunTasks(this, configurations)
    }
    
    companion object {
        val version = this::class.java.classLoader.getResourceAsStream("xyz.xenondevs.origami.version")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not read origami plugin version from resources")
    }
    
}
