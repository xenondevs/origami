plugins {
    id("origami.java-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    compileOnly(libs.bundles.asm) // shipped by minecraft server
}