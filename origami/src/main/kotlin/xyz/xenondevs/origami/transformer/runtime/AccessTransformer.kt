package xyz.xenondevs.origami.transformer.runtime

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerClassVisitor
import net.fabricmc.accesswidener.AccessWidenerReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.InputStream

object AccessTransformer : Transformer {
    
    val accessWidener = AccessWidener()
    
    fun readAccessWidener(stream: InputStream) {
        val reader = stream.bufferedReader()
        reader.use(AccessWidenerReader(accessWidener)::read)
    }
    
    override fun getTargetClasses(): Set<String> {
        return accessWidener.targets.mapTo(HashSet()) { it.replace('.', '/')  }
    }
    
    override fun transform(clazz: ClassNode, original: ByteArray): ClassNode? {
        val new = ClassNode()
        val widener = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, new, accessWidener)
        clazz.accept(widener)
        
        return new
    }
}