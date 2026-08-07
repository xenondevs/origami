package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.task.run.ExtractPatchInputs
import xyz.xenondevs.origami.task.run.InvalidateAotCache
import xyz.xenondevs.origami.task.run.PatchRunServerJar
import xyz.xenondevs.origami.task.run.RunServer
import xyz.xenondevs.origami.task.setup.ApplyBinDiffTask
import xyz.xenondevs.origami.value.ListArgumentProvider

fun Project.registerRunTasks(plugin: OrigamiPlugin) {
    val ext = extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION).runServer
    val applyBinDiff = tasks.named<ApplyBinDiffTask>("_oriApplyBinDiff")
    
    val extractPatchInputs = tasks.register<ExtractPatchInputs>("_oriPrepareMixins") {
        plugins.from(ext.plugins)
        outputDir.set(layout.buildDirectory.dir("origami/patch-fingerprint"))
    }
    
    val patchRunServerJar = tasks.register<PatchRunServerJar>("_oriMixin") {
        dependsOn(applyBinDiff)
        
        javaLauncher.set(plugin.javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        setClasspath(configurations.getByName(ORIGAMI_AOT_PATCHER_CONFIG))
        
        serverJar.set(applyBinDiff.flatMap(ApplyBinDiffTask::patchedJar))
        outputJar.set(layout.buildDirectory.file("origami/paper-server-patched.jar"))
        plugins.from(ext.plugins)
        patchFingerprint.set(extractPatchInputs.flatMap { it.outputDir })
        serverClasspath.from(configurations.named(DEV_BUNDLE_COMPILE_CLASSPATH))
        serverClasspath.from(configurations.named(ORIGAMI_AOT_INJECTABLES_CONFIG))
    }
    
    val serverLauncher = ext.javaLauncher.orElse(
        plugin.javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) }
    )
    
    fun RunServer.configure() {
        group = ORIGAMI_TASK_GROUP
        
        javaLauncher.set(ext.javaLauncher.orElse(serverLauncher))
        workingDir(ext.workingDirectory.orElse(project.layout.buildDirectory.dir("origami/server")))
        jvmArguments.addAll(ext.jvmArgs)
        argumentProviders.add(objects.newInstance<ListArgumentProvider>().apply { args.set(ext.args) })
        args("--nogui")
        standardInput = System.`in`
        
        classpath(patchRunServerJar.flatMap { it.outputJar })
        classpath(configurations.named(DEV_BUNDLE_RUNTIME_CLASSPATH))
        classpath(configurations.named(ORIGAMI_AOT_INJECTABLES_CONFIG))
        classpath(ext.classpath)
        
        mainClass.set(ext.mainClass.orElse("org.bukkit.craftbukkit.Main"))
        
        plugins.from(configurations.named(ORIGAMI_AOT_PLUGIN_CONFIG))
        plugins.from(ext.plugins)
    }
    
    tasks.register<RunServer>("runOrigamiServer") {
        useAotCache = false
        configure()
    }
    
    val invalidateAotCache = tasks.register<InvalidateAotCache>("_oriCheckAotCache") {
        patchFingerprint.set(extractPatchInputs.flatMap { it.outputDir })
        classpath.from(patchRunServerJar.flatMap { it.outputJar })
        classpath.from(configurations.named(DEV_BUNDLE_RUNTIME_CLASSPATH))
        classpath.from(configurations.named(ORIGAMI_AOT_INJECTABLES_CONFIG))
        classpath.from(ext.classpath)
        marker.set(projectLayout.buildDirectory.file("origami/aot-invalidation-marker"))
    }
    
    tasks.register<RunServer>("runOrigamiServerAot") {
        useAotCache = true
        dependsOn(invalidateAotCache)
        configure()
    }
    
}