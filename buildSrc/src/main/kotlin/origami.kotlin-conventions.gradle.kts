plugins {
    id("origami.java-conventions")
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

tasks.matching { it.name == "kotlinSourcesJar" }.configureEach { enabled = false }