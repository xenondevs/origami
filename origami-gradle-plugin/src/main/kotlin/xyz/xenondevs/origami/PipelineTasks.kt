package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.service.DownloaderService
import xyz.xenondevs.origami.task.setup.ApplyBinDiffTask
import xyz.xenondevs.origami.task.setup.ApplyPaperPatchesTask
import xyz.xenondevs.origami.task.setup.DecompileTask
import xyz.xenondevs.origami.task.setup.InstallTask
import xyz.xenondevs.origami.task.setup.RemapTask
import xyz.xenondevs.origami.task.setup.VanillaDownloadTask
import xyz.xenondevs.origami.task.setup.WidenTask
import xyz.xenondevs.origami.util.getIdeaSourcesDownloadTasks
import xyz.xenondevs.origami.util.isIdeaSync
import xyz.xenondevs.origami.util.prependTaskRequest
import xyz.xenondevs.origami.value.DevBundle
import xyz.xenondevs.origami.value.DevBundleHashSource
import xyz.xenondevs.origami.value.DevBundleValueSource
import xyz.xenondevs.origami.value.MacheConfig
import xyz.xenondevs.origami.value.MacheConfigValueSource
import java.io.File

fun Project.registerTasks(dl: Provider<DownloaderService>, plugin: OrigamiPlugin) {
    fun Provider<File>.toRegular() = layout.file(this)
    
    val ext: OrigamiExtension = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    val bundleZip: Provider<RegularFile> = configurations.named(DEV_BUNDLE_CONFIG).map { it.singleFile }.toRegular()
    val macheZip: Provider<RegularFile> = configurations.named(MACHE_CONFIG).map { it.singleFile }.toRegular()
    val devBundleInfo: Provider<DevBundle> = providers.of(DevBundleValueSource::class) { parameters.zip.set(bundleZip) }
    val devBundleHash: Provider<String> = providers.of(DevBundleHashSource::class) { parameters.zip.set(bundleZip) }
    val macheConfig: Provider<MacheConfig> = providers.of(MacheConfigValueSource::class) { parameters.zip.set(macheZip) }
    val mcVersion: Provider<String> = devBundleInfo.map(DevBundle::minecraftVersion)
    val workDir: Provider<Directory> = ext.cache.zip(devBundleHash) { cache, hash -> cache.dir(hash) }
    val launcher: Provider<JavaLauncher> = extensions.findByType<JavaPluginExtension>()
        ?.toolchain
        ?.let(plugin.javaToolchainService::launcherFor)
        ?: plugin.javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    
    @Suppress("ReplaceSizeCheckWithIsNotEmpty") // broken for DependencySet
    val hasDevBundle: Provider<Boolean> = configurations.named(DEV_BUNDLE_CONFIG).map { it.allDependencies.size != 0 }
    
    dependencies.addDependenciesToPipelineConfigs(devBundleInfo, macheConfig)
    
    val clean = tasks.register<Delete>("_oriClean") {
        group = ORIGAMI_TASK_GROUP
        delete(ext.cache)
    }
    
    fun Task.configureCommon() {
        group = ORIGAMI_TASK_GROUP
        onlyIf { hasDevBundle.get() }
        mustRunAfter(clean) // prevent clean from running after ori setup
    }
    
    fun InstallTask.configureCommon() {
        (this as Task).configureCommon()
        
        localRepo.set(plugin.localRepo)
        group.set("xyz.xenondevs.origami.patched-server")
        name.set("widened-server-${project.name}")
        version.set(ext.devBundleVersion)
    }
    
    fun WidenTask.configureCommon() {
        (this as Task).configureCommon()
        
        accessWidenerFile.set(ext.pluginId
            .map { id -> layout.projectDirectory.file("src/main/resources/$id.accesswidener") }
            .filter { it.asFile.exists() }
        )
        transitiveAccessWidenerSources.from(ext.transitiveAccessWidenerSources)
    }
    
    val installPom = tasks.register<InstallTask.Pom>("_oriInstallPom") {
        configureCommon()
        paperClasspathConfig.set(project.configurations.named(DEV_BUNDLE_COMPILE_CLASSPATH))
    }
    
    val vanillaDownloads = tasks.register<VanillaDownloadTask>("_oriVanillaDownload") {
        configureCommon()
        
        minecraftVersion.set(mcVersion)
        downloader.set(dl)
        
        val workDir = workDir.map { it.dir("vanilla") }
        bundleJar.set(workDir.map { it.file("bundle.jar") })
        serverMappings.set(workDir.map { it.file("server-mappings.txt") })
        serverJar.set(workDir.map { it.file("server.jar") })
        librariesDir.set(workDir.map { it.dir("libraries") })
    }
    
    //<editor-fold desc="binaries pipeline">
    val applyBinDiff = tasks.register<ApplyBinDiffTask>("_oriApplyBinDiff") {
        configureCommon()
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        devBundleZip.set(bundleZip)
        paperclipInternalPath.set(devBundleInfo.map(DevBundle::mojangMappedPaperclipFile))
        minecraftVersion.set(mcVersion)
        javaLauncher.set(launcher)
        
        patchedJar.set(workDir.map { it.file("paperclip/paperclip-patched.jar") })
    }
    
    val widenJar = tasks.register<WidenTask.Jar>("_oriWidenJar") {
        configureCommon()
        input.set(applyBinDiff.flatMap(ApplyBinDiffTask::patchedJar))
        output.set(workDir.map { it.file("widened/paper-server-widened.jar") })
    }
    
    val installJar = tasks.register<InstallTask.Artifact>("_oriInstallJar") {
        configureCommon()
        dependsOn(installPom)
        source.set(widenJar.flatMap(WidenTask::output))
    }
    //</editor-fold>
    
    //<editor-fold desc="sources pipeline">
    val remap = tasks.register<RemapTask>("_oriRemap") {
        configureCommon()
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        mappings.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverMappings))
        paramMappings.set(configurations.named(PARCHMENT_CONFIG).map { it.singleFile }.toRegular())
        codebook.set(configurations.named(CODEBOOK_CONFIG).map { it.singleFile }.toRegular())
        remapper.set(configurations.named(REMAPPER_CONFIG).map { it.singleFile }.toRegular())
        remapperArgs.set(macheConfig.map { it.remapperArgs })
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVersion)
        remappedJar.set(workDir.map { it.file("remapped/server-remapped.jar") })
    }
    
    val decompile = tasks.register<DecompileTask>("_oriDecompile") {
        configureCommon()
        
        remappedJar.set(remap.flatMap(RemapTask::remappedJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        decompiler.set(configurations.named(DECOMPILER_CONFIG).map { it.singleFile }.toRegular())
        decompilerArgs.set(macheConfig.map { it.decompilerArgs })
        macheFile.set(macheZip)
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVersion)
        decompiledSources.set(workDir.map { it.file("decompiled/server-decompiled.jar") })
    }
    
    val applyPatches = tasks.register<ApplyPaperPatchesTask>("_oriApplyPaperPatches") {
        configureCommon()
        
        devBundleZip.set(bundleZip)
        vanillaSources.set(decompile.flatMap(DecompileTask::decompiledSources))
        minecraftVersion.set(mcVersion)
        patchesRootName.set(devBundleInfo.map(DevBundle::patchDir))
        
        val workDir = workDir.map { it.dir("decompiled-patched") }
        patchedJar.set(workDir.map { it.file("server-patched.jar") })
        newSources.set(workDir.map { it.dir("new-sources") })
        patchedSources.set(workDir.map { it.dir("patched-sources") })
    }
    
    val widenSources = tasks.register<WidenTask.SourcesJar>("_oriWidenSourcesJar") {
        configureCommon()
        newSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::newSources))
        patchedSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedSources))
        librariesDir.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        input.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedJar))
        output.set(workDir.map { it.file("widened/paper-server-widened-sources.jar") })
    }
    
    val installSourcesJar = tasks.register<InstallTask.Artifact>("_oriInstallSourcesJar") {
        configureCommon()
        dependsOn(installPom)
        classifier.set("sources")
        source.set(widenSources.flatMap(WidenTask::output))
    }
    //</editor-fold>
    
    tasks.register("_oriInstall") {
        configureCommon()
        dependsOn(installJar, installSourcesJar)
    }
    
    afterEvaluate {
        if (!hasDevBundle.get())
            return@afterEvaluate
        
        // idea sync installs jar
        if (isIdeaSync()) {
            // TODO: if sources exist, either delete them or regenerate them as well (prevent access widener desync between sources and binaries)
            prependTaskRequest(gradle.startParameter, installJar)
        }
        
        // download sources button triggers source generation
        for (task in getIdeaSourcesDownloadTasks(this, installSourcesJar.get())) {
            task.dependsOn(installSourcesJar)
        }
        
        for (targetCfg in ext.targetConfigurations.get()) {
            targetCfg.withDependencies {
                add(dependencyFactory.create(files(installJar.flatMap { it.dummyFile })))
                addLater(installJar.flatMap {
                    it.name.zip(it.version) { artifact, version ->
                        dependencyFactory.create("xyz.xenondevs.origami.patched-server:$artifact:$version")
                    }
                })
            }
        }
        
        repositories {
            maven(plugin.localRepo) {
                content { includeGroup("xyz.xenondevs.origami.patched-server") }
            }
            
            macheConfig.get().repositories.forEach { repo ->
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

private fun DependencyHandler.addDependenciesToPipelineConfigs(devBundleInfo: Provider<DevBundle>, macheConfig: Provider<MacheConfig>) {
    addProvider(
        MACHE_CONFIG,
        devBundleInfo.map { it.mache.coordinates.first() }
    )
    addProvider(
        CODEBOOK_CONFIG,
        macheConfig.map { it.dependencies.codebook.first().toDependencyString() }
    )
    addProvider(
        PARCHMENT_CONFIG,
        macheConfig.map { it.dependencies.paramMappings.first().toDependencyString() }
    )
    addProvider(
        REMAPPER_CONFIG,
        macheConfig.map { it.dependencies.remapper.first().toDependencyString() }
    )
    addProvider(
        DECOMPILER_CONFIG,
        macheConfig.map { it.dependencies.decompiler.first().toDependencyString() }
    )
}