package xyz.xenondevs.origami.util

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.setProperty
import java.io.File
import java.nio.file.Path

internal inline fun <reified T : Any> ObjectFactory.providerSet(
    vararg providers: Provider<out T>
): Provider<Set<T>> = combinedProvider(setProperty<T>(), { set, item -> set + item }, *providers)

internal fun <T : Any, C : Collection<T>> combinedProvider(
    initial: Provider<C>,
    append: (C, T) -> C,
    vararg providers: Provider<out T>
): Provider<C> = providers.fold(initial) { acc, provider -> acc.zip(provider) { col, add -> append(col, add) } }

internal fun RegularFileProperty.getAsPath(): Path =
    get().asFile.toPath()

internal val RegularFile.asPath: Path
    get() = asFile.toPath()

internal fun Provider<File>.toRegular(layout: ProjectLayout): Provider<RegularFile> =
    layout.file(this)

internal fun Configuration.singleModuleCoordinates(): Provider<String> =
    incoming.artifacts.resolvedArtifacts.map { artifacts ->
        val id = artifacts.single().id.componentIdentifier as ModuleComponentIdentifier
        "${id.group}:${id.module}:${id.version}"
    }

internal fun Configuration.singleRegularFile(
    layout: ProjectLayout,
    optional: Boolean = false
): Provider<RegularFile> {
    val files = elements
    return (if (optional) files.filter { it.isNotEmpty() } else files)
        .map { it.single().asFile }
        .toRegular(layout)
}
