package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.task.run.ExtractPatchInputs
import xyz.xenondevs.origami.task.run.GenerateAotCacheFingerprint
import xyz.xenondevs.origami.task.run.PatchRunServerJar
import xyz.xenondevs.origami.task.run.RunServer
import xyz.xenondevs.origami.util.singleRegularFile
import xyz.xenondevs.origami.value.ListArgumentProvider

internal fun Project.registerRunTasks(plugin: OrigamiPlugin, configs: OrigamiConfigurations) {
    val ext = extensions.getByName<OrigamiExtension>(ORIGAMI_EXTENSION).runServer
    val extractPatchInputs = tasks.register<ExtractPatchInputs>("_oriPrepareMixins") {
        plugins.from(ext.plugins)
        outputDir.set(layout.buildDirectory.dir("origami/patch-fingerprint"))
    }
    
    val patchRunServerJar = tasks.register<PatchRunServerJar>("_oriMixin") {
        javaLauncher.set(plugin.javaLauncherFor(project))
        setClasspath(configs.aotPatcher)
        
        serverJar.set(configs.widenedServer.singleRegularFile(layout))
        outputJar.set(layout.buildDirectory.file("origami/paper-server-mixin-patched.jar"))
        plugins.from(ext.plugins)
        patchFingerprint.set(extractPatchInputs.flatMap { it.outputDir })
        serverClasspath.from(configs.devBundleCompileClasspath)
        serverClasspath.from(configs.aotInjectables)
    }
    
    val serverLauncher = ext.javaLauncher.orElse(plugin.javaLauncherFor(26))
    
    val serverWorkingDirectory = ext.workingDirectory.orElse(project.layout.buildDirectory.dir("origami/server"))
    val serverMainClass = ext.mainClass.orElse("org.bukkit.craftbukkit.Main")
    
    fun RunServer.configure() {
        group = ORIGAMI_TASK_GROUP
        
        javaLauncher.set(ext.javaLauncher.orElse(serverLauncher))
        workingDir(serverWorkingDirectory)
        jvmArguments.addAll(ext.jvmArgs)
        argumentProviders.add(objects.newInstance<ListArgumentProvider>().apply { args.set(ext.args) })
        args("--nogui")
        standardInput = System.`in`
        
        classpath(patchRunServerJar.flatMap { it.outputJar })
        classpath(configs.devBundleRuntimeClasspath)
        classpath(configs.aotInjectables)
        classpath(ext.classpath)
        
        mainClass.set(serverMainClass)
        
        plugins.from(configs.aotPlugin)
        plugins.from(ext.plugins)
    }
    
    tasks.register<RunServer>("runOrigamiServer") {
        useAotCache = false
        configure()
    }
    
    val generateAotCacheFingerprint = tasks.register<GenerateAotCacheFingerprint>("_oriGenerateAotCacheFingerprint") {
        patchFingerprint.set(extractPatchInputs.flatMap { it.outputDir })
        classpath.from(patchRunServerJar.flatMap { it.outputJar })
        classpath.from(configs.devBundleRuntimeClasspath)
        classpath.from(configs.aotInjectables)
        classpath.from(ext.classpath)
        plugins.from(configs.aotPlugin)
        plugins.from(ext.plugins)
        javaReleaseFile.set(serverLauncher.map { it.metadata.installationPath.file("release") })
        jvmArgs.set(ext.jvmArgs)
        args.set(ext.args)
        args.add("--nogui")
        mainClass.set(serverMainClass)
        workingDirectory.set(serverWorkingDirectory.map { it.asFile.absolutePath })
        outputFile.set(project.layout.buildDirectory.file("origami/aot-cache-fingerprint"))
    }
    
    tasks.register<RunServer>("runOrigamiServerAot") {
        useAotCache = true
        aotCacheFingerprint.set(generateAotCacheFingerprint.flatMap { it.outputFile })
        configure()
    }
    
}
