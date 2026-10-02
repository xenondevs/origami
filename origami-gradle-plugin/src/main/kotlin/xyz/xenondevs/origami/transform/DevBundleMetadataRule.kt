package xyz.xenondevs.origami.transform

import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.api.attributes.Usage
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

internal const val SERVER_BASE_ARTIFACT_TYPE = "origami-server-base"
internal const val ORIGAMI_CLASSPATH_ARTIFACT_TYPE = "origami-classpath"
internal const val SOURCE_BASE_ARTIFACT_TYPE = "origami-source-base"
internal const val ORIGAMI_SETUP_BUNDLING = "xyz.xenondevs.origami.setup"
internal val ORIGAMI_SETUP_INPUT = Attribute.of("xyz.xenondevs.origami.setup-input", Boolean::class.javaObjectType)

/**
 * Makes the dev-bundle ZIP available as the starting point for both server setup paths (binaries + sources).
 * These variants combine the dev-bundle zip with the actual compile / runtime classpath dependencies.
 */
@CacheableRule
internal abstract class DevBundleMetadataRule @Inject constructor(
    private val objects: ObjectFactory,
) : ComponentMetadataRule {
    
    override fun execute(context: ComponentMetadataContext) {
        val details = context.details
        val zipName = "${details.id.name}-${details.id.version}.zip"
        
        // mark Paper's variants as not the input
        for (variant in listOf("devBundle", "serverCompileClasspath", "serverRuntimeClasspath")) {
            details.withVariant(variant) {
                attributes {
                    attribute(ORIGAMI_SETUP_INPUT, false)
                }
            }
        }
        
        // setup input variant for compile- and runtime
        for ((variant, base) in listOf(
            "origamiServerBinaryInput" to "serverCompileClasspath",
            "origamiServerRuntimeBinaryInput" to "serverRuntimeClasspath",
        )) {
            details.maybeAddVariant(variant, base) {
                attributes {
                    attribute(ORIGAMI_SETUP_INPUT, true)
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                }
                withFiles {
                    addFile(zipName)
                }
            }
        }
        
        // setup input variant for sources
        details.maybeAddVariant("origamiServerSourcesInput", "serverCompileClasspath") {
            attributes {
                attribute(ORIGAMI_SETUP_INPUT, true)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.DOCUMENTATION))
                attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, DocsType.SOURCES))
            }
            withFiles {
                addFile(zipName)
            }
        }
    }
    
}

/**
 * Selects Origami's `setup-input` variant ONLY when explicitly requested.
 */
internal abstract class SetupInputDisambiguationRule : AttributeDisambiguationRule<Boolean> {
    
    override fun execute(details: MultipleCandidatesDetails<Boolean>) {
        val preferred = details.consumerValue == true
        if (preferred in details.candidateValues) {
            details.closestMatch(preferred)
        }
    }
    
}