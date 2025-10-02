package xyz.xenondevs.origami

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.registerIfAbsent
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.service.DownloaderService
import javax.inject.Inject

internal const val DEV_BUNDLE_CONFIG = "paperweightDevelopmentBundle"
internal const val DEV_BUNDLE_COMPILE_CLASSPATH = "paperweightDevelopmentBundleCompileClasspath"
internal const val MACHE_CONFIG = "macheConfig"
internal const val CODEBOOK_CONFIG = "codebookConfig"
internal const val PARCHMENT_CONFIG = "parchmentConfig"
internal const val REMAPPER_CONFIG = "remapperConfig"
internal const val DECOMPILER_CONFIG = "decompilerConfig"
internal const val ORIGAMI_CONFIG = "origamiConfig"
internal const val ORIGAMI_LOADER_CONFIG = "origamiLoaderConfig"

internal const val ORIGAMI_TASK_GROUP = "origami"
internal const val ORIGAMI_EXTENSION = "origami"

abstract class OrigamiPlugin : Plugin<Project> {
    
    @get:Inject
    abstract val javaToolchainService: JavaToolchainService
    
    lateinit var localRepo: Provider<Directory>
    
    override fun apply(target: Project) {
        val dl = target.gradle.sharedServices.registerIfAbsent("origamiDownloader", DownloaderService::class)
        
        target.plugins.apply("java")
        target.registerConfigurations()
        target.registerExtensions()
        val ext = target.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
        localRepo = ext.cache.dir("local-repo")
        
        target.registerTasks(dl, this)
        target.registerPackagingTasks()
        target.registerPackagingExtensions()
    }
    
    companion object {
        val version = this::class.java.classLoader.getResourceAsStream("xyz.xenondevs.origami.version")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not read origami plugin version from resources")
    }
    
}
