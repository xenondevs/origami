package xyz.xenondevs.origami.asm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.util.isInterface

private const val OBJECT_INTERNAL = "java/lang/Object"

class PatchClassWriter(val classPath: LazyClassPath, flags: Int = COMPUTE_FRAMES) : ClassWriter(flags) {
    
    constructor(flags: Int = COMPUTE_FRAMES) : this(Origami.instance.minecraftClasspath, flags)
    
    override fun getCommonSuperClass(type1: String, type2: String): String {
        if (OBJECT_INTERNAL == type1 || OBJECT_INTERNAL == type2)
            return OBJECT_INTERNAL
        
        val type1Class = classPath.getClass(type1)!!
        val type2Class = classPath.getClass(type2)!!
        
        val firstCommon = findCommonSuperName(type1Class, type2Class)
        val secondCommon = findCommonSuperName(type2Class, type1Class)
        
        if (OBJECT_INTERNAL != firstCommon)
            return firstCommon
        if (OBJECT_INTERNAL != secondCommon)
            return secondCommon
        
        return getCommonSuperClass(
            type1Class.superName,
            type2Class.superName
        )
    }
    
    private fun findCommonSuperName(class1: ClassNode, class2: ClassNode): String {
        if (isAssignableFrom(class1, class2))
            return class1.name
        if (isAssignableFrom(class2, class1))
            return class2.name
        
        if (class1.isInterface() || class2.isInterface())
            return OBJECT_INTERNAL
        
        var new = classPath.getClass(class1.superName)!!
        while (!isAssignableFrom(new, class2)) {
            new = classPath.getClass(new.superName)!!
        }
        
        return new.name
    }
    
    fun isAssignableFrom(clazz1: ClassNode, clazz2: ClassNode): Boolean {
        if (clazz1 == clazz2)
            return true
        
        return classPath.getTree(clazz2).superClasses.contains(clazz1)
    }
    
}