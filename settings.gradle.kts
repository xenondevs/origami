rootProject.name = "origami-parent"

include("origami")
include("origami-loader")
include("origami-gradle-plugin")

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

include("origami-catalog")