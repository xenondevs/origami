package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.getMethod

/**
 * Transforms the PaperPluginClassLoader to register a LookupProxy.
 */
object PaperPluginClassLoaderTransformer : PaperTransformer {
    
    private const val PLUGIN_META_CLASS = "io/papermc/paper/plugin/provider/configuration/PaperPluginMeta"
    
    override val className = "io/papermc/paper/plugin/entrypoint/classloader/PaperPluginClassLoader"
    
    override fun transform(clazz: ClassNode) {
        // super();
        // var pluginName = configuration.getName();
        // var lookupClass = LookupProxy.createLookupClass(pluginName);
        // var newClass = defineClass(lookupClass.name, lookupClass.bytecode, 0, lookupClass.bytecode.length);
        // LookupProxy.initPluginLookup(pluginName, newClass);
        val ctor = clazz.getMethod("<init>", includesDesc = false)!!
        val ctorInsns = ctor.instructions
        ctor.localVariables.clear()
        val superCall = ctorInsns.find { it.opcode == Opcodes.INVOKESPECIAL }!!
        ctorInsns.insert(superCall, buildInsnList {
            aLoad(4) // PaperPluginMeta configuration
            invokeVirtual(PLUGIN_META_CLASS, "getName", "()Ljava/lang/String;")
            dup()
            invokeStatic("xyz/xenondevs/origami/LookupProxy", "createLookupClass", $$"(Ljava/lang/String;)Lxyz/xenondevs/origami/LookupProxy$LookupClassDefinition;")
            aLoad(0)
            swap()
            dup()
            invokeVirtual($$"xyz/xenondevs/origami/LookupProxy$LookupClassDefinition", "name", "()Ljava/lang/String;")
            swap()
            invokeVirtual($$"xyz/xenondevs/origami/LookupProxy$LookupClassDefinition", "bytecode", "()[B")
            dup()
            arraylength()
            ldc(0)
            swap()
            invokeVirtual("java/lang/ClassLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;")
            invokeStatic("xyz/xenondevs/origami/LookupProxy", "initPluginLookup", "(Ljava/lang/String;Ljava/lang/Class;)V")
        })
    }
    
}