package xyz.xenondevs.origami.task.setup

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.io.File
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

abstract class InstallTask(objects: ObjectFactory) : DefaultTask() {
    
    @get:Input
    val group: Property<String> = objects.property()
    
    @get:Input
    val name: Property<String> = objects.property()
    
    @get:Input
    val version: Property<String> = objects.property()
    
    @get:Internal
    val localRepo: DirectoryProperty = objects.directoryProperty()
    
    @get:OutputFile
    abstract val target: RegularFileProperty
    
    abstract class Artifact @Inject constructor(objects: ObjectFactory) : InstallTask(objects) {
        
        @get:Input
        val classifier: Property<String> = objects.property<String>()
            .convention("")
        
        @get:Input
        val extension: Property<String> = objects.property<String>()
            .convention("jar")
        
        @get:InputFile
        val source: RegularFileProperty = objects.fileProperty()
        
        @get:OutputFile
        override val target: RegularFileProperty = objects.fileProperty()
            .convention(
                group
                    .zip(name) { g, n -> listOf(g, n) }
                    .zip(version) { l, v -> l + v }
                    .zip(classifier) { l, c -> l + c }
                    .zip(extension) { l, e -> l + e }
                    .zip(localRepo) { l, repo ->
                        val (group, name, version, classifier, extension) = l
                        val fileName = "$name-$version${if (classifier.isNotEmpty()) "-$classifier" else ""}.$extension"
                        repo.file("${group.replace('.', '/')}/$name/$version/$fileName")
                    }
            )
        
        @TaskAction
        fun run() {
            target.get().asFile.parentFile.mkdirs()
            source.get().asFile.copyTo(target.get().asFile, true)
        }
        
    }
    
    abstract class Pom @Inject constructor(objects: ObjectFactory) : InstallTask(objects) {
        
        @get:Internal
        val paperClasspathConfig: Property<Configuration> = objects.property()
        
        @get:OutputFile
        override val target: RegularFileProperty = objects.fileProperty()
            .convention(
                group
                    .zip(name) { g, n -> listOf(g, n) }
                    .zip(version) { l, v -> l + v }
                    .zip(localRepo) { l, repo ->
                        val (group, name, version) = l
                        repo.file("${group.replace('.', '/')}/$name/$version/$name-$version.pom")
                    }
            )
        
        @TaskAction
        fun run() {
            val root = paperClasspathConfig.get()
                .incoming
                .resolutionResult
                .root
                .dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .single()
                .selected
            
            val deps = root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
            
            installPom(group.get(), name.get(), version.get(), target.get().asFile, deps)
        }
        
        private fun installPom(
            group: String,
            name: String,
            version: String,
            pom: File,
            dependencies: List<ModuleComponentIdentifier>
        ) {
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
    
}