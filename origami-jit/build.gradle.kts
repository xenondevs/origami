plugins {
    id("origami.kotlin-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    implementation(project(":origami"))
    compileOnly(project(":origami-jit-loader"))
}