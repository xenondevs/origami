package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.xenondevs.origami.PatchingClassLoader
import xyz.xenondevs.origami.util.assemble
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.getMethod
import xyz.xenondevs.origami.util.internalName
import java.io.InputStream
import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.net.URLClassLoader

/**
 * Patches Paperclip to use our [PatchingClassLoader] and to initialize Origami transformers.
 */
object PaperclipPatcher {
    
    fun patch(instrumentation: Instrumentation, appClassLoader: ClassLoader) {
        val node = getPaperclipNode(appClassLoader)
        patchMain(node)
        val bytecode = node.assemble()
        val paperclipClass = appClassLoader.loadClass("io.papermc.paperclip.Paperclip")
        instrumentation.redefineClasses(ClassDefinition(paperclipClass, bytecode))
    }
    
    fun getPaperclipNode(appClassLoader: ClassLoader): ClassNode {
        val bytes = appClassLoader.getResourceAsStream("io/papermc/paperclip/Paperclip.class")?.use(InputStream::readBytes)
            ?: throw IllegalStateException("Paperclip class not found")
        
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
        return node
    }
    
    fun patchMain(node: ClassNode) {
        val mainInsns = node
            .getMethod("main([Ljava/lang/String;)V", includesDesc = true)!!
            .apply { localVariables?.clear() }
            .instructions
        
        val iter = mainInsns.iterator()
        while (iter.hasNext()) {
            when (val insn = iter.next()) {
                is MethodInsnNode -> {
                    if (insn.opcode == Opcodes.INVOKESPECIAL && insn.owner == URLClassLoader::class.internalName) {
                        // Replace URLClassLoader constructor call with PatchingClassLoader init
                        insn.opcode = Opcodes.INVOKESTATIC
                        insn.owner = "xyz/xenondevs/origami/OrigamiAgent"
                        insn.name = "createClassLoader"
                        insn.desc = "([Ljava/net/URL;Ljava/lang/ClassLoader;)Ljava/lang/ClassLoader;"
                        
                        // Insert init call after classloader creation
                        mainInsns.insert(insn.next, buildInsnList {
                            aLoad(1)
                            aLoad(3)
                            invokeStatic("xyz/xenondevs/origami/OrigamiAgent", "initOrigami", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V")
                        })
                    }
                }
                
                is TypeInsnNode -> {
                    if (insn.opcode == Opcodes.NEW && insn.desc == URLClassLoader::class.internalName) {
                        // Remove URLClassLoader alloc call since the constructor call is replaced with a static call
                        if (insn.next.opcode != Opcodes.DUP) {
                            throw IllegalStateException("Expected DUP after NEW URLClassLoader")
                        }
                        iter.remove() // Remove NEW URLClassLoader
                        iter.next()
                        iter.remove() // Remove DUP
                    }
                }
                
                is InvokeDynamicInsnNode -> {
                    // Replace lambda handle with one accepting normal ClassLoader
                    val bsm = insn.bsm
                    if (bsm.owner == "java/lang/invoke/LambdaMetafactory" && bsm.name == "metafactory") {
                        val handle = insn.bsmArgs[1] as Handle
                        insn.desc = insn.desc.replace("Ljava/net/URLClassLoader;", "Ljava/lang/ClassLoader;")
                        insn.bsmArgs[1] = Handle(
                            handle.tag,
                            "xyz/xenondevs/origami/OrigamiAgent",
                            "startMain",
                            handle.desc.replace("Ljava/net/URLClassLoader;", "Ljava/lang/ClassLoader;"),
                            handle.isInterface
                        )
                    }
                }
                
            }
        }
    }
    
}