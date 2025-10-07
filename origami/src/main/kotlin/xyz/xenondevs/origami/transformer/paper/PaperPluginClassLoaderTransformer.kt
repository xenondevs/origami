package xyz.xenondevs.origami.transformer.paper

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import xyz.xenondevs.origami.PluginProxy
import xyz.xenondevs.origami.asm.LookupProxy
import xyz.xenondevs.origami.asm.LookupProxy.LookupClassDefinition
import xyz.xenondevs.origami.transformer.paper.PaperPluginMetaTransformer.ENABLED_FIELD
import xyz.xenondevs.origami.util.InsnBuilder
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.getMethod
import xyz.xenondevs.origami.util.internalName

object PaperPluginClassLoaderTransformer : PaperTransformer {
    
    private const val REQUIRED_CLASSES_FIELD = $$"origami$requiredClasses"
    private const val PLUGIN_NAME_FIELD = $$"origami$pluginName"
    
    override val className = "io/papermc/paper/plugin/entrypoint/classloader/PaperPluginClassLoader"
    
    override fun transform(clazz: ClassNode) {
        addSetInit(clazz)
        addClassInitCalls(clazz)
    }
    
    private fun addSetInit(clazz: ClassNode) {
        // private final Set<String> origami$requiredClasses;
        // private final String origami$pluginName;
        clazz.fields.add(FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, REQUIRED_CLASSES_FIELD, "Ljava/util/Set;", null, null))
        clazz.fields.add(FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, PLUGIN_NAME_FIELD, "Ljava/lang/String;", null, null))
        // super();
        // if (configuration.origami$enabled) {
        //     this.origami$requiredClasses = PluginProxy.getRequired(configuration.getName());
        //     this.origami$pluginName = configuration.getName();
        //     var lookupClass = LookupProxy.createLookupClass(this.origami$pluginName);
        //     var newClass = defineClass(lookupClass.name, lookupClass.bytecode, 0, lookupClass.bytecode.length);
        //     LookupProxy.initPluginLookup(this.origami$pluginName, newClass);
        // }
        val ctor = clazz.getMethod("<init>", includesDesc = false)!!
        val ctorInsns = ctor.instructions
        val newLocal = ctor.localVariables.size
        ctor.localVariables.clear()
        val superCall = ctorInsns.find { it.opcode == Opcodes.INVOKESPECIAL }!!
        val skipLabel = superCall.next as LabelNode
        ctorInsns.insert(superCall, buildInsnList {
            addLabel()
            aLoad(4) // PaperPluginMeta configuration
            getField(PaperPluginMetaTransformer.className, ENABLED_FIELD, "Z")
            ifeq(skipLabel)
            
            addLabel()
            aLoad(0)
            aLoad(4) // PaperPluginMeta configuration
            invokeVirtual(PaperPluginMetaTransformer.className, "getName", "()Ljava/lang/String;")
            dup2()
            invokeStatic(PluginProxy::class.internalName, "getRequired", "(Ljava/lang/String;)Ljava/util/Set;")
            putField(clazz.name, REQUIRED_CLASSES_FIELD, "Ljava/util/Set;")
            putField(clazz.name, PLUGIN_NAME_FIELD, "Ljava/lang/String;")
            
            addLabel()
            aLoad(0)
            getField(clazz.name, PLUGIN_NAME_FIELD, "Ljava/lang/String;")
            invokeStatic(LookupProxy::class.internalName, "createLookupClass", $$"(Ljava/lang/String;)Lxyz/xenondevs/origami/asm/LookupProxy$LookupClassDefinition;")
            aStore(newLocal)
            
            addLabel()
            aLoad(0)
            getField(clazz.name, PLUGIN_NAME_FIELD, "Ljava/lang/String;")
            aLoad(0)
            aLoad(newLocal)
            getField(LookupClassDefinition::class.internalName, "name", "Ljava/lang/String;")
            aLoad(newLocal)
            getField(LookupClassDefinition::class.internalName, "bytecode", "[B")
            dup()
            arraylength()
            ldc(0)
            swap()
            invokeVirtual("java/lang/ClassLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;")
            invokeStatic(LookupProxy::class.internalName, "initPluginLookup", "(Ljava/lang/String;Ljava/lang/Class;)V")
        })
    }
    
    private fun addClassInitCalls(clazz: ClassNode) {
        val method = clazz.getMethod("loadClass", "(Ljava/lang/String;ZZZ)Ljava/lang/Class;")!!
        val insns = method.instructions
        var superCall: MethodInsnNode? = null
        var libraryCall: MethodInsnNode? = null
        for (it in insns) {
            if (it !is MethodInsnNode)
                continue
            if (superCall != null && libraryCall != null)
                break
            if (it.opcode == Opcodes.INVOKESPECIAL
                && it.owner == "io/papermc/paper/plugin/entrypoint/classloader/PaperSimplePluginClassLoader"
                && it.name == "loadClass") {
                superCall = it
            } else if (it.opcode == Opcodes.INVOKEVIRTUAL && it.owner == "java/net/URLClassLoader" && it.name == "loadClass") {
                libraryCall = it
            }
        }
        
        if (superCall == null || libraryCall == null) {
            throw IllegalStateException("Could not find super or library calls in PaperPluginClassLoader")
        }
        
        val localIdx = method.maxLocals
        method.maxLocals += 1
        
        val initCall: InsnBuilder.() -> Unit = {
            val skip = LabelNode()
            
            aLoad(0)
            getField(clazz.name, REQUIRED_CLASSES_FIELD, "Ljava/util/Set;")
            dup()
            aStore(localIdx)
            ifnull(skip)
            
            addLabel()
            aLoad(localIdx)
            aLoad(1)
            invokeInterface("java/util/Set", "contains", "(Ljava/lang/Object;)Z")
            ifeq(skip)
            
            addLabel()
            dup()
            aLoad(0)
            getField(clazz.name, PLUGIN_NAME_FIELD, "Ljava/lang/String;")
            swap()
            invokeStatic(PluginProxy::class.internalName, "initializeHandles", "(Ljava/lang/String;Ljava/lang/Class;)V")
            
            add(skip)
        }
        
        insns.insert(superCall, buildInsnList(initCall))
        insns.insert(libraryCall, buildInsnList(initCall))
    }
    
}