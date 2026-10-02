import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.kotlin.dsl.named
import xyz.xenondevs.origami.task.packaging.PrepareOrigamiMarkerTask

/**
 * Includes the generated `origami.json` in this [AbstractCopyTask].
 */
fun AbstractCopyTask.addOrigamiJson() {
    from(project.tasks.named<PrepareOrigamiMarkerTask>("_oriPrepareMarker").flatMap { it.jsonOutput })
}