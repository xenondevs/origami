plugins {
    id("origami.kotlin-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    implementation(project(":origami"))
    implementation(libs.joptsimple)
    compileOnly(project(":origami-jit-loader"))
}