plugins {
    id("java")
    `maven-publish`
}

publishing {
    repositories { mavenLocal() }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "origami-loader"
        }
    }
}