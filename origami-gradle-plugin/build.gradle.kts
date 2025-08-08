plugins {
    `kotlin-dsl`
    `maven-publish`
}

repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation(gradleKotlinDsl())
    implementation(libs.kotlin.stdlib)
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
    val versionFile = layout.buildDirectory.file("resources/main/version").get().asFile
    doLast {
        versionFile.parentFile.mkdirs()
        versionFile.writeText(project.version.toString())
    }
}

tasks.named("processResources") {
    dependsOn("generateVersionFile")
}