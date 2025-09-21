plugins {
    `maven-publish`
    `version-catalog`
}

catalog {
    versionCatalog {
        version("mixin", libs.versions.mixin.get())
        version("mixinextras", libs.versions.mixinextras.get())
        version("origami", project.version.toString())
        
        plugin("origami", "xyz.xenondevs.origami").versionRef("origami")
        
        library("origami-plugin", "xyz.xenondevs.origami", "origami-gradle-plugin").versionRef("origami")
        library("origami-api", "xyz.xenondevs.origami", "origami-api").versionRef("origami")
        library("mixin", "net.fabricmc", "sponge-mixin").versionRef("mixin")
        library("mixinextras", "io.github.llamalad7", "mixinextras-common").versionRef("mixinextras")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["versionCatalog"])
        }
    }
    
    repositories {
        maven {
            credentials {
                name = "xenondevs"
                url = uri { "https://repo.xenondevs.xyz/releases/" }
                credentials(PasswordCredentials::class)
            }
        }
    }
}