package xyz.xenondevs.origami

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerReader.AccessType
import net.fabricmc.accesswidener.AccessWidenerVisitor

data class ProjectAccessWidener(
    val config: AccessWidenerConfig,
    val accessWidener: AccessWidener
) {
    
    fun isEmpty(): Boolean = accessWidener.targets.isEmpty()
    
    fun hasClass(name: String): Boolean {
        return accessWidener.targets.contains(
            name
                .replace("/", ".")
                .removeSuffix(".class")
                .removeSuffix(".java")
        )
    }
    
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
    
    data class AccessChange(val targetType: TargetType) {
        
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