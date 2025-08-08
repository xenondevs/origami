package xyz.xenondevs.origami.value

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import xyz.xenondevs.origami.util.GSON
import java.nio.file.FileSystems
import kotlin.io.path.reader

data class MavenDependency(val url: String, val coordinates: List<String>)
data class MavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
) {
    fun toDependencyString(): String {
        return if (classifier != null) "$group:$name:$version:$classifier" else "$group:$name:$version"
    }
}
data class MavenRepo(
    val url: String,
    val name: String,
    val groups: List<String>
)

class DevBundle(
    val minecraftVersion: String,
    val mache: MavenDependency,
    val patchDir: String,
    val reobfMappingsFile: String,
    val mojangMappedPaperclipFile: String,
    val libraryRepositories: List<String>,
    val pluginRemapArgs: List<String>,
)

abstract class DevBundleValueSource : ValueSource<DevBundle, DevBundleValueSource.Parameters> {
    
    interface Parameters : ValueSourceParameters {
        @InputFile
        fun getZip(): RegularFileProperty
    }
    
    override fun obtain(): DevBundle? {
        val bundleZip = parameters.getZip().asFile.get()
        FileSystems.newFileSystem(bundleZip.toPath()).use { fs ->
            fs.getPath("/config.json").reader().use {
                return GSON.fromJson(it, DevBundle::class.java)
            }
        }
    }
    
}