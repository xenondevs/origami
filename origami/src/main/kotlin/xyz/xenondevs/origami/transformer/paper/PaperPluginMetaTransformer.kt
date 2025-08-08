package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode

/**
 * Note that the field added by this transformer is not meant to be used as a yml configuration option. It is just added
 * here for ease of access in the [PaperPluginClassLoaderTransformer] to avoid always having to call a method to check
 * if this plugin uses Origami.
 */
object PaperPluginMetaTransformer : PaperTransformer {
    
    const val ENABLED_FIELD = "origami\$enabled"
    
    override val className = "io/papermc/paper/plugin/provider/configuration/PaperPluginMeta"
    
    override fun transform(clazz: ClassNode) {
        // public boolean origami$enabled = false;
        val field = FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_TRANSIENT, ENABLED_FIELD, "Z", null, false)
        clazz.fields.add(field)
    }
}