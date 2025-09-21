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
    version = "0.1.3"
    
    repositories { configureRepos() }
    
    tasks {
        withType<JavaCompile> {
            sourceCompatibility = "21"
            targetCompatibility = "21"
        }
    }
    
}