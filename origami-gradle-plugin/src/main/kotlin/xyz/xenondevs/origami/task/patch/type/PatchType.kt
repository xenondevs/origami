package xyz.xenondevs.origami.task.patch.type

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import org.objectweb.asm.ClassVisitor
import java.util.zip.ZipEntry

abstract class PatchType {

    abstract fun isEnabled(): Boolean
    
    abstract fun isApplicable(entry: ZipEntry): Boolean
    
    abstract fun getClassVisitor(parent: ClassVisitor): ClassVisitor
    
    abstract fun getSourceVisitor(serverFacade: JavaParserFacade): SourceVisitor
    
}

interface SourceVisitor {
    
    fun visit(cu: CompilationUnit, serverFacade: JavaParserFacade)
    
}