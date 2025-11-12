package xyz.xenondevs.origami.task.patch.source

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import xyz.xenondevs.origami.AccessWidenerConfig
import xyz.xenondevs.origami.AccessWidenerConfig.AccessChange
import xyz.xenondevs.origami.AccessWidenerConfig.TargetType
import xyz.xenondevs.origami.task.patch.type.SourceVisitor
import xyz.xenondevs.origami.util.getDescriptor
import xyz.xenondevs.origami.util.getInternalName
import xyz.xenondevs.origami.util.toDescriptor

private val ORDER = listOf(
    Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE,
    Modifier.Keyword.ABSTRACT, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL,
    Modifier.Keyword.TRANSIENT, Modifier.Keyword.VOLATILE,
    Modifier.Keyword.SYNCHRONIZED, Modifier.Keyword.NATIVE, Modifier.Keyword.STRICTFP
)

class WidenerSourceVisitor(
    private val widenerConfig: AccessWidenerConfig
) : SourceVisitor {
    
    override fun visit(cu: CompilationUnit, serverFacade: JavaParserFacade) {
        val currentPackage = cu.packageDeclaration
            .map { it.nameAsString.replace('.', '/') }
            .orElse("")
        
        cu.findAll(BodyDeclaration::class.java).forEach { decl ->
            val owner = ((decl as? TypeDeclaration<*>)
                ?: decl.findAncestor(TypeDeclaration::class.java).orElseThrow()
                ).getInternalName(currentPackage)
            
            when (decl) {
                is TypeDeclaration<*> -> {
                    val ch = widenerConfig.classes[owner] ?: return@forEach
                    ch.apply(decl.modifiers, decl, null)
                }
                
                is FieldDeclaration -> visitField(decl, serverFacade, owner)
                is MethodDeclaration -> visitMethod(decl, serverFacade, owner)
                is ConstructorDeclaration -> visitMethod(decl, serverFacade, owner, "<init>")
            }
        }
    }
    
    // TODO records
    private fun visitField(decl: FieldDeclaration, serverFacade: JavaParserFacade, owner: String) {
        data class VarChange(val varDecl: VariableDeclarator, val ch: AccessChange)
        
        val toWiden = mutableListOf<VarChange>()
        
        decl.variables.forEach { v ->
            if (!widenerConfig.precheck.contains("${owner}.${v.nameAsString}")) return@forEach
            
            val key = AccessWidenerConfig.ClassMember(
                owner,
                v.nameAsString,
                decl.commonType.toDescriptor(serverFacade)
            )
            val ch = widenerConfig.fields[key] ?: return@forEach
            toWiden.add(VarChange(v, ch))
        }
        
        if (toWiden.isEmpty()) return
        
        val parent = decl.parentNode.orElse(null)
        val groups = toWiden.groupBy(VarChange::ch, VarChange::varDecl)
        
        if (toWiden.size == decl.variables.size && groups.size == 1) {
            toWiden[0].ch.apply(decl.modifiers, decl, parent)
            return
        }
        
        val parentMembers = (parent as TypeDeclaration<*>).members
        val anchorIndex = parentMembers.indexOf(decl)
        var insertOffset = 1
        val annotationsToCopy = decl.annotations.map { it.clone() }
        // TODO if one of the access changes is redundant, the fields would still get moved into their own declaration
        groups.forEach { (ch, vars) ->
            val newField = FieldDeclaration()
            newField.modifiers.addAll(decl.modifiers)
            annotationsToCopy.forEach { newField.addAnnotation(it.clone()) }
            
            for (v in vars) {
                val newVar = VariableDeclarator(decl.commonType.clone(), v.nameAsString)
                v.initializer.ifPresent { newVar.setInitializer(it.clone()) }
                newField.addVariable(newVar)
                decl.variables.remove(v)
            }
            
            ch.apply(newField.modifiers, newField, parent)
            parentMembers.add(anchorIndex + insertOffset, newField)
            ++insertOffset
        }
        
        if (decl.variables.isEmpty()) decl.remove()
    }
    
    private fun visitMethod(decl: CallableDeclaration<*>, serverFacade: JavaParserFacade, owner: String, nameOverride: String? = null) {
        val name = nameOverride ?: decl.nameAsString
        if (!widenerConfig.precheck.contains("${owner}.$name()")) return
        
        val key = AccessWidenerConfig.ClassMember(
            owner,
            name,
            decl.getDescriptor(serverFacade)
        )
        val ch = widenerConfig.methods[key] ?: return
        ch.apply(decl.modifiers, decl, decl.parentNode.orElse(null))
    }
    
    private fun MutableSet<Modifier.Keyword>.setVisibility(to: Modifier.Keyword) {
        removeAll(listOf(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE))
        add(to)
    }
    
    fun AccessChange.apply(mods: NodeList<Modifier>, node: Node, owner: Node?) {
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