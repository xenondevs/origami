package xyz.xenondevs.origami.asm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import xyz.xenondevs.origami.util.WriteOnlyArrayList
import java.io.BufferedInputStream
import java.util.*
import java.util.jar.JarFile

/**
 * A virtual class path with lazy lookups mostly used for inheritance to support [PatchClassWriter.getCommonSuperClass]
 * without actually loading classes.
 */
class LazyClassPath(val files: WriteOnlyArrayList<JarFile>, private val includeAllCode: Boolean = false) {
    
    /**
     * A list of classes whose instructions should not be skipped because of patching purposes
     */
    val codeNeeded = HashSet<String>()
    
    /**
     * A map of all classes that have been loaded
     */
    private val classes = HashMap<String, ClassNode>()
    
    /**
     * A map of all inheritance trees that have been generated
     */
    private val inheritanceTrees = HashMap<ClassNode, InheritanceTree>()
    
    /**
     * A map of known packages (The first 3 parts) to their URLs
     */
    private val packageHints = HashMap<String, ArrayList<JarFile>>()
    
    fun getClass(name: String): ClassNode? {
        val internalName = name.replace(".", "/")
        classes[internalName]?.let { return it }
        
        val stream = findClassStream(internalName) ?: return null
        val options = if (includeAllCode || codeNeeded.contains(internalName)) ClassReader.SKIP_FRAMES else ClassReader.SKIP_CODE
        val wrapper = ClassNode().also { ClassReader(stream).accept(it, options) }
        classes[internalName] = wrapper
        return wrapper
    }
    
    fun findClassStream(internalName: String): BufferedInputStream? {
        val entryName = "$internalName.class"
        val projectPackage = internalName.split("/").take(3).joinToString("/")
        
        var stream = packageHints[projectPackage]?.firstNotNullOfOrNull { jar ->
            val entry = jar.getJarEntry(entryName) ?: return@firstNotNullOfOrNull null
            jar.getInputStream(entry).buffered()
        }
        
        if (stream == null) {
            val resource = ClassLoader.getSystemClassLoader().parent.getResource(entryName)
                ?: javaClass.classLoader.getResource(entryName)
            stream = resource?.openStream()?.buffered()
        }
        
        if (stream == null) {
            stream = files.firstNotNullOfOrNull { jar ->
                val entry = jar.getJarEntry(entryName) ?: return@firstNotNullOfOrNull null
                packageHints.computeIfAbsent(projectPackage) { ArrayList() }.add(jar)
                jar.getInputStream(entry).buffered()
            }
        }
        
        return stream
    }
    
    fun findResourceStream(path: String): BufferedInputStream? {
        ClassLoader.getSystemResourceAsStream(path)?.let { return it.buffered() }
        javaClass.classLoader.getResourceAsStream(path)?.let { return it.buffered() }
        
        return files.firstNotNullOfOrNull { jar ->
            jar.getJarEntry(path)?.let { jar.getInputStream(it).buffered() }
        }
    }
    
    fun getTree(clazz: ClassNode, knownSubClasses: List<ClassNode> = emptyList()): InheritanceTree {
        if (clazz !in inheritanceTrees)
            return addInheritanceTree(clazz, knownSubClasses)
        
        val inheritanceTree = inheritanceTrees[clazz]!!
        if (knownSubClasses.isNotEmpty()) {
            inheritanceTree.subClasses += knownSubClasses
            inheritanceTree.superClasses.forEach { superClass ->
                // The tree has to exist because of the recursive calls in addInheritanceTree
                val superTree = inheritanceTrees[superClass]!!
                superTree.subClasses += knownSubClasses
            }
        }
        return inheritanceTree
    }
    
    private fun addInheritanceTree(clazz: ClassNode, knownSubClasses: List<ClassNode>): InheritanceTree {
        val tree = InheritanceTree(clazz)
        tree.subClasses.addAll(knownSubClasses)
        val subClasses = if (knownSubClasses.isNotEmpty())
            knownSubClasses.toMutableList().apply { add(clazz) }
        else Collections.singletonList(clazz)
        
        clazz.superName?.let { superName ->
            val superClass = getClass(superName)
                ?: throw ClassNotFoundException("Could not resolve super class $superName of $clazz")
            tree.superClasses += superClass
            val superTree = getTree(superClass, subClasses)
            tree.superClasses += superTree.superClasses
        }
        
        clazz.interfaces?.let { interfaces ->
            interfaces.forEach { i ->
                val superClass = getClass(i)
                    ?: throw ClassNotFoundException("Could not resolve interface $i of $clazz")
                tree.superClasses += superClass
                val superTree = getTree(superClass, subClasses)
                tree.superClasses += superTree.superClasses
            }
        }
        
        inheritanceTrees[clazz] = tree
        return tree
    }
    
}