plugins {
    id("origami.kotlin-conventions")
    id("origami.publish-conventions-java")
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