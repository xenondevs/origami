plugins {
    id("origami.kotlin-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.accesswidener)
    api(libs.mixin)
    api(libs.mixinextras)
    api(libs.snakeyaml)
    api(libs.bundles.asm)
    
    testImplementation(project(":origami-injectables"))
}