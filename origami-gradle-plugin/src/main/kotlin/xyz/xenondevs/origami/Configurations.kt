package xyz.xenondevs.origami

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.repositories

internal fun Project.registerConfigurations() {
    configurations.apply {
        register(DEV_BUNDLE_CONFIG) {
            attributes.attribute(
                Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                objects.named("zip")
            )
        }
        register(DEV_BUNDLE_COMPILE_CLASSPATH) {
            attributes {
                attribute(
                    Attribute.of("io.papermc.paperweight.dev-bundle-output", Named::class.java),
                    objects.named("serverDependencies")
                )
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            }
        }
        register(MACHE_CONFIG) {
            attributes.attribute(
                Attribute.of("io.papermc.mache.output", Named::class.java),
                objects.named("zip")
            )
        }
        register(CODEBOOK_CONFIG) { isTransitive = false }
        register(PARCHMENT_CONFIG) { isTransitive = false }
        register(REMAPPER_CONFIG) { isTransitive = false }
        register(DECOMPILER_CONFIG) { isTransitive = false }
        
        register(ORIGAMI_CONFIG) {
            isVisible = false
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_RUNTIME)
            )
        }
        
        register(ORIGAMI_LOADER_CONFIG) {
            isVisible = false
            isTransitive = false
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_RUNTIME)
            )
        }
    }
    
    repositories {
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                onlyForConfigurations(DEV_BUNDLE_CONFIG, DEV_BUNDLE_COMPILE_CLASSPATH, MACHE_CONFIG, ORIGAMI_CONFIG)
            }
        }
        maven("https://maven.fabricmc.net/") {
            content {
                onlyForConfigurations(ORIGAMI_CONFIG)
            }
        }
    }
}