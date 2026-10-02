package xyz.xenondevs.origami.transform.action

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Keeps ordinary classpath artifacts unchanged while giving them the artifact type requested by Origami classpaths.
 */
@CacheableTransform
internal abstract class PreserveClasspathArtifactTransformAction : TransformAction<TransformParameters.None> {
    
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get()
        if (!input.asFile.exists()) {
            outputs.dir("empty").mkdirs()
        } else if (input.asFile.isDirectory) {
            outputs.dir(input)
        } else {
            outputs.file(input)
        }
    }
    
}