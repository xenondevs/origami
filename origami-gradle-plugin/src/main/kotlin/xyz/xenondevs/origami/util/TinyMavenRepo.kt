package xyz.xenondevs.origami.util

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import kotlin.text.split

class TinyMavenRepo(
    val folder: Provider<Directory>,
) {
    
    fun installPom(
        group: String,
        name: String,
        version: String,
        dependencies: List<ModuleComponentIdentifier> = emptyList()
    ) {
        val pom = resolve(group, name, version, ".pom")
        pom.parentFile.mkdirs()
        pom.outputStream().buffered().use { out ->
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
                writer.writeCoordinates(dep.group, dep.module, dep.version)
                writer.writeEndElement()
            }
            writer.writeEndElement()
            
            writer.writeEndElement()
            writer.writeEndDocument()
        }
    }
    
    fun installJarArtifact(
        group: String,
        name: String,
        version: String,
        classifier: String?,
        file: File,
    ) {
        val suffix = if (classifier != null) "-$classifier.jar" else ".jar"
        val targetFile = resolve(group, name, version, suffix)
        targetFile.parentFile.mkdirs()
        file.copyTo(targetFile, true)
    }
    
    private fun resolve(group: String, name: String, version: String, suffix: String): File {
        val path = "${group.replace('.', '/')}/$name/$version/$name-$version$suffix"
        return folder.get().asFile.resolve(path)
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