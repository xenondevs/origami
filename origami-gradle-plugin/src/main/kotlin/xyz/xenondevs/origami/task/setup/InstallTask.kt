package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import xyz.xenondevs.origami.util.TinyMavenRepo
import javax.inject.Inject

private const val PATCHED_SERVER_GROUP = "xyz.xenondevs.origami.patched-server"

// TODO properly cache this
abstract class InstallTask(objects: ObjectFactory) : DefaultTask() {
    
    @get:Internal
    val localRepo: Property<TinyMavenRepo> = objects.property()
    
    @get:Input
    val name: Property<String> = objects.property()
    
    @get:Input
    val version: Property<String> = objects.property()
    
    abstract class Pom @Inject constructor(objects: ObjectFactory) : InstallTask(objects) {
        
        @get:Internal
        val paperClasspathConfig: Property<Configuration> = objects.property()
        
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
            
            val deps = root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
            
            localRepo.get().installPom(
                PATCHED_SERVER_GROUP,
                name.get(),
                version.get(),
                deps
            )
        }
        
    }
    
    abstract class Jar @Inject constructor(objects: ObjectFactory) : InstallTask(objects) {
        
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val jar: RegularFileProperty = objects.fileProperty()
        
        @TaskAction
        fun run() {
            localRepo.get().installJarArtifact(
                PATCHED_SERVER_GROUP,
                name.get(),
                version.get(),
                null,
                jar.get().asFile
            )
        }
        
    }
    
    abstract class SourcesJar @Inject constructor(objects: ObjectFactory) : InstallTask(objects) {
        
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val sourcesJar: RegularFileProperty = objects.fileProperty()
        
        @TaskAction
        fun run() {
            localRepo.get().installJarArtifact(
                PATCHED_SERVER_GROUP,
                name.get(),
                version.get(),
                "sources",
                sourcesJar.get().asFile
            )
        }
        
    }
    
}