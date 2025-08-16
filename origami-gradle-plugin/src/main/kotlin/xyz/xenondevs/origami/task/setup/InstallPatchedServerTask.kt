package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.origami.util.TinyMavenRepo

// TODO properly cache this
abstract class InstallPatchedServerTask : DefaultTask() {
    
    @get:Internal
    abstract val localRepo: Property<TinyMavenRepo>
    
    @get:Internal
    abstract val paperClasspathConfig: Property<Configuration>
    
    @get:Input
    abstract val artifact: Property<String>
    
    @get:Input
    abstract val version: Property<String>
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classesJar: RegularFileProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty
    
    @TaskAction
    fun run() {
        val root = paperClasspathConfig.get()
            .incoming
            .resolutionResult
            .root
            .dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .single()
            .selected
        val repo = localRepo.get()
        val classesFile = classesJar.get().asFile.toPath()
        val sourcesFile = sourcesJar.get().asFile.toPath()
        
        val deps = root.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
            .map { id -> "${id.group}:${id.module}:${id.version}" }
        
        repo.installArtifact(
            "xyz.xenondevs.origami.patched-server",
            artifact.get(),
            version.get(),
            classesFile,
            deps,
            sourcesFile
        )
    }
    
}