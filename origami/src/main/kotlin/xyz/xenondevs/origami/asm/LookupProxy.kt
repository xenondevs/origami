package xyz.xenondevs.origami.asm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.util.buildClass
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.internalName
import java.lang.invoke.MethodHandles

private const val LOOKUP_PREFIX = "Origami\$LookupProxy"

// TODO: thread safety
object LookupProxy {
    
    class LookupClassDefinition(@JvmField val name: String, @JvmField val bytecode: ByteArray)
    
    private lateinit var MINECRAFT_LOOKUP: MethodHandles.Lookup
    
    private val pluginLookups = HashMap<String, MethodHandles.Lookup>()
    
    fun init() {
        val loader = Origami.instance.minecraftLoader
        val clazz = loader.createClass(LOOKUP_PREFIX, createLookupClass().bytecode)
        initPluginLookup(null, clazz)
    }
    
    @JvmStatic
    fun getLookupFor(pluginName: String? = null): MethodHandles.Lookup {
        if (pluginName == null) return MINECRAFT_LOOKUP
        
        return pluginLookups[pluginName]
            ?: throw IllegalStateException("Lookup for plugin '$pluginName' is not initialized.")
    }
    
    @JvmStatic
    fun initPluginLookup(pluginName: String?, lookupClass: Class<*>) {
        val lookup = lookupClass.getMethod("getLookup").invoke(null) as MethodHandles.Lookup
        if (pluginName == null) {
            MINECRAFT_LOOKUP = lookup
            return
        }
        
        if (pluginLookups.containsKey(pluginName)) {
            throw IllegalStateException("Lookup for plugin '$pluginName' is already initialized.")
        }
        pluginLookups[pluginName] = lookup
    }
    
    @JvmStatic
    fun createLookupClass(pluginName: String? = null): LookupClassDefinition {
        var className = LOOKUP_PREFIX
        if (pluginName != null)
            className += "$${pluginName.filter { it.isJavaIdentifierStart() }}" + System.currentTimeMillis()
        
        val clazz = buildClass(className) {
            access = access or Opcodes.ACC_FINAL
            
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
                name = "getLookup"
                desc = "()Ljava/lang/invoke/MethodHandles\$Lookup;"
                instructions = buildInsnList {
                    addLabel()
                    invokeStatic(MethodHandles::class.internalName, "lookup", "()Ljava/lang/invoke/MethodHandles\$Lookup;")
                    areturn()
                }
            })
        }
        
        val classWriter = PatchClassWriter(Origami.instance.minecraftClasspath, ClassWriter.COMPUTE_MAXS)
        clazz.accept(classWriter)
        return LookupClassDefinition(className, classWriter.toByteArray())
    }
    
}