plugins {
    id("origami.java-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.91-stable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}
