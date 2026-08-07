plugins {
    id("origami.java-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    implementation(project(":origami-injectables"))
    
    // access provided through PatchingClassLoader
    compileOnly(libs.mixin)
    
    // shipped by minecraft server
    compileOnly(libs.slf4j)
    compileOnly(libs.bundles.asm)
}