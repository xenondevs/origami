package xyz.xenondevs.origami.extension

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import xyz.xenondevs.origami.OrigamiPlugin
import xyz.xenondevs.origami.task.setup.InstallTask

abstract class OrigamiDependenciesExtension(
    private val project: Project,
    private val plugin: OrigamiPlugin
) {
    
    fun patchedPaperServer(): Provider<ExternalModuleDependency> {
        val installTask = project.tasks.named<InstallTask>("_oriInstallJar")
        
        val repo = project.repositories.maven(plugin.localRepo.folder.get().asFile.toPath()) {
            content { includeGroup("xyz.xenondevs.origami.patched-server") }
        }
        project.repositories.remove(repo)
        project.repositories.add(0, repo)
        
        val notation = installTask.flatMap {
            it.name.zip(it.version) { artifact, version -> "xyz.xenondevs.origami.patched-server:$artifact:$version" }
        }
        val dep = notation.map {
            (project.dependencies.create(it) as ExternalModuleDependency)
        }
        
        return dep
    }
    
}