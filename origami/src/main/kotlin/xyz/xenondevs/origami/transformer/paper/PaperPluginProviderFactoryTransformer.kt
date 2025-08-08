package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.transformer.paper.PaperPluginMetaTransformer.ENABLED_FIELD
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.getMethod
import xyz.xenondevs.origami.util.internalName

object PaperPluginProviderFactoryTransformer : PaperTransformer {

    override val className = "io/papermc/paper/plugin/provider/type/paper/PaperPluginProviderFactory"
    
    override fun transform(clazz: ClassNode) {
        val createMethod = clazz.getMethod("create")!!
        val createInsns = createMethod.instructions
        val returnInsn = createInsns.find { it.opcode == Opcodes.ARETURN}!!
        createInsns.insertBefore(returnInsn, buildInsnList {
            // configuration.origami$enabled = Origami.isMixinPlugin(configuration.getName());
            dup()
            dup()
            invokeVirtual(PaperPluginMetaTransformer.className, "getName", "()Ljava/lang/String;")
            invokeStatic(Origami::class.internalName, "isMixinPlugin", "(Ljava/lang/String;)Z")
            putField(PaperPluginMetaTransformer.className, ENABLED_FIELD, "Z")
        })
        createMethod.localVariables.clear()
    }
}