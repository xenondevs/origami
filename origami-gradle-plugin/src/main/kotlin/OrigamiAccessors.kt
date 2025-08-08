import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.getByType
import xyz.xenondevs.origami.extension.OrigamiJarExtension

fun Jar.addOrigamiLoader(librariesFolder: String = "libs") {
    extensions.getByType<OrigamiJarExtension>().addOrigamiLoader(librariesFolder)
}

fun Jar.addOrigamiJson() {
    extensions.getByType<OrigamiJarExtension>().addOrigamiJson()
}