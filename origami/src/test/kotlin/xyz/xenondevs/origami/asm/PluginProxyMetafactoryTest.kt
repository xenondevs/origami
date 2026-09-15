package xyz.xenondevs.origami.asm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.xenondevs.origami.LookupProxy
import xyz.xenondevs.origami.PluginProxy
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginProxyMetafactoryTest {
    
    @Test
    fun `lambda is created across parent and plugin classloaders`() {
        val pluginLoader = DefiningClassLoader(javaClass.classLoader)
        val functionClass = pluginLoader.define(
            FUNCTION_CLASS,
            createFunctionInterface()
        )
        val lookupClass = pluginLoader.define(
            LOOKUP_CLASS,
            createLookupClass()
        )
        LookupProxy.initPluginLookup(PLUGIN_NAME, lookupClass)
        
        val targetLookup = LambdaProxyTarget.lookup()
        val target = targetLookup.findStatic(
            LambdaProxyTarget::class.java,
            "identity",
            MethodType.methodType(Any::class.java, Any::class.java)
        )
        val callSite = PluginProxy.proxyLocalMetafactory(
            targetLookup,
            "apply",
            MethodType.methodType(Any::class.java),
            PLUGIN_NAME,
            "()L$FUNCTION_INTERNAL_NAME;",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            target,
            "(Ljava/lang/Object;)Ljava/lang/Object;",
        )
        
        val function = callSite.target.invokeWithArguments()
        val result = functionClass
            .getMethod("apply", Any::class.java)
            .invoke(function, "cross-loader")
        
        assertTrue(functionClass.isInstance(function))
        assertEquals("cross-loader", result)
    }
    
    private fun createFunctionInterface(): ByteArray {
        return ClassWriter(0).apply {
            visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                FUNCTION_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null
            )
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null
            ).visitEnd()
            visitEnd()
        }.toByteArray()
    }
    
    private fun createLookupClass(): ByteArray {
        return ClassWriter(0).apply {
            visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
                LOOKUP_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null
            )
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "getLookup",
                "()Ljava/lang/invoke/MethodHandles\$Lookup;",
                null,
                null
            ).apply {
                visitCode()
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "lookup",
                    "()Ljava/lang/invoke/MethodHandles\$Lookup;",
                    false
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
    }
    
    private class DefiningClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(name: String, bytecode: ByteArray): Class<*> =
            defineClass(name, bytecode, 0, bytecode.size)
    }
    
    private companion object {
        const val PLUGIN_NAME = "DynamicInvokerCrossLoaderTest"
        const val FUNCTION_CLASS = "test.plugin.CrossLoaderFunction"
        const val FUNCTION_INTERNAL_NAME = "test/plugin/CrossLoaderFunction"
        const val LOOKUP_CLASS = "test.plugin.LookupProvider"
        const val LOOKUP_INTERNAL_NAME = "test/plugin/LookupProvider"
    }
}
