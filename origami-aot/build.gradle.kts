plugins {
    application
    id("origami.kotlin-conventions")
    id("origami.publish-conventions-java")
}

application {
    mainClass = "xyz.xenondevs.origami.aot.OrigamiAot"
}

dependencies {
    implementation(project(":origami"))
    implementation(libs.slf4j)
    implementation(libs.logback.classic)
}