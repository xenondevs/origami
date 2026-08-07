rootProject.name = "origami-parent"

// core
include("origami")
include("origami-injectables")
include("origami-api")

// AOT patching
include("origami-aot")
include("origami-injectables-aot")
include("origami-aot-plugin")

// JIT patching
include("origami-jit")
include("origami-injectables-jit")
include("origami-jit-loader")

// gradle plugin
include("origami-catalog")
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
