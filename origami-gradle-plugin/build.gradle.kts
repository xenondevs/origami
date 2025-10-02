plugins {
    `kotlin-dsl`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.gson)
    implementation(libs.accesswidener)
    implementation(libs.diffpatch)
    implementation(libs.asm)
    implementation(libs.javaparser)
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

java {
    withSourcesJar()
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

publishing {
    repositories {
        maven {
            credentials {
                name = "xenondevs"
                url = uri { "https://repo.xenondevs.xyz/releases/" }
                credentials(PasswordCredentials::class)
            }
        }
    }
}

tasks.register("generateVersionFile") {
    val versionFile = layout.buildDirectory.file("generatedResources/xyz.xenondevs.origami.version").get().asFile
    inputs.property("projectVersion", project.version.toString())
    outputs.file(versionFile)
    
    doLast {
        versionFile.parentFile.mkdirs()
        versionFile.writeText(project.version.toString())
    }
}

tasks.named<ProcessResources>("processResources") {
    from(tasks.named("generateVersionFile"))
}
