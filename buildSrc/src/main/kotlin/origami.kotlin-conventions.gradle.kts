import org.gradle.api.tasks.testing.Test

plugins {
    id("origami.java-conventions")
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.matching { it.name == "kotlinSourcesJar" }.configureEach { enabled = false }