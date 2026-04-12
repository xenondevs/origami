# origami
Origami is a mixin loader and access widener for Paper that can be used in plugins.

## Limitations

Since plugin and Minecraft classes are loaded by different classloaders, Mixins cannot directly reference addon classes. Origami solves this problem by replacing calls to your addon's classes with invokedynamic instructions that will then link to the correct class at runtime. Because of this, some Mixin features are currently not supported or may not work correctly. Most notably, injecting interfaces will not work.

## Usage

### Step 1: Add the origami catalog to your project

settings.gradle.kts:
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.xenondevs.xyz/releases/")
    }
    
    versionCatalogs {
        create("origamiLibs") {
            from("xyz.xenondevs.origami:origami-catalog:<VERSION>")
        }
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.xenondevs.xyz/releases/")
    }
}
```

### Step 2: Add and configure the origami plugin

build.gradle.kts
```kotlin
plugins {
    java
    alias(origamiLibs.plugins.origami)
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.xenondevs.xyz/releases/")
}

dependencies {
    compileOnly(origamiLibs.mixin)
    compileOnly(origamiLibs.mixinextras)
}

origami {
    devBundleVersion = "26.1.+"
    // (dev bundle coordinates can be changed via devBundleGroup, devBundleArtifact)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

### Step 3: Create access wideners and mixins

#### Step 3.1: Access wideners

To add access wideners, add a `<name>.aw` or `<name>.accesswidener` file to your `resources` folder:

```accesswidener
accessWidener v2 named

# Access wideners here
```

#### Step 3.2: Mixins

First, add a `<name>.mixins.json` file to your `resources` folder with the following content:
```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "org.example.mixin", // path to your mixin package
  "mixins": [],
  "mixinextras": {
    "minVersion": "0.5.0"
  }
}
```

Now, you can create mixins as usual.

### Step 4: Build `origamiJar`

To build, run the `origamiJar` gradle task.


If you're using the gradle shadow plugin, you'll first need to change the input of the `origamiJar` task to use the shadow jar:
```kotlin 
origami {
    input = copySpec {
        from(tasks.shadowJar.flatMap { shadowJar -> shadowJar.archiveFile.map { jar -> zipTree(jar) } }) 
    }
}
```

### Step 5: Run server

Start the server with `javaagent:plugins/<Path to your plugin>.jar` as a JVM argument.