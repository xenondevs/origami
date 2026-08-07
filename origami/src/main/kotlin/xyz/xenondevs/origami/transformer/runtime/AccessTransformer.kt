package xyz.xenondevs.origami.transformer.runtime

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class AccessTransformer(private val aw: AccessWidener) : Transformer {
    
    override fun getTargetClasses(): Set<String> {
        return aw.targets.mapTo(HashSet()) { it.replace('.', '/')  }
    }
    
    override fun transform(clazz: ClassNode, original: ByteArray): ClassNode {
        val new = ClassNode()
        val widener = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, new, aw)
        clazz.accept(widener)
        return new
    }
    
}