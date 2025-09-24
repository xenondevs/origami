import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.getByName
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiMarkerTask

fun Jar.addOrigamiJson() {
    val prepMarker = project.tasks.getByName<PrepareOrigamiMarkerTask>("_oriPrepareMarker")
    from(prepMarker.jsonOutput)
}