package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.service.DownloaderService
import xyz.xenondevs.origami.task.ApplyBinDiffTask
import xyz.xenondevs.origami.task.ApplyPaperPatchesTask
import xyz.xenondevs.origami.task.DecompileTask
import xyz.xenondevs.origami.task.RemapTask
import xyz.xenondevs.origami.task.VanillaDownloadTask
import xyz.xenondevs.origami.task.WidenTask
import xyz.xenondevs.origami.value.DevBundle
import xyz.xenondevs.origami.value.DevBundleValueSource
import xyz.xenondevs.origami.value.MacheConfigValueSource
import java.io.File

fun Project.registerTasks(dl: Provider<DownloaderService>, javaToolchainService: JavaToolchainService) {
    fun Provider<File>.toRegular() = layout.file(this)
    
    val bundleZip = configurations.named(DEV_BUNDLE_CONFIG).map { it.singleFile }.toRegular()
    val macheZip = configurations.named(MACHE_CONFIG).map { it.singleFile }.toRegular()
    
    val ext = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    
    val devBundleInfo: Provider<DevBundle> =
        providers.of(DevBundleValueSource::class) {
            parameters.getZip().set(bundleZip)
        }
    
    @Suppress("ReplaceSizeCheckWithIsNotEmpty") // broken for DependencySet
    val hasDevBundle = configurations.named(DEV_BUNDLE_CONFIG).map { it.allDependencies.size != 0 }
    val mcVerProvider = devBundleInfo.map(DevBundle::minecraftVersion)
    
    dependencies.addProvider(
        MACHE_CONFIG,
        devBundleInfo.map { it.mache.coordinates.first() }
    )
    
    val macheConfig = providers.of(MacheConfigValueSource::class) {
        parameters.getZip().set(macheZip)
    }
    
    dependencies.addProvider(
        CODEBOOK_CONFIG,
        macheConfig.map { it.dependencies.codebook.first().toDependencyString() }
    )
    dependencies.addProvider(
        PARCHMENT_CONFIG,
        macheConfig.map { it.dependencies.paramMappings.first().toDependencyString() }
    )
    dependencies.addProvider(
        REMAPPER_CONFIG,
        macheConfig.map { it.dependencies.remapper.first().toDependencyString() }
    )
    dependencies.addProvider(
        DECOMPILER_CONFIG,
        macheConfig.map { it.dependencies.decompiler.first().toDependencyString() }
    )
    
    val launcher = extensions.findByType<JavaPluginExtension>()?.toolchain?.let(javaToolchainService::launcherFor)
        ?: javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    
    val vanillaDownloads = tasks.register<VanillaDownloadTask>("_oriVanillaDownload") {
        onlyIf { hasDevBundle.get() }
        
        minecraftVersion.set(mcVerProvider)
        downloader.set(dl)
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("vanilla-$version")
        }
        
        bundleJar.set(workDir.map { it.file("bundle.jar") })
        serverMappings.set(workDir.map { it.file("server-mappings.txt") })
        serverJar.set(workDir.map { it.file("server.jar") })
        librariesDir.set(workDir.map { it.dir("libraries") })
    }
    
    val applyBinDiff = tasks.register<ApplyBinDiffTask>("_oriApplyBinDiff") {
        onlyIf { hasDevBundle.get() }
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        devBundleZip.set(bundleZip)
        paperclipInternalPath.set(devBundleInfo.map(DevBundle::mojangMappedPaperclipFile))
        minecraftVersion.set(mcVerProvider)
        javaLauncher.set(launcher)
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("paperclip-$version")
        }
        
        patchedJar.set(workDir.map { it.file("paperclip-patched.jar") })
    }
    
    val remap = tasks.register<RemapTask>("_oriRemap") {
        onlyIf { hasDevBundle.get() }
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        mappings.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverMappings))
        paramMappings.set(configurations.named(PARCHMENT_CONFIG).map { it.singleFile }.toRegular())
        codebook.set(configurations.named(CODEBOOK_CONFIG).map { it.singleFile }.toRegular())
        remapper.set(configurations.named(REMAPPER_CONFIG).map { it.singleFile }.toRegular())
        remapperArgs.set(macheConfig.map { it.remapperArgs })
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVerProvider)
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("remapped-$version")
        }
        
        remappedJar.set(workDir.map { it.file("server-remapped.jar") })
    }
    
    val decompile = tasks.register<DecompileTask>("_oriDecompile") {
        onlyIf { hasDevBundle.get() }
        
        remappedJar.set(remap.flatMap(RemapTask::remappedJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        decompiler.set(configurations.named(DECOMPILER_CONFIG).map { it.singleFile }.toRegular())
        decompilerArgs.set(macheConfig.map { it.decompilerArgs })
        macheFile.set(macheZip)
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVerProvider)
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("decompiled-$version")
        }
        
        decompiledSources.set(workDir.map { it.file("server-decompiled.jar") })
    }
    
    val applyPatches = tasks.register<ApplyPaperPatchesTask>("_oriApplyPaperPatches") {
        onlyIf { hasDevBundle.get() }
        
        devBundleZip.set(bundleZip)
        vanillaSources.set(decompile.flatMap(DecompileTask::decompiledSources))
        minecraftVersion.set(mcVerProvider)
        patchesRootName.set(devBundleInfo.map(DevBundle::patchDir))
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("decompiled-patched-$version")
        }
        
        patchedJar.set(workDir.map { it.file("server-patched.jar") })
        newSources.set(workDir.map { it.dir("new-sources") })
        patchedSources.set(workDir.map { it.dir("patched-sources") })
    }
    
    val widenMerge = tasks.register<WidenTask>("_oriWiden") {
        onlyIf { hasDevBundle.get() }
        
        accessWidenerFile.set(ext.pluginId
            .map { id -> layout.projectDirectory.file("src/main/resources/$id.accesswidener") }
            .filter { it.asFile.exists() }
        )
        sourcesJar.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedJar))
        newSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::newSources))
        patchedSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedSources))
        serverJar.set(applyBinDiff.flatMap(ApplyBinDiffTask::patchedJar))
        librariesDir.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        
        val workDir = ext.cache.dir("widened")
        
        outputClassesJar.set(workDir.map { it.file("paper-server-widened.jar") })
        outputSourcesJar.set(workDir.map { it.file("paper-server-widened-sources.jar") })
    }
    
    afterEvaluate {
        if (!hasDevBundle.get()) return@afterEvaluate
        
        gradle.projectsEvaluated {
//            val importTask = tasks.findByName("prepareKotlinBuildScriptModel")
//                ?: tasks.findByName("ideaModule")
//                ?: tasks.findByName("eclipseClasspath")
//
//            importTask?.dependsOn(widenMerge)
        }
        
        val cfg = macheConfig.get()
        
        repositories {
            cfg.repositories.forEach { repo ->
                maven(repo.url) {
                    name = repo.name
                    content {
                        repo.groups.forEach(::includeGroupAndSubgroups)
                        onlyForConfigurations(
                            CODEBOOK_CONFIG, PARCHMENT_CONFIG, REMAPPER_CONFIG, DECOMPILER_CONFIG
                        )
                    }
                }
            }
        }
    }
}