package xyz.xenondevs.origami.transform.action

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
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.xenondevs.origami.AccessWidenerConfig
import xyz.xenondevs.origami.AccessWidenerConfig.ClassMember
import xyz.xenondevs.origami.ProjectAccessWidener
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.measureTime

internal interface WidenTransformParameters : TransformParameters {
    
    @get:Input
    val devBundleId: Property<String>
    
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val accessWideners: ConfigurableFileCollection
    
    @get:InputFiles
    @get:Classpath
    val transitiveAccessWidenerSources: ConfigurableFileCollection
    
}

/**
 * Produces the server JAR used by the current project.
 *
 * It takes the shared patched server and applies access wideners from the project and its dependencies. This final
 * output is project-specific, unlike the cached server prepared by [PrepareServerTransform].
 */
@CacheableTransform
internal abstract class WidenBinaryTransform : TransformAction<WidenTransformParameters> {
    
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    
    override fun transform(outputs: TransformOutputs) {
        val base = inputArtifact.get().asFile
        val logger = Logging.getLogger(WidenBinaryTransform::class.java)
        logger.lifecycle("[Origami] Preparing widened Paper server for ${parameters.devBundleId.get()}")
        Widening.Jar(
            parameters.accessWideners,
            parameters.transitiveAccessWidenerSources,
            logger
        ).run(base.resolve(PrepareServerTransform.PATCHED_SERVER_FILE), outputs.file("server-widened.jar"))
        logger.lifecycle("[Origami] Prepared widened Paper server for ${parameters.devBundleId.get()}")
    }
    
}

/**
 * Produces server sources whose access modifiers match the widened server JAR.
 *
 * It applies the same project and dependency access wideners to the shared patched sources, so navigation and
 * completion in the IDE reflect the classes that the project actually compiles against.
 */
@CacheableTransform
internal abstract class WidenSourcesTransform : TransformAction<WidenTransformParameters> {
    
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    
    override fun transform(outputs: TransformOutputs) {
        val base = inputArtifact.get().asFile
        val logger = Logging.getLogger(WidenSourcesTransform::class.java)
        logger.lifecycle("[Origami] Preparing widened Paper sources for ${parameters.devBundleId.get()}")
        Widening.SourcesJar(
            parameters.accessWideners,
            parameters.transitiveAccessWidenerSources,
            logger,
            base.resolve(PrepareSourcesTransformAction.LIBRARIES_DIR),
            base.resolve(PrepareSourcesTransformAction.NEW_SOURCES_DIR),
            base.resolve(PrepareSourcesTransformAction.PATCHED_SOURCES_DIR),
        ).run(base.resolve(PrepareSourcesTransformAction.PATCHED_SOURCES_FILE), outputs.file("server-widened-sources.jar"))
        logger.lifecycle("[Origami] Prepared widened Paper sources for ${parameters.devBundleId.get()}")
    }
    
}

// TODO: include craftbukkit sources
/**
 * Reads the access wideners supplied by the current project and its dependencies, then applies their requested access
 * changes to either compiled classes or Java sources.
 */
internal abstract class Widening(
    private val accessWideners: Iterable<File>,
    private val transitiveAccessWidenerSources: Iterable<File>,
    protected val logger: Logger,
) {
    
    fun run(input: File, output: File) {
        val aw = parseAccessWidener()
        output.parentFile.mkdirs()
        
        val inp = ZipInputStream(input.inputStream().buffered())
        val out = ZipOutputStream(output.outputStream().buffered())
        
        try {
            if (!aw.isEmpty()) {
                val time = measureTime { process(aw, inp, out) }
                logger.info("Applied access wideners in $time")
            } else {
                copy(inp, out)
                logger.info("Applied no access wideners")
            }
        } finally {
            inp.close()
            out.close()
        }
    }
    
    private fun parseAccessWidener(): ProjectAccessWidener {
        val accessWidener = AccessWidener()
        val config = AccessWidenerConfig()
        
        val accessWideners = accessWideners.toSet()
        if (accessWideners.isNotEmpty()) {
            for (projectAw in accessWideners) {
                logger.info("Using project access wideners from ${projectAw.name}")
                projectAw.bufferedReader().use { reader ->
                    val awr = AccessWidenerReader(ForwardingVisitor(config, accessWidener))
                    awr.read(reader)
                }
            }
        } else {
            logger.info("No project access wideners configured")
        }
        
        transitiveAccessWidenerSources.asSequence()
            .filter { file -> file.extension.equals("jar", true) }
            .forEach { jarFile ->
                ZipInputStream(jarFile.inputStream().buffered()).use { zin ->
                    generateSequence { zin.nextEntry }
                        .filter { entry -> entry.name.endsWith(".accesswidener") || entry.name.endsWith(".aw") }
                        .forEach { entry ->
                            logger.info("Using transitive access wideners from ${jarFile.name} (${entry.name})")
                            val awr = AccessWidenerReader(TransitiveOnlyFilter(ForwardingVisitor(config, accessWidener)))
                            awr.read(zin.bufferedReader())
                        }
                }
            }
        
        return ProjectAccessWidener(config, accessWidener)
    }
    
    abstract fun process(aw: ProjectAccessWidener, inp: ZipInputStream, out: ZipOutputStream)
    
    abstract fun copy(inp: ZipInputStream, out: ZipOutputStream)
    
    /**
     * Rewrites access flags in the compiled server classes and copies all other JAR entries unchanged.
     */
    class Jar(
        accessWideners: Iterable<File>,
        transitiveAccessWidenerSources: Iterable<File>,
        logger: Logger,
    ) : Widening(accessWideners, transitiveAccessWidenerSources, logger) {
        
        override fun process(aw: ProjectAccessWidener, inp: ZipInputStream, out: ZipOutputStream) {
            generateSequence(inp::getNextEntry).forEach { entry ->
                out.putNextEntry(entry)
                if (entry.name.endsWith(".class") && aw.hasClass(entry.name)) {
                    val reader = ClassReader(inp)
                    val writer = ClassWriter(0)
                    val widener = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, aw.accessWidener)
                    reader.accept(widener, 0)
                    out.write(writer.toByteArray())
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
    
    /**
     * Updates access modifiers in Java sources so the source JAR describes the same public API as the widened classes.
     */
    class SourcesJar(
        accessWideners: Iterable<File>,
        transitiveAccessWidenerSources: Iterable<File>,
        logger: Logger,
        private val librariesDir: File,
        private val newSourcesDir: File,
        private val patchedSourcesDir: File,
    ) : Widening(accessWideners, transitiveAccessWidenerSources, logger) {
        
        override fun process(
            aw: ProjectAccessWidener,
            inp: ZipInputStream,
            out: ZipOutputStream
        ) {
            val libraries = librariesDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
            val sourcesFolders = listOf(newSourcesDir, patchedSourcesDir)
            
            // fixes file handles to vanilla libraries not being closed
            ClassPool.cacheOpenedJarFile = false
            
            val config = aw.config
            
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
                    if (name.endsWith(".java") && aw.hasClass(name)) {
                        try {
                            val code = inp.readBytes().decodeToString()
                            val cu = javaParser.parse(code).result
                                .orElseThrow { IllegalStateException("Cannot parse $name") }
                            
                            applyAccessChanges(cu, config, parserFacade)
                            out.write(cu.toString().toByteArray())
                        } catch (e: Exception) {
                            logger.error("Failed to parse $name", e)
                            inp.copyTo(out)
                        }
                    } else {
                        inp.copyTo(out)
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