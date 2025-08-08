package xyz.xenondevs.origami

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerReader.AccessType
import net.fabricmc.accesswidener.AccessWidenerVisitor

data class ProjectAccessWidener(
    val config: AccessWidenerConfig,
    val accessWidener: AccessWidener
) {
    
    fun hasClass(name: String): Boolean {
        return accessWidener.targets.contains(
            name
                .replace("/", ".")
                .removeSuffix(".class")
                .removeSuffix(".java")
        )
    }
    
}

private val ORDER = listOf(
    Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE,
    Modifier.Keyword.ABSTRACT, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL,
    Modifier.Keyword.TRANSIENT, Modifier.Keyword.VOLATILE,
    Modifier.Keyword.SYNCHRONIZED, Modifier.Keyword.NATIVE, Modifier.Keyword.STRICTFP
)

private fun MutableSet<Modifier.Keyword>.setVisibility(to: Modifier.Keyword) {
    removeAll(listOf(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE))
    add(to)
}

class AccessWidenerConfig : AccessWidenerVisitor {
    
    val classes = HashMap<String, AccessChange>()
    val fields = HashMap<ClassMember, AccessChange>()
    val methods = HashMap<ClassMember, AccessChange>()
    
    /**
     * Set with simple className.memberName entries to quickly check if a class member might be relevant for the access
     * widener. Useful to avoid resolving the full descriptor during source processing for every single class member if
     * only a few are relevant.
     */
    val precheck = HashSet<String>()
    
    override fun visitClass(name: String, access: AccessType, transitive: Boolean) {
        val current = classes.getOrPut(name) { AccessChange(TargetType.CLASS) }
        current.update(access)
    }
    
    override fun visitMethod(owner: String, name: String, descriptor: String, access: AccessType, transitive: Boolean) {
        val current = methods.getOrPut(ClassMember(owner, name, descriptor)) { AccessChange(TargetType.METHOD) }
        current.update(access)
        precheck.add("$owner.$name()")
    }
    
    override fun visitField(owner: String, name: String, descriptor: String, access: AccessType, transitive: Boolean) {
        val current = fields.getOrPut(ClassMember(owner, name, descriptor)) { AccessChange(TargetType.FIELD) }
        current.update(access)
        precheck.add("$owner.$name")
    }
    
    class AccessChange(val targetType: TargetType) {
        
        var accessible: Boolean = false
        var extendable: Boolean = false
        var mutable: Boolean = false
        
        fun update(accessType: AccessType) {
            when (accessType) {
                AccessType.ACCESSIBLE -> accessible = true
                AccessType.MUTABLE -> mutable = true
                AccessType.EXTENDABLE -> extendable = true
            }
        }
        
        fun apply(mods: NodeList<Modifier>, node: Node, owner: Node?) {
            val kw: MutableSet<Modifier.Keyword> = mods.mapTo(mutableSetOf()) { it.keyword }
            
            when (targetType) {
                TargetType.CLASS -> {
                    if (accessible || extendable) kw.setVisibility(Modifier.Keyword.PUBLIC)
                    if (extendable) kw.remove(Modifier.Keyword.FINAL)
                }
                
                TargetType.METHOD -> {
                    val wasPrivate = kw.contains(Modifier.Keyword.PRIVATE)
                    val isStatic = kw.contains(Modifier.Keyword.STATIC)
                    val inInterface = owner is ClassOrInterfaceDeclaration && owner.isInterface
                    val isConstructor = node is ConstructorDeclaration
                    
                    when {
                        accessible && extendable -> {
                            kw.setVisibility(Modifier.Keyword.PUBLIC)
                            kw.remove(Modifier.Keyword.FINAL)
                        }
                        
                        accessible -> {
                            kw.setVisibility(Modifier.Keyword.PUBLIC)
                            if (wasPrivate && !isStatic && !inInterface && !isConstructor)
                                kw.add(Modifier.Keyword.FINAL)
                        }
                        
                        extendable -> {
                            kw.setVisibility(Modifier.Keyword.PROTECTED)
                            kw.remove(Modifier.Keyword.FINAL)
                        }
                    }
                }
                
                TargetType.FIELD -> {
                    val ownerIsInterface = owner is ClassOrInterfaceDeclaration && owner.isInterface
                    if (accessible) kw.setVisibility(Modifier.Keyword.PUBLIC)
                    if (mutable && !(ownerIsInterface && kw.contains(Modifier.Keyword.STATIC))) {
                        kw.remove(Modifier.Keyword.FINAL)
                    }
                }
            }
            
            mods.clear()
            ORDER.forEach { if (it in kw) mods.add(Modifier(it)) }
        }
        
    }
    
    data class ClassMember(
        val owner: String,
        val name: String,
        val descriptor: String,
    )
    
    enum class TargetType {
        CLASS,
        FIELD,
        METHOD
    }
}