package xyz.xenondevs.origami.util

import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.PrimitiveType.Primitive
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.VoidType
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory

fun TypeDeclaration<*>.getInternalName(pkg: String): String {
    val names = generateSequence(this) { it.parentNode.orElse(null) as? TypeDeclaration<*> }
        .map { it.nameAsString }
        .toList()
        .asReversed()
    
    return buildString {
        if (pkg.isNotEmpty()) append(pkg).append('/')
        append(names.joinToString("$"))
    }
}

fun Type.toDescriptor(parserFacade: JavaParserFacade): String {
    return when (this) {
        is ArrayType -> "[${elementType.toDescriptor(parserFacade)}"
        is PrimitiveType -> when (this.type) {
            Primitive.BOOLEAN -> "Z"
            Primitive.BYTE -> "B"
            Primitive.CHAR -> "C"
            Primitive.DOUBLE -> "D"
            Primitive.FLOAT -> "F"
            Primitive.INT -> "I"
            Primitive.LONG -> "J"
            Primitive.SHORT -> "S"
            null -> throw IllegalArgumentException("Received null primitive type in $this")
        }
        
        is VoidType -> "V"
        
        is ClassOrInterfaceType -> {
            if (typeArguments.isPresent) {
                // type args are irrelevant for descriptors
                val rawName = this.toString().substringBefore('<')
                
                val ctx = JavaParserFactory.getContext(this, parserFacade.typeSolver)
                val ref = ctx.solveType(rawName, emptyList())
                check(ref.isSolved) { "Cannot resolve type $rawName in context $ctx" }
                return ref.correspondingDeclaration.toDescriptor()
            }
            
            val resolved = parserFacade.convertToUsage(this)
            
            if (resolved.isReferenceType)
                return resolved.asReferenceType().typeDeclaration.get().toDescriptor()
            
            if (resolved.isTypeVariable) {
                val typeParam = resolved.asTypeParameter()
                
                val bound = typeParam.bounds
                    .firstOrNull { it.type.isReferenceType }
                    ?.type?.asReferenceType()
                    ?.typeDeclaration
                    ?.get()
                
                // if no bound is found, assume it's an object type
                return bound?.toDescriptor() ?: "Ljava/lang/Object;"
            } else {
                throw IllegalArgumentException("Unsupported type: $this")
            }
        }
        
        else -> throw IllegalArgumentException("Unsupported type: $this")
    }
}

fun ResolvedTypeDeclaration.toDescriptor(): String {
    val pkg = packageName.replace('.', '/')
    val name = qualifiedName.drop(packageName.length + 1).replace('.', '$')
    return buildString {
        append('L')
        if (pkg.isNotEmpty()) append(pkg).append('/')
        append(name).append(';')
    }
}

fun CallableDeclaration<*>.getDescriptor(parserFacade: JavaParserFacade): String {
    val params = parameters.joinToString("") { it.type.toDescriptor(parserFacade) }
    val returnType = if (this is MethodDeclaration) type.toDescriptor(parserFacade) else "V"
    return "($params)$returnType"
}