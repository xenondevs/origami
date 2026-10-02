package xyz.xenondevs.origami

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.repositories
import xyz.xenondevs.origami.transform.ORIGAMI_CLASSPATH_ARTIFACT_TYPE
import xyz.xenondevs.origami.transform.ORIGAMI_SETUP_INPUT
import xyz.xenondevs.origami.value.DevBundle
import xyz.xenondevs.origami.value.MacheConfig
import xyz.xenondevs.origami.value.MacheDependencies
import xyz.xenondevs.origami.value.MavenArtifact

internal class OrigamiConfigurations(private val project: Project) {
    
    private val configurations = project.configurations
    private val dependencyFactory = project.dependencies
    
    val devBundle = configurations.detachedConfiguration().apply {
        attributes.attribute(
            Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
            project.objects.named("zip")
        )
    }
    val devBundleCompileClasspath = configurations.detachedConfiguration().apply {
        attributes {
            attribute(
                Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                project.objects.named("serverDependencies")
            )
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_API))
        }
    }
    val devBundleRuntimeClasspath = configurations.detachedConfiguration().apply {
        attributes {
            attribute(
                Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                project.objects.named("serverDependencies")
            )
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
        }
    }
    val widenedServer = configurations.detachedConfiguration().apply {
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_API))
            attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
            attribute(ORIGAMI_SETUP_INPUT, true)
            attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ORIGAMI_CLASSPATH_ARTIFACT_TYPE)
        }
    }
    
    val mache = configurations.detachedConfiguration().apply {
        attributes.attribute(
            Attribute.of("io.papermc.mache.output", Named::class.java),
            project.objects.named("zip")
        )
    }
    val codebook = configurations.detachedConfiguration().apply { isTransitive = false }
    val paramMappings = configurations.detachedConfiguration().apply { isTransitive = false }
    val constants = configurations.detachedConfiguration().apply { isTransitive = false }
    val remapper = configurations.detachedConfiguration().apply { isTransitive = false }
    val decompiler = configurations.detachedConfiguration().apply { isTransitive = false }
    
    val jit = origamiDependency("origami-jit")
    val jitInjectables = origamiDependency("origami-injectables-jit")
    val jitLoader = origamiDependency("origami-jit-loader")
    val aotPatcher = origamiDependency("origami-aot")
    val aotInjectables = origamiDependency("origami-injectables-aot")
    val aotPlugin = origamiDependency("origami-aot-plugin")
    
    init {
        configureRepositories()
    }
    
    fun configureDevBundle(dependency: Provider<Dependency>) {
        devBundle.dependencies.addLater(dependency)
        devBundleCompileClasspath.dependencies.addLater(dependency)
        devBundleRuntimeClasspath.dependencies.addLater(dependency)
        widenedServer.dependencies.addLater(dependency)
    }
    
    fun configureMache(devBundleInfo: Provider<DevBundle>, macheConfig: Provider<MacheConfig>) {
        fun Configuration.addMacheDependencies(selector: MacheDependencies.() -> List<MavenArtifact>?) {
            dependencies.addAllLater(
                macheConfig.map { config ->
                    config.dependencies.selector().orEmpty()
                        .map { dependencyFactory.create(it.toDependencyString()) }
                }
            )
        }
        
        mache.dependencies.addAllLater(
            devBundleInfo.map { it.mache.coordinates.map(dependencyFactory::create) }
        )
        codebook.addMacheDependencies { codebook }
        paramMappings.addMacheDependencies { paramMappings }
        constants.addMacheDependencies { constants }
        remapper.addMacheDependencies { remapper }
        decompiler.addMacheDependencies { decompiler }
        
        project.afterEvaluate {
            macheConfig.get().repositories.forEach { repository ->
                repositories.maven(repository.url) {
                    name = repository.name
                    content {
                        repository.groups.forEach(::includeGroupAndSubgroups)
                        onlyForConfigurations(
                            codebook.name,
                            paramMappings.name,
                            constants.name,
                            remapper.name,
                            decompiler.name
                        )
                    }
                }
            }
        }
    }
    
    private fun origamiDependency(module: String) = configurations.detachedConfiguration(
        dependencyFactory.create("xyz.xenondevs.origami:$module:${OrigamiPlugin.version}")
    )
    
    private fun configureRepositories() = project.repositories {
        maven("https://repo.xenondevs.xyz/releases/") {
            content {
                includeGroup("xyz.xenondevs.origami")
                onlyForConfigurations(
                    jit.name,
                    jitInjectables.name,
                    jitLoader.name,
                    aotPatcher.name,
                    aotInjectables.name,
                    aotPlugin.name
                )
            }
        }
        
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                onlyForConfigurations(
                    devBundle.name,
                    devBundleCompileClasspath.name,
                    devBundleRuntimeClasspath.name,
                    widenedServer.name,
                    mache.name,
                    aotPlugin.name
                )
            }
        }
        
        maven("https://maven.fabricmc.net/") {
            content {
                onlyForConfigurations(
                    jit.name,
                    jitInjectables.name,
                    aotInjectables.name
                )
            }
        }
    }
    
}
