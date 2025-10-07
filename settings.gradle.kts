rootProject.name = "origami-parent"

include("origami")
include("origami-api")
include("origami-catalog")
include("origami-gradle-plugin")
include("origami-loader")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs")
    }
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
