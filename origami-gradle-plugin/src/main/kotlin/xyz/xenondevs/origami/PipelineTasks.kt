package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.jvm.toolchain.JavaLanguageVersion
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
import xyz.xenondevs.origami.task.setup.WidenTask
import xyz.xenondevs.origami.task.setup.RemapTask
import xyz.xenondevs.origami.task.setup.VanillaDownloadTask
import xyz.xenondevs.origami.value.DevBundle
import xyz.xenondevs.origami.value.DevBundleValueSource
import xyz.xenondevs.origami.value.MacheConfigValueSource
import java.io.File

fun Project.registerTasks(dl: Provider<DownloaderService>, plugin: OrigamiPlugin) {
    fun Provider<File>.toRegular() = layout.file(this)
    
    val javaToolchainService = plugin.javaToolchainService
    
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
        name.set("widened-server-${project.name}")
        version.set(ext.devBundleVersion)
    }
    
    fun WidenTask.configureCommon() {
        (this as Task).configureCommon()
        
        accessWidenerFile.set(ext.pluginId
            .map { id -> layout.projectDirectory.file("src/main/resources/$id.accesswidener") }
            .filter { it.asFile.exists() }
        )
        transitiveAccessWidenerSources.from(
            ext.transitiveAccessWidenerConfigurations.map { cfgs ->
                cfgs.flatMap { cfg -> cfg.incoming.artifacts.artifactFiles }
            }
        )
    }
    
    val installPom = tasks.register<InstallTask.Pom>("_oriInstallPom") {
        configureCommon()
        paperClasspathConfig.set(project.configurations.named(DEV_BUNDLE_COMPILE_CLASSPATH))
    }
    
    val vanillaDownloads = tasks.register<VanillaDownloadTask>("_oriVanillaDownload") {
        configureCommon()
        
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
    
    //<editor-fold desc="binaries pipeline">
    val applyBinDiff = tasks.register<ApplyBinDiffTask>("_oriApplyBinDiff") {
        configureCommon()
        
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
    
    val widenJar = tasks.register<WidenTask.Jar>("_oriWidenJar") {
        configureCommon()
        val workDir = ext.cache.dir("widened")
        input.set(applyBinDiff.flatMap(ApplyBinDiffTask::patchedJar))
        output.set(workDir.map { it.file("paper-server-widened.jar") })
    }
    
    val installJar = tasks.register<InstallTask.Jar>("_oriInstallJar") {
        configureCommon()
        dependsOn(installPom)
        jar.set(widenJar.flatMap(WidenTask::output))
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
        minecraftVersion.set(mcVerProvider)
        
        val workDir = providers.zip(mcVerProvider, ext.cache) { version, cacheDir ->
            cacheDir.dir("remapped-$version")
        }
        
        remappedJar.set(workDir.map { it.file("server-remapped.jar") })
    }
    
    val decompile = tasks.register<DecompileTask>("_oriDecompile") {
        configureCommon()
        
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
        configureCommon()
        
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
    
    val widenSources = tasks.register<WidenTask.SourcesJar>("_oriWidenSourcesJar") {
        configureCommon()
        val workDir = ext.cache.dir("widened")
        newSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::newSources))
        patchedSourcesDir.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedSources))
        librariesDir.set(vanillaDownloads.flatMap(VanillaDownloadTask::librariesDir))
        input.set(applyPatches.flatMap(ApplyPaperPatchesTask::patchedJar))
        output.set(workDir.map { it.file("paper-server-widened-sources.jar") })
    }
    
    val installSourcesJar = tasks.register<InstallTask.SourcesJar>("_oriInstallSourcesJar") {
        configureCommon()
        dependsOn(installPom)
        sourcesJar.set(widenSources.flatMap(WidenTask::output))
    }
    //</editor-fold>
    
    tasks.register("_oriInstall") {
        configureCommon()
        dependsOn(installJar, installSourcesJar)
    }
    
    afterEvaluate {
        if (!hasDevBundle.get()) return@afterEvaluate
        
        gradle.projectsEvaluated {
            val importTask = tasks.findByName("prepareKotlinBuildScriptModel")
                ?: tasks.findByName("ideaModule")
                ?: tasks.findByName("eclipseClasspath")
            
            importTask?.dependsOn(installJar)
            
            tasks.findByName("compileJava")?.dependsOn(installJar)
            tasks.findByName("compileTestJava")?.dependsOn(installJar)
            tasks.findByName("compileKotlin")?.dependsOn(installJar)
            tasks.findByName("compileTestKotlin")?.dependsOn(installJar)
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
