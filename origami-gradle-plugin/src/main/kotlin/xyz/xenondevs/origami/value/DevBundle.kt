package xyz.xenondevs.origami.value

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import xyz.xenondevs.origami.util.GSON
import java.io.File
import java.nio.file.FileSystems
import kotlin.io.path.reader

internal data class MavenDependency(val url: String, val coordinates: List<String>)

internal data class MavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
) {
    fun toDependencyString(): String {
        return if (classifier != null) "$group:$name:$version:$classifier" else "$group:$name:$version"
    }
}

internal data class MavenRepo(
    val url: String,
    val name: String,
    val groups: List<String>
)

internal data class DevBundle(
    val minecraftVersion: String,
    val mache: MavenDependency,
    val patchDir: String,
    val reobfMappingsFile: String?,
    val mojangMappedPaperclipFile: String,
    val libraryRepositories: List<String>,
    val pluginRemapArgs: List<String>,
) {
    
    internal companion object {
        
        fun read(bundle: File): DevBundle =
            FileSystems.newFileSystem(bundle.toPath()).use { fs ->
                fs.getPath("/config.json")
                    .reader()
                    .use { GSON.fromJson(it, DevBundle::class.java) }
            }
        
    }
    
}

internal abstract class DevBundleValueSource : ValueSource<DevBundle, DevBundleValueSource.Parameters> {
    
    interface Parameters : ValueSourceParameters {
        @get:InputFile
        val zip: RegularFileProperty
    }
    
    override fun obtain(): DevBundle = DevBundle.read(parameters.zip.asFile.get())
    
}
