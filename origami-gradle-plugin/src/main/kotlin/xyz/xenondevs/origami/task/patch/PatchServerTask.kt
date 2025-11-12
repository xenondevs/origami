package xyz.xenondevs.origami.task.patch

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ParserConfiguration.LanguageLevel
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import javassist.ClassPool
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.xenondevs.origami.task.patch.type.WidenerPatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.measureTime

@CacheableTask
internal abstract class PatchServerTask : DefaultTask() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:Optional
    abstract val accessWidenerFile: RegularFileProperty
    
    @get:InputFiles
    @get:Classpath
    abstract val transitiveAccessWidenerSources: ConfigurableFileCollection
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: RegularFileProperty
    
    @get:Internal
    abstract val visitor: Property<AtomicReference<ChainedVisitor>>
    
    @get:OutputFile
    abstract val output: RegularFileProperty
    
    @TaskAction
    fun run() {
        val visitor = createVisitor()
        val inp = ZipInputStream(input.get().asFile.inputStream().buffered())
        val out = ZipOutputStream(output.get().asFile.outputStream().buffered())
        
        try {
            if (visitor.isEnabled()) {
                val time = measureTime { process(visitor, inp, out) }
                logger.info("Applied patches in $time")
            } else {
                copy(inp, out)
                logger.info("Applied no patches")
            }
        } finally {
            inp.close()
            out.close()
        }
    }
    
    fun createVisitor(): ChainedVisitor {
        val ref = visitor.get()
        synchronized(ref) {
            val inner = ref.get()
            if (inner != null) return inner
            
            val visitor = ChainedVisitor()
            visitor.patches += WidenerPatch(accessWidenerFile, transitiveAccessWidenerSources, logger)
            ref.set(visitor)
            return visitor
        }
    }
    
    abstract fun process(visitor: ChainedVisitor, inp: ZipInputStream, out: ZipOutputStream)
    
    abstract fun copy(inp: ZipInputStream, out: ZipOutputStream)
    
    abstract class Jar : PatchServerTask() {
        
        override fun process(visitor: ChainedVisitor, inp: ZipInputStream, out: ZipOutputStream) {
            generateSequence(inp::getNextEntry).forEach { entry ->
                out.putNextEntry(entry)
                if (entry.name.endsWith(".class") && visitor.isRelevant(entry)) {
                    out.write(visitor.processClass(entry, inp))
                } else {
                    inp.copyTo(out)
                }
                out.closeEntry()
            }
        }
        
        override fun copy(inp: ZipInputStream, out: ZipOutputStream) {
            inp.copyStructureTo(out)
        }
        
    }
    
    abstract class SourcesJar : PatchServerTask() {
        
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val librariesDir: DirectoryProperty
        
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val newSourcesDir: DirectoryProperty
        
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val patchedSourcesDir: DirectoryProperty
        
        override fun process(
            visitor: ChainedVisitor,
            inp: ZipInputStream,
            out: ZipOutputStream
        ) {
            val libraries = librariesDir.get().asFile.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
            val sourcesFolders = listOf(newSourcesDir.get().asFile, patchedSourcesDir.get().asFile)
            
            // fixes file handles to vanilla libraries not being closed
            ClassPool.cacheOpenedJarFile = false
            
            // This allows multiple things:
            // * Resolving full class names including package names from imports
            // * Resolving full class names including package names for classes in the same package
            // * Resolving bounds for generic types
            // * Differentiating between package path and inner class names (e.g. a.b.c.d could be both a/b/c/d.java or a/b/c$d.java)
            val typeSolver = CombinedTypeSolver().apply {
                add(ReflectionTypeSolver())
                // JavaParserTypeSolver currently doesn't support zip file systems for sources so we need the 2 folders the
                // sources jar was built from
                sourcesFolders.forEach { add(JavaParserTypeSolver(it)) }
                libraries.forEach { add(JarTypeSolver(it)) }
            }
            val symbolSolver = JavaSymbolSolver(typeSolver)
            
            // Not using StaticJavaParser here because of the Gradle daemon.
            val parserCfg = ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(LanguageLevel.CURRENT)
            val javaParser = JavaParser(parserCfg)
            val parserFacade = JavaParserFacade.get(typeSolver)
            
            generateSequence { inp.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                
                if (name.endsWith(".java")) {
                    out.putNextEntry(ZipEntry(name))
                    if (name.endsWith(".java") && visitor.isRelevant(entry)) {
                        val code = inp.readBytes().decodeToString()
                        val cu = javaParser.parse(code).result
                            .orElseThrow { IllegalStateException("Cannot parse $name") }
                        
                        visitor.processSource(cu, parserFacade)
                        out.write(cu.toString().toByteArray())
                    } else {
                        inp.copyTo(out)
                    }
                    out.closeEntry()
                }
                // any other file besides java source files should already be included in the server jar, so just
                // ignore them to avoid zos conflict exceptions
            }
        }
        
        override fun copy(inp: ZipInputStream, out: ZipOutputStream) {
            inp.copyStructureTo(out, allowedExtension = ".java")
        }
        
    }
    
}

private fun ZipInputStream.copyStructureTo(out: ZipOutputStream, allowedExtension: String? = null) {
    generateSequence { getNextEntry() }.forEach { entry ->
        if (allowedExtension == null || entry.name.endsWith(allowedExtension)) {
            out.putNextEntry(entry)
            this.copyTo(out)
            out.closeEntry()
        }
    }
}