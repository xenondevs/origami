import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("origami.common-conventions")
    `java-library`
}

val libs = the<LibrariesForLibs>()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven("https://repo.xenondevs.xyz/releases/")
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceSets.main {
        java.srcDir("src/main/kotlin/")
    }
}

dependencies {
    compileOnly(libs.jspecify)
}