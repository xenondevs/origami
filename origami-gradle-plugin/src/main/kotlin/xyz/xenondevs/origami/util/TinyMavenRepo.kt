package xyz.xenondevs.origami.util

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

class TinyMavenRepo(
    val folder: Provider<Directory>,
) {
    
    fun installArtifact(
        group: String,
        name: String,
        version: String,
        file: Path,
        dependencies: List<String> = emptyList(),
        sources: Path? = null,
    ) {
        val repoFolder = folder.get().asFile.toPath()
        
        val groupPath = group.replace('.', '/')
        val artifactPath = "$groupPath/$name/$version/${name}-${version}.jar"
        val sourcesPath = "$groupPath/$name/$version/${name}-${version}-sources.jar"
        val targetFile = repoFolder.resolve(artifactPath)
        val sourcesFile = repoFolder.resolve(sourcesPath)
        val pomFile = targetFile.resolveSibling("${name}-${version}.pom")
        
        Files.createDirectories(targetFile.parent)
        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
        if (sources != null) {
            Files.copy(sources, sourcesFile, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.deleteIfExists(sourcesFile)
        }
        
        pomFile.toFile().outputStream().buffered().use { out ->
            val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out)
            
            writer.writeStartDocument("UTF-8", "1.0")
            writer.writeStartElement("project")
            
            writer.writeNamespace("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI)
            writer.writeNamespace("", "http://maven.apache.org/POM/4.0.0")
            writer.writeAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation", "http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd")
            
            writer.writeElement("modelVersion", "4.0.0")
            writer.writeCoordinates(group, name, version)
            
            writer.writeStartElement("dependencies")
            for (dep in dependencies) {
                writer.writeStartElement("dependency")
                val (depGroup, depArtifact, depVersion) = dep.split(':', limit = 3)
                writer.writeCoordinates(depGroup, depArtifact, depVersion)
                writer.writeEndElement()
            }
            writer.writeEndElement()
            
            writer.writeEndElement()
            writer.writeEndDocument()
        }
    }
    
    
    
    private fun XMLStreamWriter.writeElement(name: String, value: String) {
        writeStartElement(name)
        writeCharacters(value)
        writeEndElement()
    }
    
    private fun XMLStreamWriter.writeCoordinates(group: String, artifact: String, version: String) {
        writeElement("groupId", group)
        writeElement("artifactId", artifact)
        writeElement("version", version)
    }
    
}