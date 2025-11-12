package xyz.xenondevs.origami.task.patch

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import xyz.xenondevs.origami.task.patch.type.PatchType
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ChainedVisitor {
    
    val patches = LinkedList<PatchType>()
    
    fun isEnabled() = patches.isNotEmpty()
    
    fun isRelevant(entry: ZipEntry) = patches.any { it.isApplicable(entry) }
    
    fun processClass(entry: ZipEntry, zis: ZipInputStream): ByteArray {
        val cr = ClassReader(zis)
        val cw = ClassWriter(0)
        
        val top = patches
            .asSequence()
            .filter { it.isApplicable(entry) }
            .fold(cw as ClassVisitor) { acc, next -> next.getClassVisitor(acc) }
        
        cr.accept(top, 0)
        return cw.toByteArray()
    }
    
    fun processSource(cu: CompilationUnit, serverFacade: JavaParserFacade) {
        patches.forEach { it.getSourceVisitor(serverFacade).visit(cu, serverFacade) }
    }
    
}