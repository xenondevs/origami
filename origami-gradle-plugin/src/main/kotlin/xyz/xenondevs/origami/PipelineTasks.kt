package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.task.setup.ApplyBinDiffTask
import xyz.xenondevs.origami.task.setup.ApplyPaperPatchesTask
import xyz.xenondevs.origami.task.setup.CodebookTask
import xyz.xenondevs.origami.task.setup.DecompileTask
import xyz.xenondevs.origami.task.setup.InstallTask
import xyz.xenondevs.origami.task.setup.VanillaDownloadTask
import xyz.xenondevs.origami.task.setup.WidenTask
import xyz.xenondevs.origami.util.getIdeaSourcesDownloadTasks
import xyz.xenondevs.origami.util.isIdeaSync
import xyz.xenondevs.origami.util.prependTaskRequest
import xyz.xenondevs.origami.util.singleRegularFile
import xyz.xenondevs.origami.util.toRegular
import xyz.xenondevs.origami.value.DevBundle
import xyz.xenondevs.origami.value.DevBundleHashSource
import xyz.xenondevs.origami.value.DevBundleValueSource
import xyz.xenondevs.origami.value.MacheConfig
import xyz.xenondevs.origami.value.MacheConfigValueSource

internal fun Project.registerTasks(plugin: OrigamiPlugin, configs: OrigamiConfigurations) {
    val ext: OrigamiExtension = this.extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION)
    val bundleZip: Provider<RegularFile> = configs.devBundle.map { it.singleFile }.toRegular(layout)
    val macheZip = configs.mache.singleRegularFile(layout)
    val devBundleInfo: Provider<DevBundle> = providers.of(DevBundleValueSource::class) { parameters.zip.set(bundleZip) }
    val devBundleHash: Provider<String> = providers.of(DevBundleHashSource::class) { parameters.zip.set(bundleZip) }
    val macheConfig: Provider<MacheConfig> = providers.of(MacheConfigValueSource::class) { parameters.zip.set(macheZip) }
    val mcVersion: Provider<String> = devBundleInfo.map(DevBundle::minecraftVersion)
    val sharedWorkDir: Provider<Directory> = ext.sharedCache.zip(devBundleHash) { cache, hash -> cache.dir(hash) }
    val lockFile: Provider<RegularFile> = sharedWorkDir.map { it.file(".lock") }
    val launcher = plugin.javaLauncherFor(project)
    
    @Suppress("ReplaceSizeCheckWithIsNotEmpty") // broken for DependencySet
    val hasDevBundle: Provider<Boolean> = configs.devBundle.map { it.allDependencies.size != 0 }
    val resolvedDevBundleVersion: Provider<String> = configs.devBundle.map { cfg ->
        val selectedId = cfg.incoming.resolutionResult.root.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .single().selected.id
        (selectedId as? ModuleComponentIdentifier)?.version
            ?: error("Expected ${configs.devBundle.name} to resolve to a module component, but got $selectedId")
    }
    
    configs.configureMache(devBundleInfo, macheConfig)
    
    val clean = tasks.register<Delete>("_oriClean") {
        group = ORIGAMI_TASK_GROUP
        delete(ext.cache)
        delete(ext.sharedCache)
    }
    
    fun Task.configureCommon() {
        onlyIf { hasDevBundle.get() }
        mustRunAfter(clean) // prevent clean from running after ori setup
    }
    
    fun InstallTask.configureCommon() {
        (this as Task).configureCommon()
        
        localRepo.set(plugin.localRepo)
        group.set("xyz.xenondevs.origami.patched-server")
        name.set("widened-server-${project.name}")
        version.set(resolvedDevBundleVersion)
    }
    
    fun WidenTask.configureCommon() {
        (this as Task).configureCommon()
        val mainResources = project.extensions
            .getByType(JavaPluginExtension::class.java)
            .sourceSets.getByName("main")
            .resources
        accessWideners.from(mainResources.matching { include("**/*.accesswidener", "**/*.aw") })
        transitiveAccessWidenerSources.from(ext.transitiveAccessWidenerSources)
    }
    
    val installPom = tasks.register<InstallTask.Pom>("_oriInstallPom") {
        configureCommon()
        val dependencies = configs.devBundleCompileClasspath.map { cfg ->
            cfg.incoming.resolutionResult.root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .single().selected.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .filterNot { it.isConstraint }
                .mapNotNull { dependency ->
                    val id = dependency.selected.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                    val category = dependency.resolvedVariant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                    val isPlatform = category == Category.REGULAR_PLATFORM || category == Category.ENFORCED_PLATFORM
                    isPlatform to "${id.group}:${id.module}:${id.version}"
                }
        }
        serverDependencies.set(dependencies.map { deps ->
            deps.filterNot { (isPlatform, _) -> isPlatform }.map { (_, coordinates) -> coordinates }
        })
        serverPlatforms.set(dependencies.map { deps ->
            deps.filter { (isPlatform, _) -> isPlatform }.map { (_, coordinates) -> coordinates }
        })
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
        (this as Task).group = ORIGAMI_TASK_GROUP
        configureCommon()
        dependsOn(installPom)
        source.set(widenJar.flatMap(WidenTask::output))
        markerFile.set(localRepo.file("~origami"))
    }
    //</editor-fold>
    
    //<editor-fold desc="sources pipeline">
    val remap = tasks.register<CodebookTask>("_oriCodebook") {
        configureCommon()
        
        dependsOn(vanillaDownloads)
        this.lockFile.set(lockFile)
        
        vanillaServer.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        mappings.set(vanillaDownloads.flatMap(VanillaDownloadTask::serverMappings).filter { it.asFile.exists() })
        paramMappings.set(configs.paramMappings.singleRegularFile(layout, optional = true))
        constants.set(configs.constants.singleRegularFile(layout, optional = true))
        codebook.set(configs.codebook.singleRegularFile(layout))
        remapper.set(configs.remapper.singleRegularFile(layout, optional = true))
        remapperArgs.set(macheConfig.map { it.remapperArgs })
        javaLauncher.set(launcher)
        minecraftVersion.set(mcVersion)
        remappedJar.set(sharedWorkDir.map { it.file("remapped/server-remapped.jar") })
    }
    
    val decompile = tasks.register<DecompileTask>("_oriDecompile") {
        configureCommon()
        
        dependsOn(vanillaDownloads, remap)
        this.lockFile.set(lockFile)
        
        remappedJar.set(remap.flatMap(CodebookTask::remappedJar))
        vanillaLibraries.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        decompiler.set(configs.decompiler.singleRegularFile(layout))
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
        (this as Task).group = ORIGAMI_TASK_GROUP
        group.set(ORIGAMI_TASK_GROUP)
        configureCommon()
        dependsOn(installPom)
        classifier.set("sources")
        source.set(widenSources.flatMap(WidenTask::output))
    }
    //</editor-fold>
    
    tasks.register("_oriInstall") {
        group = ORIGAMI_TASK_GROUP
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
                add(dependencyFactory.create(files(installJar.flatMap { it.markerFile })))
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
        }
    }
}
