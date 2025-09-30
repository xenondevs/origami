fun RepositoryHandler.configureRepos() {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

repositories { configureRepos() }

plugins {
    alias(libs.plugins.kotlin) apply false
}

subprojects {
    group = "xyz.xenondevs.origami"
    version = "0.2.1"
    
    repositories { configureRepos() }
    
    tasks {
        withType<JavaCompile> {
            sourceCompatibility = "21"
            targetCompatibility = "21"
        }
    }
    
}