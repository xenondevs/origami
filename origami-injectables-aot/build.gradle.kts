plugins {
    id("origami.java-conventions")
    id("origami.publish-conventions-java")
}

dependencies {
    implementation(project(":origami-injectables"))
    implementation(libs.mixin) // for classes like CallbackInfoReturnable
    implementation(libs.mixinextras) // for classes like LocalRef
}