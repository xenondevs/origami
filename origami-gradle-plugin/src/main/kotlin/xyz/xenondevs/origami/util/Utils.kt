package xyz.xenondevs.origami.util

import org.gradle.StartParameter
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.kotlin.dsl.setProperty
import xyz.xenondevs.origami.task.setup.InstallTask

internal inline fun <reified T : Any> ObjectFactory.providerSet(
    vararg providers: Provider<out T>
): Provider<Set<T>> = combinedProvider(setProperty<T>(), { set, item -> set + item}, *providers)

internal fun <T, C : Collection<T>> combinedProvider(
    initial: Provider<C>,
    append: (C, T) -> C,
    vararg providers: Provider<out T>
): Provider<C> = providers.fold(initial) { acc, provider -> acc.zip(provider) { col, add -> append(col, add) } }

internal fun prependTaskRequest(start: StartParameter, task: TaskProvider<*>) {
    start.setTaskRequests(buildList { 
        add(DefaultTaskExecutionRequest(listOf(task.name)))
        addAll(start.taskRequests)
    })
}

internal fun isIdeaSync(): Boolean =
    java.lang.Boolean.getBoolean("idea.sync.active")

internal fun getIdeaSourcesDownloadTasks(project: Project, sources: InstallTask.Artifact): List<Task> {
    val downloadsSources = project.configurations.asSequence()
        .filter { it.name.startsWith("downloadArtifact_") }
        .flatMap { it.dependencies }
        .filterIsInstance<ExternalModuleDependency>()
        .flatMap { dependency -> dependency.artifacts.asSequence().map { artifact -> dependency to artifact } }
        .any { (dependency, artifact) -> 
            dependency.group == sources.group.get() 
                && dependency.name == sources.name.get() 
                && artifact.extension == sources.extension.get() 
                && artifact.classifier == sources.classifier.get() 
                && dependency.version == sources.version.get()
        }
    
    if (downloadsSources)
        return project.tasks.filter { it.name.startsWith("ijDownloadArtifact")}
    
    return emptyList()
}