package xyz.xenondevs.origami.value

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import xyz.xenondevs.origami.util.GSON
import java.nio.file.FileSystems
import kotlin.io.path.reader

data class MacheConfig(
    val minecraftVersion: String,
    val includesClientPatches: Boolean,
    val macheVersion: String,
    val dependencies: MacheDependencies,
    val repositories: List<MavenRepo>,
    val decompilerArgs: List<String>,
    val remapperArgs: List<String>,
)

data class MacheDependencies(
    val codebook: List<MavenArtifact>,
    val paramMappings: List<MavenArtifact>,
    val constants: List<MavenArtifact>,
    val remapper: List<MavenArtifact>,
    val decompiler: List<MavenArtifact>,
)

abstract class MacheConfigValueSource: ValueSource<MacheConfig, MacheConfigValueSource.Parameters> {
    
    interface Parameters : ValueSourceParameters {
        @InputFile
        fun getZip(): RegularFileProperty
    }
    
    override fun obtain(): MacheConfig? {
        val macheZip = parameters.getZip().asFile.get()
        FileSystems.newFileSystem(macheZip.toPath()).use { fs ->
            fs.getPath("mache.json").reader().use {
                return GSON.fromJson(it, MacheConfig::class.java)
            }
        }
    }

}