plugins {
    id("java")
    `maven-publish`
    alias(libs.plugins.kotlin)
}

dependencies {
    implementation(libs.accesswidener)
    implementation(libs.mixin)
    implementation(libs.mixinextras)
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    implementation(libs.bundles.asm)
    implementation(libs.bundles.kotlin)
    compileOnly(project(":origami-loader"))
}

sourceSets.main { java.setSrcDirs(listOf("src/main/kotlin/")) }

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "origami"
        }
    }
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