package xyz.xenondevs.origami.util.dto

internal class VersionManifest(
    val versions: List<Version>
) {
    
    class Version(
        val id: String,
        val url: String
    )
    
}

internal class VersionData(
    val downloads: Map<String, Download>
) {
    
    class Download(val url: String)
    
}