package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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

fun Project.registerTasks(plugin: OrigamiPlugin) {
    fun Provider<File>.toRegular() = layout.file(this)
    
    val ext: OrigamiExtension = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    val bundleZip: Provider<RegularFile> = configurations.named(DEV_BUNDLE_CONFIG).map { it.singleFile }.toRegular()
    val macheZip: Provider<RegularFile> = configurations.named(MACHE_CONFIG).map { it.singleFile }.toRegular()
    val devBundleInfo: Provider<DevBundle> = providers.of(DevBundleValueSource::class) { parameters.zip.set(bundleZip) }
    val devBundleHash: Provider<String> = providers.of(DevBundleHashSource::class) { parameters.zip.set(bundleZip) }
    val macheConfig: Provider<MacheConfig> = providers.of(MacheConfigValueSource::class) { parameters.zip.set(macheZip) }
    val mcVersion: Provider<String> = devBundleInfo.map(DevBundle::minecraftVersion)
    val sharedWorkDir: Provider<Directory> = ext.sharedCache.zip(devBundleHash) { cache, hash -> cache.dir(hash) }
    val lockFile: Provider<RegularFile> = sharedWorkDir.map { it.file(".lock") }
    val launcher: Provider<JavaLauncher> = extensions.findByType<JavaPluginExtension>()
        ?.toolchain
        ?.let(plugin.javaToolchainService::launcherFor)
        ?: plugin.javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    
    @Suppress("ReplaceSizeCheckWithIsNotEmpty") // broken for DependencySet
    val hasDevBundle: Provider<Boolean> = configurations.named(DEV_BUNDLE_CONFIG).map { it.allDependencies.size != 0 }
    
    addDependenciesToPipelineConfigs(devBundleInfo, macheConfig)
    
    val clean = tasks.register<Delete>("_oriClean") {
        group = ORIGAMI_TASK_GROUP
        delete(ext.cache)
        delete(ext.sharedCache)
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
        serverDependencies.set(
            project.configurations.named(DEV_BUNDLE_COMPILE_CLASSPATH).map { cfg ->
                cfg.incoming.resolutionResult.root.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .single().selected.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
                    .map { "${it.group}:${it.module}:${it.version}" }
            }
        )
    }
    
    val vanillaDownloads = tasks.register<VanillaDownloadTask>("_oriVanillaDownload") {
        configureCommon()
        
        this.lockFile.set(lockFile)
        minecraftVersion.set(mcVersion)
        workDir.set(sharedWorkDir.map { it.dir("vanilla") })
    }
    
    //<editor-fold desc="binaries pipeline">
    val applyBinDiff = tasks.register<ApplyBinDiffTask>("_oriApplyBinDiff") {
        configureCommon()
        
        dependsOn(vanillaDownloads)
        this.lockFile.set(lockFile)
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        devBundleZip.set(bundleZip)
        paperclipInternalPath.set(devBundleInfo.map(DevBundle::mojangMappedPaperclipFile))
        minecraftVersion.set(mcVersion)
        javaLauncher.set(launcher)
        
        patchedJar.set(sharedWorkDir.map { it.file("paperclip/paperclip-patched.jar") })
    }
    
    val widenJar = tasks.register<WidenTask.Jar>("_oriWidenJar") {
        configureCommon()
        
        dependsOn(applyBinDiff)
        input.set(applyBinDiff.flatMap(ApplyBinDiffTask::patchedJar))
        output.set(layout.buildDirectory.file("origami/paper-server-widened.jar"))
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
        
        dependsOn(vanillaDownloads)
        this.lockFile.set(lockFile)
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        mappings.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverMappings))
        paramMappings.set(configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile }.toRegular())
        constants.set(configurations.named(CONSTANTS_CONFIG).filter { !it.isEmpty }.map { it.singleFile }.toRegular())
        codebook.set(configurations.named(CODEBOOK_CONFIG).map { it.singleFile }.toRegular())
        remapper.set(configurations.named(REMAPPER_CONFIG).map { it.singleFile }.toRegular())
        remapperArgs.set(macheConfig.map { it.remapperArgs })
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVersion)
        remappedJar.set(sharedWorkDir.map { it.file("remapped/server-remapped.jar") })
    }
    
    val decompile = tasks.register<DecompileTask>("_oriDecompile") {
        configureCommon()
        
        dependsOn(vanillaDownloads, remap)
        this.lockFile.set(lockFile)
        
        remappedJar.set(remap.flatMap(RemapTask::remappedJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        decompiler.set(configurations.named(DECOMPILER_CONFIG).map { it.singleFile }.toRegular())
        decompilerArgs.set(macheConfig.map { it.decompilerArgs })
        macheFile.set(macheZip)
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVersion)
        decompiledSources.set(sharedWorkDir.map { it.file("decompiled/server-decompiled.jar") })
    }
    
    val applyPatches = tasks.register<ApplyPaperPatchesTask>("_oriApplyPaperPatches") {
        configureCommon()
        
        dependsOn(decompile)
        this.lockFile.set(lockFile)
        
        devBundleZip.set(bundleZip)
        vanillaSources.set(decompile.flatMap(DecompileTask::decompiledSources))
        minecraftVersion.set(mcVersion)
        patchesRootName.set(devBundleInfo.map(DevBundle::patchDir))
        
        val workDir = sharedWorkDir.map { it.dir("decompiled-patched") }
        patchedJar.set(workDir.map { it.file("server-patched.jar") })
        newSources.set(workDir.map { it.dir("new-sources") })
        patchedSources.set(workDir.map { it.dir("patched-sources") })
    }
    
    val widenSources = tasks.register<WidenTask.SourcesJar>("_oriWidenSourcesJar") {
        configureCommon()
        
        dependsOn(vanillaDownloads, applyPatches)
        newSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::newSources))
        patchedSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedSources))
        librariesDir.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        input.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedJar))
        output.set(layout.buildDirectory.file("origami/paper-server-widened-sources.jar"))
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
                            CODEBOOK_CONFIG, PARAM_MAPPINGS_CONFIG, CONSTANTS_CONFIG, REMAPPER_CONFIG, DECOMPILER_CONFIG
                        )
                    }
                }
            }
        }
    }
}

private fun Project.addDependenciesToPipelineConfigs(devBundleInfo: Provider<DevBundle>, macheConfig: Provider<MacheConfig>) {
    val deps = this.dependencies
    this.configurations.apply {
        named(MACHE_CONFIG) {
            defaultDependencies {
                addAllLater(devBundleInfo.map { it.mache.coordinates.map(deps::create) })
            }
        }
        named(CODEBOOK_CONFIG) {
            defaultDependencies {
                addAllLater(macheConfig.map { mache ->
                    mache.dependencies.codebook.map { deps.create(it.toDependencyString()) }
                })
            }
        }
        named(PARAM_MAPPINGS_CONFIG) {
            defaultDependencies {
                addAllLater(macheConfig.map { mache ->
                    mache.dependencies.paramMappings.map { deps.create(it.toDependencyString()) }
                })
            }
        }
        named(CONSTANTS_CONFIG) {
            defaultDependencies {
                addAllLater(macheConfig.map { mache ->
                    mache.dependencies.constants.map { deps.create(it.toDependencyString()) }
                })
            }
        }
        named(REMAPPER_CONFIG) {
            defaultDependencies {
                addAllLater(macheConfig.map { mache ->
                    mache.dependencies.remapper.map { deps.create(it.toDependencyString()) }
                })
            }
        }
        named(DECOMPILER_CONFIG) {
            defaultDependencies {
                addAllLater(macheConfig.map { mache ->
                    mache.dependencies.decompiler.map { deps.create(it.toDependencyString()) }
                })
            }
        }
    }
}