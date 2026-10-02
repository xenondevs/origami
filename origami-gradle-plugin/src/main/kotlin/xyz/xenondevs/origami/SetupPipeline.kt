package xyz.xenondevs.origami

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.registerTransform
import xyz.xenondevs.origami.extension.OrigamiExtension
import xyz.xenondevs.origami.transform.DevBundleMetadataRule
import xyz.xenondevs.origami.transform.ORIGAMI_CLASSPATH_ARTIFACT_TYPE
import xyz.xenondevs.origami.transform.ORIGAMI_SETUP_BUNDLING
import xyz.xenondevs.origami.transform.ORIGAMI_SETUP_INPUT
import xyz.xenondevs.origami.transform.SERVER_BASE_ARTIFACT_TYPE
import xyz.xenondevs.origami.transform.SOURCE_BASE_ARTIFACT_TYPE
import xyz.xenondevs.origami.transform.SetupInputDisambiguationRule
import xyz.xenondevs.origami.transform.action.PrepareServerTransform
import xyz.xenondevs.origami.transform.action.PrepareSourcesTransformAction
import xyz.xenondevs.origami.transform.action.PreserveClasspathArtifactTransformAction
import xyz.xenondevs.origami.transform.action.WidenBinaryTransform
import xyz.xenondevs.origami.transform.action.WidenSourcesTransform
import xyz.xenondevs.origami.util.singleModuleCoordinates
import xyz.xenondevs.origami.util.singleRegularFile
import xyz.xenondevs.origami.value.DevBundleValueSource
import xyz.xenondevs.origami.value.MacheConfigValueSource

internal fun Project.registerSetupPipeline(plugin: OrigamiPlugin, cfgs: OrigamiConfigurations) {
    val extension = extensions.getByType<OrigamiExtension>()
    configureDevBundleSetup(extension, cfgs)
    registerCleanupTask(extension)
    registerTransforms(plugin, cfgs, extension)
}

private fun Project.configureDevBundleSetup(ext: OrigamiExtension, cfgs: OrigamiConfigurations) {
    dependencies.attributesSchema.attribute(ORIGAMI_SETUP_INPUT) {
        disambiguationRules.add(SetupInputDisambiguationRule::class.java)
    }
    
    // IntelliJ requests Bundling.EXTERNAL for sources. 
    // We mark ZIP artifacts with our custom bundling attribute, which then triggers Gradle to run the source transforms.
    // This explicitly does NOT add the attribute to the VARIANT, as otherwise variant selection would discard it.
    dependencies.artifactTypes
        .maybeCreate(ArtifactTypeDefinition.ZIP_TYPE)
        .attributes
        .attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, ORIGAMI_SETUP_BUNDLING))
    
    val devBundleNotation = ext.devBundleGroup
        .zip(ext.devBundleArtifact) { g, a -> "$g:$a" }
        .zip(ext.devBundleVersion) { ga, v -> "$ga:$v" }
    val devBundleDependency = devBundleNotation.map(dependencies::create)
    cfgs.configureDevBundle(devBundleDependency)
    
    afterEvaluate {
        val notation = devBundleNotation.orNull
            ?: return@afterEvaluate
        val targets = ext.targetConfigurations.get()
        
        // add dev-bundle dependency to target configurations
        for (target in targets) {
            target.dependencies.add(dependencyFactory.create(notation))
        }
        
        // configure e.g. compileClasspath's required attributes, this triggers the artifact transforms
        configurations
            .filter { cfg -> cfg.isCanBeResolved && cfg.hierarchy.any { it in targets } }
            .forEach { cfg ->
                cfg.attributes {
                    attribute(ORIGAMI_SETUP_INPUT, true)
                    attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ORIGAMI_CLASSPATH_ARTIFACT_TYPE)
                }
            }
        
        // Explicit dependency on transitive access widener sources jar tasks
        // (Gradle does not do this automatically for some reason?)
        val setupInputProducers = files().builtBy(ext.transitiveAccessWidenerSources)
        for (target in targets + listOf(cfgs.widenedServer)) {
            target.dependencies.add(dependencies.create(setupInputProducers))
        }
        
        // DevBundleMetadataRule attaches required variants to dev-bundle
        dependencies.components.withModule(
            "${ext.devBundleGroup.get()}:${ext.devBundleArtifact.get()}",
            DevBundleMetadataRule::class.java
        )
    }
}

private fun Project.registerCleanupTask(ext: OrigamiExtension) {
    tasks.register<Delete>("_oriClean") {
        group = ORIGAMI_TASK_GROUP
        description = "Deletes Origami's system-wide shared setup cache."
        delete(ext.sharedCache)
    }
}

private fun Project.registerTransforms(
    plugin: OrigamiPlugin,
    cfgs: OrigamiConfigurations,
    ext: OrigamiExtension,
) {
    //<editor-fold desc="shared inputs">
    val macheZip = cfgs.mache.singleRegularFile(layout)
    val macheConfig = providers.of(MacheConfigValueSource::class.java) {
        parameters.zip.set(macheZip)
    }
    cfgs.configureMache(
        providers.of(DevBundleValueSource::class.java) {
            parameters.zip.set(cfgs.devBundle.singleRegularFile(layout))
        },
        macheConfig
    )
    val devBundleId = cfgs.devBundle.singleModuleCoordinates()
    
    val javaExecutable = plugin.javaLauncherFor(project)
        .map { it.executablePath.asFile.absolutePath }
    
    val declaredAccessWideners = extensions.getByType<JavaPluginExtension>()
        .sourceSets.getByName("main")
        .resources.matching { include("**/*.accesswidener", "**/*.aw") }
    //</editor-fold>
    
    val setupBundling = objects.named(Bundling::class.java, ORIGAMI_SETUP_BUNDLING)
    val externalBundling = objects.named(Bundling::class.java, Bundling.EXTERNAL)
    val libraryCategory = objects.named(Category::class.java, Category.LIBRARY)
    
    //<editor-fold desc="binary transforms">
    dependencies.registerTransform(PrepareServerTransform::class) {
        from
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
        to
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SERVER_BASE_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
        parameters {
            this.devBundleId.set(devBundleId)
            this.javaExecutable.set(javaExecutable)
            this.sharedCache.set(ext.sharedCache)
        }
    }
    
    dependencies.registerTransform(WidenBinaryTransform::class) {
        from
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SERVER_BASE_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
            .attribute(Bundling.BUNDLING_ATTRIBUTE, setupBundling)
            .attribute(Category.CATEGORY_ATTRIBUTE, libraryCategory)
        to
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ORIGAMI_CLASSPATH_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
            .attribute(Bundling.BUNDLING_ATTRIBUTE, externalBundling)
        parameters {
            this.devBundleId.set(devBundleId)
            accessWideners.from(declaredAccessWideners)
            transitiveAccessWidenerSources.from(ext.transitiveAccessWidenerSources)
        }
    }
    //</editor-fold>
    
    //<editor-fold desc="source transforms">
    val sourcesDocsType = objects.named(DocsType::class.java, DocsType.SOURCES)
    
    dependencies.registerTransform(PrepareSourcesTransformAction::class) {
        from
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SERVER_BASE_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
            .attribute(DocsType.DOCS_TYPE_ATTRIBUTE, sourcesDocsType)
        to
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SOURCE_BASE_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
        parameters {
            this.devBundleId.set(devBundleId)
            this.javaExecutable.set(javaExecutable)
            this.sharedCache.set(ext.sharedCache)
            this.macheFile.set(macheZip)
            this.codebook.set(cfgs.codebook.singleRegularFile(layout))
            this.paramMappings.set(cfgs.paramMappings.singleRegularFile(layout, optional = true))
            this.constants.set(cfgs.constants.singleRegularFile(layout, optional = true))
            this.remapper.set(cfgs.remapper.singleRegularFile(layout, optional = true))
            this.decompiler.set(cfgs.decompiler.singleRegularFile(layout))
            this.remapperArgs.set(macheConfig.map { it.remapperArgs })
            this.decompilerArgs.set(macheConfig.map { it.decompilerArgs })
        }
    }
    
    dependencies.registerTransform(WidenSourcesTransform::class) {
        from
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SOURCE_BASE_ARTIFACT_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
            .attribute(Bundling.BUNDLING_ATTRIBUTE, setupBundling)
            .attribute(DocsType.DOCS_TYPE_ATTRIBUTE, sourcesDocsType)
        to
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
            .attribute(ORIGAMI_SETUP_INPUT, true)
            .attribute(Bundling.BUNDLING_ATTRIBUTE, externalBundling)
        parameters {
            this.devBundleId.set(devBundleId)
            accessWideners.from(declaredAccessWideners)
            transitiveAccessWidenerSources.from(ext.transitiveAccessWidenerSources)
        }
    }
    //</editor-fold>
    
    //<editor-fold desc="classpath passthrough">
    for (artifactType in listOf(
        ArtifactTypeDefinition.JAR_TYPE,
        ArtifactTypeDefinition.DIRECTORY_TYPE,
        ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
        ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY,
    )) {
        dependencies.registerTransform(PreserveClasspathArtifactTransformAction::class) {
            from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType)
            to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ORIGAMI_CLASSPATH_ARTIFACT_TYPE)
        }
    }
    //</editor-fold>
}
