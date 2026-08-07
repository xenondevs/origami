package xyz.xenondevs.origami

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.repositories

internal fun Project.registerConfigurations() {
    configurations.register(DEV_BUNDLE_CONFIG) {
        attributes.attribute(
            Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
            objects.named("zip")
        )
    }
    configurations.register(DEV_BUNDLE_COMPILE_CLASSPATH) {
        attributes {
            attribute(
                Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                objects.named("serverDependencies")
            )
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        }
    }
    configurations.register(DEV_BUNDLE_RUNTIME_CLASSPATH) {
        attributes {
            attribute(
                Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                objects.named("serverDependencies")
            )
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        }
    }
    configurations.register(MACHE_CONFIG) {
        attributes.attribute(
            Attribute.of("io.papermc.mache.output", Named::class.java),
            objects.named("zip")
        )
    }
    configurations.register(CODEBOOK_CONFIG) { isTransitive = false }
    configurations.register(PARAM_MAPPINGS_CONFIG) { isTransitive = false }
    configurations.register(CONSTANTS_CONFIG) { isTransitive = false }
    configurations.register(REMAPPER_CONFIG) { isTransitive = false }
    configurations.register(DECOMPILER_CONFIG) { isTransitive = false }
    
    configurations.register(ORIGAMI_JIT_CONFIG)
    dependencies.addProvider(
        ORIGAMI_JIT_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-jit:${OrigamiPlugin.version}" }
    )
    configurations.register(ORIGAMI_JIT_LOADER_CONFIG)
    dependencies.addProvider(
        ORIGAMI_JIT_LOADER_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-jit-loader:${OrigamiPlugin.version}" }
    )
    configurations.register(ORIGAMI_JIT_INJECTABLES_CONFIG)
    dependencies.addProvider(
        ORIGAMI_JIT_INJECTABLES_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-injectables-jit:${OrigamiPlugin.version}" }
    )
    configurations.register(ORIGAMI_AOT_PATCHER_CONFIG)
    dependencies.addProvider(
        ORIGAMI_AOT_PATCHER_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-aot:${OrigamiPlugin.version}" }
    )
    configurations.register(ORIGAMI_AOT_INJECTABLES_CONFIG)
    dependencies.addProvider(
        ORIGAMI_AOT_INJECTABLES_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-injectables-aot:${OrigamiPlugin.version}" }
    )
    configurations.register(ORIGAMI_AOT_PLUGIN_CONFIG)
    dependencies.addProvider(
        ORIGAMI_AOT_PLUGIN_CONFIG,
        providers.provider { "xyz.xenondevs.origami:origami-aot-plugin:${OrigamiPlugin.version}" }
    )
    
    repositories {
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                onlyForConfigurations(DEV_BUNDLE_CONFIG, DEV_BUNDLE_COMPILE_CLASSPATH, MACHE_CONFIG, ORIGAMI_AOT_PLUGIN_CONFIG)
            }
        }
        maven("https://maven.fabricmc.net/") {
            content {
                onlyForConfigurations(ORIGAMI_JIT_CONFIG, ORIGAMI_JIT_INJECTABLES_CONFIG, ORIGAMI_AOT_INJECTABLES_CONFIG)
            }
        }
    }
}
