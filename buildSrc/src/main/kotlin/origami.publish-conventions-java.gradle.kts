plugins {
    id("origami.java-conventions")
    id("origami.publish-conventions")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}