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

publishing {
    repositories { mavenLocal() }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "origami"
        }
    }
}