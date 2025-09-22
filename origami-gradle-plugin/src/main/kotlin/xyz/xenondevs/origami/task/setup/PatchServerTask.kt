package xyz.xenondevs.origami.task.setup

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ParserConfiguration.LanguageLevel
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.PrimitiveType.Primitive
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.VoidType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import javassist.ClassPool
import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerClassVisitor
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.ForwardingVisitor
import net.fabricmc.accesswidener.TransitiveOnlyFilter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.xenondevs.origami.AccessWidenerConfig
import xyz.xenondevs.origami.AccessWidenerConfig.ClassMember
import xyz.xenondevs.origami.ProjectAccessWidener
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

// TODO: include craftbukkit sources
@CacheableTask
abstract class PatchServerTask @Inject constructor() : DefaultTask() {
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:Optional
    abstract val accessWidenerFile: RegularFileProperty
    
    @get:InputFiles
    @get:Classpath
    abstract val transitiveAccessWidenerSources: ConfigurableFileCollection
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val newSourcesDir: DirectoryProperty
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patchedSourcesDir: DirectoryProperty
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty
    
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val librariesDir: DirectoryProperty
    
    @get:OutputFile
    abstract val outputClassesJar: RegularFileProperty
    
    @get:OutputFile
    abstract val outputSourcesJar: RegularFileProperty
    
    @TaskAction
    fun run() {
        val aw = parseAccessWidener()
        val tempClassesOut = temporaryDir.resolve("widened-server.jar").apply { delete() }
        val tempSourcesOut = temporaryDir.resolve("widened-sources.jar").apply { delete() }
        
        val classesIn = ZipInputStream(serverJar.get().asFile.inputStream().buffered())
        val sourcesIn = ZipInputStream(sourcesJar.get().asFile.inputStream().buffered())
        val classesOut = ZipOutputStream(tempClassesOut.outputStream().buffered())
        val sourcesOut = ZipOutputStream(tempSourcesOut.outputStream().buffered())
        
        try {
            if (!aw.isEmpty()) {
                logger.lifecycle("Applying access widener to server classes and sources")
                val start = System.currentTimeMillis()
                processClasses(classesIn, classesOut, aw)
                val libraries = librariesDir.get().asFile.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
                val sourcesFolders = listOf(newSourcesDir.get().asFile, patchedSourcesDir.get().asFile)
                processSources(sourcesIn, libraries, sourcesFolders, sourcesOut, aw)
                logger.lifecycle("Applied access widener in ${(System.currentTimeMillis() - start).milliseconds}")
            } else {
                logger.lifecycle("Merging server classes and sources")
                classesIn.copyStructureTo(classesOut)
                sourcesIn.copyStructureTo(sourcesOut, allowedExtension = ".java")
            }
            
        } finally {
            classesIn.close()
            sourcesIn.close()
            classesOut.close()
            sourcesOut.close()
        }
        
        Files.move(tempClassesOut.toPath(), outputClassesJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tempSourcesOut.toPath(), outputSourcesJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    
    fun parseAccessWidener(): ProjectAccessWidener {
        val accessWidener = AccessWidener()
        val config = AccessWidenerConfig()
        
        if (accessWidenerFile.isPresent) {
            val projectAw = accessWidenerFile.asFile.get()
            logger.info("Using project access wideners from ${projectAw.name}")
            projectAw.bufferedReader().use { reader ->
                val awr = AccessWidenerReader(ForwardingVisitor(config, accessWidener))
                awr.read(reader)
            }
        } else {
            logger.info("No project access wideners configured")
        }
        
        transitiveAccessWidenerSources.asSequence()
            .filter { file -> file.extension.equals("jar", true) }
            .forEach { jarFile ->
                ZipInputStream(jarFile.inputStream().buffered()).use { zin ->
                    generateSequence { zin.nextEntry }
                        .filter { entry -> entry.name.endsWith(".accesswidener", true) }
                        .forEach { entry ->
                            logger.info("Using transitive access wideners from ${jarFile.name} (${entry.name})")
                            val awr = AccessWidenerReader(TransitiveOnlyFilter(ForwardingVisitor(config, accessWidener)))
                            awr.read(zin.bufferedReader())
                        }
                }
            }
        
        return ProjectAccessWidener(config, accessWidener)
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
    
    private fun processClasses(
        classesIn: ZipInputStream,
        out: ZipOutputStream,
        projectConfig: ProjectAccessWidener
    ) {
        generateSequence(classesIn::getNextEntry).forEach { entry ->
            out.putNextEntry(entry)
            if (entry.name.endsWith(".class") && projectConfig.hasClass(entry.name)) {
                val reader = ClassReader(classesIn)
                val writer = ClassWriter(0)
                val widener = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, projectConfig.accessWidener)
                reader.accept(widener, 0)
                out.write(writer.toByteArray())
            } else {
                classesIn.copyTo(out)
            }
            out.closeEntry()
        }
    }
    
    private fun processSources(
        sourcesZis: ZipInputStream,
        libraries: List<File>,
        sourcesFolders: List<File>,
        out: ZipOutputStream,
        projectConfig: ProjectAccessWidener
    ) {
        // fixes file handles to vanilla libraries not being closed
        ClassPool.cacheOpenedJarFile = false
        
        val config = projectConfig.config
        
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
        
        generateSequence { sourcesZis.nextEntry }.forEach { entry ->
            if (entry.isDirectory) return@forEach
            val name = entry.name
            
            if (name.endsWith(".java")) {
                out.putNextEntry(ZipEntry(name))
                if (name.endsWith(".java") && projectConfig.hasClass(name)) {
                    val code = sourcesZis.readBytes().decodeToString()
                    val cu = javaParser.parse(code).result
                        .orElseThrow { IllegalStateException("Cannot parse $name") }
                    
                    applyAccessChanges(cu, config, parserFacade)
                    out.write(cu.toString().toByteArray())
                } else {
                    sourcesZis.copyTo(out)
                }
                out.closeEntry()
            }
            // any other file besides java source files should already be included in the server jar, so just
            // ignore them to avoid zos conflict exceptions
        }
    }
    
    private fun applyAccessChanges(
        cu: CompilationUnit,
        config: AccessWidenerConfig,
        parserFacade: JavaParserFacade,
    ) {
        val pkgInternal = cu.packageDeclaration
            .map { it.nameAsString.replace('.', '/') }
            .orElse("")
        
        cu.findAll(BodyDeclaration::class.java).forEach { decl ->
            val owner = (decl as? TypeDeclaration<*>)
                ?: decl.findAncestor(TypeDeclaration::class.java) { true }.orElseThrow()
            val ownerInternal = owner.getInternalName(pkgInternal)
            
            when (decl) {
                is TypeDeclaration<*> -> {
                    val ch = config.classes[ownerInternal] ?: return@forEach
                    ch.apply(decl.modifiers, decl, null)
                }
                
                is FieldDeclaration -> {
                    decl.variables.forEach fieldLoop@{ v ->
                        if (!config.precheck.contains("${ownerInternal}.${v.nameAsString}")) return@fieldLoop
                        
                        val key = ClassMember(
                            ownerInternal,
                            v.nameAsString,
                            decl.commonType.toDescriptor(parserFacade)
                        )
                        
                        val ch = config.fields[key] ?: return@fieldLoop
                        // TODO
                        // If there are multiple variables in one declaration but only one is widened, that one
                        // should move to a new declaration as to not make the others appear public in the sources.
                        ch.apply(decl.modifiers, decl, decl.parentNode.orElse(null))
                    }
                }
                
                is MethodDeclaration -> {
                    if (!config.precheck.contains("${ownerInternal}.${decl.nameAsString}()")) return@forEach
                    
                    val key = ClassMember(
                        ownerInternal,
                        decl.nameAsString,
                        decl.getDescriptor(parserFacade)
                    )
                    val ch = config.methods[key] ?: return@forEach
                    ch.apply(decl.modifiers, decl, decl.parentNode.orElse(null))
                }
                
                is ConstructorDeclaration -> {
                    if (!config.precheck.contains("${ownerInternal}.<init>()")) return@forEach
                    
                    val key = ClassMember(
                        ownerInternal,
                        "<init>",
                        decl.getDescriptor(parserFacade)
                    )
                    val ch = config.methods[key] ?: return@forEach
                    ch.apply(decl.modifiers, decl, decl.parentNode.orElse(null))
                }
            }
        }
    }
    
    private fun TypeDeclaration<*>.getInternalName(pkg: String): String {
        val names = generateSequence(this) { it.parentNode.orElse(null) as? TypeDeclaration<*> }
            .map { it.nameAsString }
            .toList()
            .asReversed()
        
        return buildString {
            if (pkg.isNotEmpty()) append(pkg).append('/')
            append(names.joinToString("$"))
        }
    }
    
    private fun Type.toDescriptor(parserFacade: JavaParserFacade): String {
        return when (this) {
            is ArrayType -> "[${elementType.toDescriptor(parserFacade)}"
            is PrimitiveType -> when (this.type) {
                Primitive.BOOLEAN -> "Z"
                Primitive.BYTE -> "B"
                Primitive.CHAR -> "C"
                Primitive.DOUBLE -> "D"
                Primitive.FLOAT -> "F"
                Primitive.INT -> "I"
                Primitive.LONG -> "J"
                Primitive.SHORT -> "S"
                null -> throw IllegalArgumentException("Received null primitive type in $this")
            }
            
            is VoidType -> "V"
            
            is ClassOrInterfaceType -> {
                if (typeArguments.isPresent) {
                    // type args are irrelevant for descriptors
                    val rawName = this.toString().substringBefore('<')
                    
                    val ctx = JavaParserFactory.getContext(this, parserFacade.typeSolver)
                    val ref = ctx.solveType(rawName, emptyList())
                    check(ref.isSolved) { "Cannot resolve type $rawName in context $ctx" }
                    val decl = ref.correspondingDeclaration
                    
                    val pkg = decl.packageName.replace('.', '/')
                    val name = decl.qualifiedName.drop(decl.packageName.length + 1).replace('.', '$')
                    
                    return buildString {
                        append('L')
                        if (pkg.isNotEmpty()) append(pkg).append('/')
                        append(name).append(';')
                    }
                }
                
                val resolved = parserFacade.convertToUsage(this)
                
                if (resolved.isReferenceType) {
                    val ref = resolved.asReferenceType().typeDeclaration.get()
                    val pkg = ref.packageName.replace('.', '/')
                    val name = ref.qualifiedName.drop(ref.packageName.length + 1).replace('.', '$')
                    return buildString {
                        append('L')
                        if (pkg.isNotEmpty()) append(pkg).append('/')
                        append(name).append(';')
                    }
                }
                
                if (resolved.isTypeVariable) {
                    val typeParam = resolved.asTypeParameter()
                    
                    val bound = typeParam.bounds
                        .firstOrNull { it.type.isReferenceType }
                        ?.type?.asReferenceType()
                        ?.typeDeclaration
                        ?.get()
                    
                    if (bound == null) {
                        // if no bound is found, assume it's an object type
                        return "Ljava/lang/Object;"
                    } else {
                        // found a bound, so use that
                        val pkg = bound.packageName.replace('.', '/')
                        val name = bound.qualifiedName.drop(bound.packageName.length + 1).replace('.', '$')
                        return buildString {
                            append('L')
                            if (pkg.isNotEmpty()) append(pkg).append('/')
                            append(name).append(';')
                        }
                    }
                } else {
                    throw IllegalArgumentException("Unsupported type: $this")
                }
            }
            
            else -> throw IllegalArgumentException("Unsupported type: $this")
        }
    }
    
    private fun CallableDeclaration<*>.getDescriptor(parserFacade: JavaParserFacade): String {
        val params = parameters.joinToString("") { it.type.toDescriptor(parserFacade) }
        val returnType = if (this is MethodDeclaration) type.toDescriptor(parserFacade) else "V"
        return "($params)$returnType"
    }
    
}