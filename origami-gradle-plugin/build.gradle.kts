import xyz.xenondevs.origami.task.GenerateVersionFile

plugins {
    `kotlin-dsl`
    id("origami.kotlin-conventions")
    id("origami.publish-conventions")
}

dependencies {
    implementation(project(":origami"))
    implementation(libs.gson)
    implementation(libs.commons.gson)
    implementation(libs.accesswidener)
    implementation(libs.diffpatch)
    implementation(libs.asm)
    implementation(libs.javaparser)
    testImplementation(gradleTestKit())
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))

tasks.named("check") {
    dependsOn(tasks.named("validatePlugins"))
}

gradlePlugin {
    plugins {
        create("origami") {
            version = project.version
            id = "xyz.xenondevs.origami"
            implementationClass = "xyz.xenondevs.origami.OrigamiPlugin"
        }
    }
}

tasks.register<GenerateVersionFile>("generateVersionFile") {
    versionText.set(version.toString())
    outputFile.set(layout.buildDirectory.file("generatedResources/xyz.xenondevs.origami.version"))
}

tasks.named<ProcessResources>("processResources") {
    from(tasks.named("generateVersionFile"))
    from(project(":origami-aot-plugin").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("xyz/xenondevs/origami")
        rename { "aot-helper.jar" }
    }
}
