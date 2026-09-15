package xyz.xenondevs.origami.asm

import org.objectweb.asm.Handle
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicInvokerTest {
    
    @Test
    fun `plugin allocation and cast are removed`() {
        val clazz = transform(
            TypeInsnNode(Opcodes.NEW, PLUGIN_TYPE),
            InsnNode(Opcodes.DUP),
            TypeInsnNode(Opcodes.CHECKCAST, PLUGIN_TYPE)
        )
        
        assertTrue(clazz.methods.single().instructions.toArray().isEmpty())
    }
    
    @Test
    fun `plugin allocation without dup is rejected`() {
        val clazz = classWith(TypeInsnNode(Opcodes.NEW, PLUGIN_TYPE), InsnNode(Opcodes.POP))
        
        val error = assertFailsWith<IllegalStateException> {
            DynamicInvoker.transform(clazz, PLUGIN_NAME)
        }
        
        assertEquals("Unknown object allocation pattern. Expected DUP after NEW", error.message)
    }
    
    @Test
    fun `plugin array and instanceof instructions are rewritten`() {
        val clazz = transform(
            TypeInsnNode(Opcodes.ANEWARRAY, PLUGIN_TYPE),
            TypeInsnNode(Opcodes.INSTANCEOF, PLUGIN_TYPE)
        )
        val instructions = clazz.methods.single().instructions.toArray()
        
        assertEquals("java/lang/Object", assertIs<TypeInsnNode>(instructions[0]).desc)
        assertIndy(
            assertIs(instructions[1]),
            "(Ljava/lang/Object;)Z",
            "proxyInstanceOf",
            PLUGIN_NAME,
            PLUGIN_TYPE
        )
    }
    
    @Test
    fun `current class and its inner classes are unchanged`() {
        val outer = TypeInsnNode(Opcodes.CHECKCAST, CURRENT_CLASS)
        val inner = TypeInsnNode(Opcodes.CHECKCAST, "$CURRENT_CLASS\$Inner")
        
        val instructions = transform(outer, inner).methods.single().instructions.toArray()
        
        assertSame(outer, instructions[0])
        assertSame(inner, instructions[1])
    }
    
    @Test
    fun `class sharing current class prefix is still remapped`() {
        val similarlyNamedClass = TypeInsnNode(Opcodes.CHECKCAST, "${CURRENT_CLASS}Helper")
        
        val instructions = transform(similarlyNamedClass).methods.single().instructions.toArray()
        
        assertTrue(instructions.isEmpty())
    }
    
    @Test
    fun `static virtual and interface plugin calls are rewritten`() {
        val clazz = transform(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PLUGIN_TYPE,
                "staticCall",
                "(Ljava/util/List;[IL$PLUGIN_TYPE;I)[L$PLUGIN_TYPE;",
                false
            ),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, PLUGIN_TYPE, "virtualCall", "(I)L$PLUGIN_TYPE;", false),
            MethodInsnNode(Opcodes.INVOKEINTERFACE, PLUGIN_INTERFACE, "interfaceCall", "()V", true)
        )
        val instructions = clazz.methods.single().instructions.toArray()
        
        assertIndy(
            assertIs(instructions[0]),
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)[Ljava/lang/Object;",
            "proxyMethod",
            PLUGIN_NAME,
            PLUGIN_TYPE,
            "(Ljava/util/List;[IL$PLUGIN_TYPE;I)[L$PLUGIN_TYPE;",
            1
        )
        assertIndy(
            assertIs(instructions[1]),
            "(Ljava/lang/Object;I)Ljava/lang/Object;",
            "proxyMethod",
            PLUGIN_NAME,
            PLUGIN_TYPE,
            "(I)L$PLUGIN_TYPE;",
            0
        )
        assertIndy(
            assertIs(instructions[2]),
            "(Ljava/lang/Object;)V",
            "proxyMethod",
            PLUGIN_NAME,
            PLUGIN_INTERFACE,
            "()V",
            0
        )
    }
    
    @Test
    fun `plugin constructor call is rewritten`() {
        val instruction = transform(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                PLUGIN_TYPE,
                "<init>",
                "(Ljava/util/List;L$PLUGIN_TYPE;)V",
                false
            )
        ).methods.single().instructions.single()
        
        val indy = assertIs<InvokeDynamicInsnNode>(instruction)
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", indy.desc)
        assertEquals("proxyConstructor", indy.bsm.name)
        assertEquals(
            listOf(PLUGIN_NAME, PLUGIN_TYPE, "(Ljava/util/List;L$PLUGIN_TYPE;)V"),
            indy.bsmArgs.toList()
        )
    }

    @Test
    fun `erased plugin cast verifies when passed to visible proxy parameter`() {
        val skipPluginCode = LabelNode()
        val end = LabelNode()
        val clazz = classWith(
            VarInsnNode(Opcodes.ALOAD, 0),
            JumpInsnNode(Opcodes.IFNULL, end),
            VarInsnNode(Opcodes.ALOAD, 0),
            TypeInsnNode(Opcodes.INSTANCEOF, PLUGIN_TYPE),
            JumpInsnNode(Opcodes.IFEQ, skipPluginCode),
            VarInsnNode(Opcodes.ALOAD, 0),
            TypeInsnNode(Opcodes.CHECKCAST, PLUGIN_TYPE),
            VarInsnNode(Opcodes.ASTORE, 1),
            VarInsnNode(Opcodes.ALOAD, 1),
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PLUGIN_OWNER,
                "consume",
                "(Ljava/util/List;)V",
                false
            ),
            skipPluginCode,
            end,
            InsnNode(Opcodes.RETURN),
            methodDesc = "(Ljava/lang/Object;)V"
        )

        DynamicInvoker.transform(clazz, PLUGIN_NAME)

        val proxyCall = clazz.methods.single().instructions.toArray()
            .filterIsInstance<InvokeDynamicInsnNode>()
            .single { it.name == "consume" }
        assertEquals("(Ljava/lang/Object;)V", proxyCall.desc)

        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        clazz.accept(writer)
        val loadedClass = VerifyingClassLoader(javaClass.classLoader)
            .defineAndResolve(clazz.name.replace('/', '.'), writer.toByteArray())
        loadedClass.getMethod("test", Any::class.java).invoke(null, null)
    }
    
    @Test
    fun `plugin types in current class calls are remapped`() {
        val original = MethodInsnNode(
            Opcodes.INVOKESTATIC,
            CURRENT_CLASS,
            "method",
            "(L$PLUGIN_TYPE;)[[L$PLUGIN_TYPE;",
            false
        )
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
        assertEquals("(Ljava/lang/Object;)[[Ljava/lang/Object;", original.desc)
    }
    
    @Test
    fun `every plugin field opcode is rewritten`() {
        val clazz = transform(
            FieldInsnNode(Opcodes.GETFIELD, PLUGIN_TYPE, "a", "L$PLUGIN_TYPE;"),
            FieldInsnNode(Opcodes.PUTFIELD, PLUGIN_TYPE, "b", "Ljava/lang/String;"),
            FieldInsnNode(Opcodes.GETSTATIC, PLUGIN_TYPE, "c", "L$PLUGIN_TYPE;"),
            FieldInsnNode(Opcodes.PUTSTATIC, PLUGIN_TYPE, "d", "I")
        )
        val instructions = clazz.methods.single().instructions.toArray().map { assertIs<InvokeDynamicInsnNode>(it) }
        
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", instructions[0].desc)
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)V", instructions[1].desc)
        assertEquals("()Ljava/lang/Object;", instructions[2].desc)
        assertEquals("(I)V", instructions[3].desc)
        instructions.forEachIndexed { index, indy ->
            assertEquals("proxyField", indy.bsm.name)
            assertEquals(('a'.code + index).toChar().toString(), indy.name)
            assertEquals(listOf(PLUGIN_NAME, PLUGIN_TYPE, listOf("L$PLUGIN_TYPE;", "Ljava/lang/String;", "L$PLUGIN_TYPE;", "I")[index], listOf(Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.GETSTATIC, Opcodes.PUTSTATIC)[index]), indy.bsmArgs.toList())
        }
    }
    
    @Test
    fun `plugin array field on current class preserves dimensions`() {
        val original = FieldInsnNode(Opcodes.GETFIELD, CURRENT_CLASS, "value", "[L$PLUGIN_TYPE;")
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
        assertEquals("[Ljava/lang/Object;", original.desc)
    }
    
    @Test
    fun `visible method and field instructions are unchanged`() {
        val method = MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "length",
            "()I",
            false
        )
        val field = FieldInsnNode(
            Opcodes.GETSTATIC,
            "java/lang/Integer",
            "MAX_VALUE",
            "I"
        )
        
        val instructions = transform(method, field).methods.single().instructions.toArray()
        
        assertSame(method, instructions[0])
        assertSame(field, instructions[1])
    }
    
    @Test
    fun `plugin multi array preserves dimensions`() {
        val original = MultiANewArrayInsnNode("[[L$PLUGIN_TYPE;", 2)
        
        transform(original)
        
        assertEquals("[[Ljava/lang/Object;", original.desc)
    }
    
    @Test
    fun `visible multi array descriptor is unchanged`() {
        val original = MultiANewArrayInsnNode("[[Ljava/lang/String;", 2)
        
        transform(original)
        
        assertEquals("[[Ljava/lang/String;", original.desc)
    }
    
    @Test
    fun `method and field declarations are remapped`() {
        val clazz = classWith(methodDesc = "(L$PLUGIN_TYPE;)[L$PLUGIN_TYPE;")
        clazz.fields.add(FieldNode(Opcodes.ACC_PRIVATE, "value", "[[L$PLUGIN_TYPE;", null, null))
        
        DynamicInvoker.transform(clazz, PLUGIN_NAME)
        
        assertEquals("(Ljava/lang/Object;)[Ljava/lang/Object;", clazz.methods.single().desc)
        assertEquals("[[Ljava/lang/Object;", clazz.fields.single().desc)
    }
    
    @Test
    fun `pre mixin lambda with plugin factory keeps target handle remappable`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            CURRENT_CLASS,
            "lambdaBody",
            "(Ljava/util/List;Ljava/util/Map;)Ljava/util/Map;",
            false
        )
        val originalFactoryDesc = "(Ljava/util/List;)Lkotlin/jvm/functions/Function1;"
        val indy = lambdaIndy(originalFactoryDesc, target, "(Ljava/util/Map;)Ljava/util/Map;")
        
        val transformed = assertIs<InvokeDynamicInsnNode>(transform(indy).methods.single().instructions.single())
        
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", transformed.desc)
        assertEquals("proxyLocalMetafactory", transformed.bsm.name)
        assertEquals(PLUGIN_NAME, transformed.bsmArgs[0])
        assertEquals(originalFactoryDesc, transformed.bsmArgs[1])
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", transformed.bsmArgs[2])
        assertEquals(target, transformed.bsmArgs[3])
        assertEquals("(Ljava/util/Map;)Ljava/util/Map;", transformed.bsmArgs[4])
    }
    
    @Test
    fun `current class lambda metadata is remapped in place when factory is visible`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            CURRENT_CLASS,
            "lambda",
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            false
        )
        val indy = lambdaIndy("()Ljava/util/function/Function;", target, "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;")
        
        val transformed = assertIs<InvokeDynamicInsnNode>(transform(indy).methods.single().instructions.single())
        val transformedTarget = assertIs<Handle>(transformed.bsmArgs[1])
        
        assertEquals("java/lang/invoke/LambdaMetafactory", transformed.bsm.owner)
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", transformedTarget.desc)
        assertEquals(Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), transformed.bsmArgs[0])
        assertEquals(Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), transformed.bsmArgs[2])
    }
    
    @Test
    fun `lambda targeting plugin class is proxied`() {
        val target = Handle(Opcodes.H_INVOKEVIRTUAL, PLUGIN_TYPE, "apply", "(I)L$PLUGIN_TYPE;", false)
        val indy = lambdaIndy("()Ljava/util/function/IntFunction;", target, "(I)L$PLUGIN_TYPE;")
        
        val transformed = assertIs<InvokeDynamicInsnNode>(transform(indy).methods.single().instructions.single())
        
        assertEquals("proxyMetafactory", transformed.bsm.name)
        assertEquals("()Ljava/util/function/IntFunction;", transformed.desc)
        assertEquals(PLUGIN_TYPE, transformed.bsmArgs[3])
        assertEquals(Opcodes.H_INVOKEVIRTUAL, transformed.bsmArgs[7])
    }
    
    @Test
    fun `fully visible lambda is unchanged`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/util/Objects",
            "nonNull",
            "(Ljava/lang/Object;)Z",
            false
        )
        val original = lambdaIndy(
            "()Ljava/util/function/Predicate;",
            target,
            "(Ljava/lang/Object;)Z"
        )
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
        assertSame(target, original.bsmArgs[1])
    }
    
    @Test
    fun `visible lambda target with plugin dynamic type is proxied`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/util/Objects",
            "nonNull",
            "(Ljava/lang/Object;)Z",
            false
        )
        val original = lambdaIndy(
            "()Ljava/util/function/Predicate;",
            target,
            "(L$PLUGIN_TYPE;)Z"
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        
        assertEquals("proxyMetafactory", transformed.bsm.name)
        assertEquals("(L$PLUGIN_TYPE;)Z", transformed.bsmArgs[6])
    }
    
    @Test
    fun `current class alt metafactory metadata is remapped in place`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            CURRENT_CLASS,
            "lambda",
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            false
        )
        val original = altLambdaIndy(
            "()Ljava/util/function/Function;",
            target,
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            4,
            1,
            Type.getMethodType("(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;")
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        
        assertSame(original, transformed)
        assertEquals("java/lang/invoke/LambdaMetafactory", transformed.bsm.owner)
        assertEquals("altMetafactory", transformed.bsm.name)
        assertEquals(
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            assertIs<Handle>(transformed.bsmArgs[1]).desc
        )
        assertEquals(
            Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
            transformed.bsmArgs[5]
        )
    }

    @Test
    fun `pre mixin alt metafactory proxy keeps target handle remappable`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            CURRENT_CLASS,
            "lambdaBody",
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            false
        )
        val original = altLambdaIndy(
            "()Lkotlin/jvm/functions/Function1;",
            target,
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            0
        )

        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        val remappableTarget = assertIs<Handle>(transformed.bsmArgs[3])

        assertEquals("proxyLocalAltMetafactory", transformed.bsm.name)
        assertEquals(CURRENT_CLASS, remappableTarget.owner)
        assertEquals("lambdaBody", remappableTarget.name)
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", remappableTarget.desc)
    }
    
    @Test
    fun `alt metafactory with plugin factory marker and bridge is proxied`() {
        val target = Handle(
            Opcodes.H_INVOKESTATIC,
            PLUGIN_TYPE,
            "lambda",
            "(Ljava/util/List;L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            false
        )
        val flags = 2 or 4
        val original = altLambdaIndy(
            "(Ljava/util/List;)Lkotlin/jvm/functions/Function1;",
            target,
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            flags,
            1,
            Type.getObjectType(PLUGIN_INTERFACE),
            1,
            Type.getMethodType("(L$PLUGIN_TYPE;)Ljava/lang/Object;")
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", transformed.desc)
        assertEquals("proxyAltMetafactory", transformed.bsm.name)
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", transformed.bsmArgs[2])
        assertEquals(flags, transformed.bsmArgs[8])
        assertEquals(
            listOf("I1", "CL$PLUGIN_INTERFACE;", "I1", "M(L$PLUGIN_TYPE;)Ljava/lang/Object;"),
            transformed.bsmArgs.drop(9)
        )
    }
    
    @Test
    fun `existing Origami proxy is unchanged`() {
        val original = InvokeDynamicInsnNode(
            "alreadyProxied",
            "()Ljava/lang/Object;",
            bootstrap("xyz/xenondevs/origami/PluginProxy", "proxyMethod")
        )
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
    }
    
    @Test
    fun `type switch with plugin and valid constant labels is proxied`() {
        val enumDesc = ConstantDynamic(
            "ENUM_VALUE",
            "Ljava/lang/Enum\$EnumDesc;",
            bootstrap("java/lang/invoke/ConstantBootstraps", "invoke")
        )
        val original = InvokeDynamicInsnNode(
            "typeSwitch",
            "(Ljava/lang/Object;I)I",
            bootstrap("java/lang/runtime/SwitchBootstraps", "typeSwitch"),
            Type.getObjectType(PLUGIN_TYPE),
            Type.getObjectType("java/lang/String"),
            "literal",
            42,
            enumDesc
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(transform(original).methods.single().instructions.single())
        
        assertEquals("proxySwitch", transformed.bsm.name)
        assertEquals(
            listOf(
                PLUGIN_NAME,
                "(Ljava/lang/Object;I)I",
                0,
                "CL$PLUGIN_TYPE;",
                "CLjava/lang/String;",
                "Sliteral",
                "I42",
                enumDesc
            ),
            transformed.bsmArgs.toList()
        )
    }
    
    @Test
    fun `type switch with visible and constant labels is unchanged`() {
        val original = InvokeDynamicInsnNode(
            "typeSwitch",
            "(Ljava/lang/Object;I)I",
            bootstrap("java/lang/runtime/SwitchBootstraps", "typeSwitch"),
            Type.getObjectType("java/lang/String"),
            "literal",
            42
        )
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
    }
    
    @Test
    fun `enum switch with plugin selector is proxied`() {
        val originalDesc = "(L$PLUGIN_TYPE;I)I"
        val original = InvokeDynamicInsnNode(
            "enumSwitch",
            originalDesc,
            bootstrap("java/lang/runtime/SwitchBootstraps", "enumSwitch"),
            "FIRST",
            "SECOND"
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        
        assertEquals("(Ljava/lang/Object;I)I", transformed.desc)
        assertEquals("proxySwitch", transformed.bsm.name)
        assertEquals(
            listOf(PLUGIN_NAME, originalDesc, 1, "SFIRST", "SSECOND"),
            transformed.bsmArgs.toList()
        )
    }
    
    @Test
    fun `string concat descriptor is remapped without changing bootstrap arguments`() {
        val bootstrap = bootstrap("java/lang/invoke/StringConcatFactory", "makeConcatWithConstants")
        val original = InvokeDynamicInsnNode(
            "makeConcatWithConstants",
            "(L$PLUGIN_TYPE;)Ljava/lang/String;",
            bootstrap,
            "value=\u0001"
        )
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(original).methods.single().instructions.single()
        )
        
        assertSame(original, transformed)
        assertSame(bootstrap, transformed.bsm)
        assertEquals("(Ljava/lang/Object;)Ljava/lang/String;", transformed.desc)
        assertEquals(listOf("value=\u0001"), transformed.bsmArgs.toList())
    }
    
    @Test
    fun `unknown bootstrap with plugin descriptor is unchanged`() {
        val original = InvokeDynamicInsnNode(
            "dynamic",
            "(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;",
            bootstrap("example/Bootstrap", "bootstrap"),
            Type.getObjectType(PLUGIN_TYPE)
        )
        
        val instruction = transform(original).methods.single().instructions.single()
        
        assertSame(original, instruction)
        assertEquals("(L$PLUGIN_TYPE;)L$PLUGIN_TYPE;", original.desc)
    }
    
    @Test
    fun `plugin class literal is replaced while visible literals are unchanged`() {
        val pluginLiteral = LdcInsnNode(Type.getObjectType(PLUGIN_TYPE))
        val primitiveLiteral = LdcInsnNode(Type.INT_TYPE)
        val currentLiteral = LdcInsnNode(Type.getObjectType(CURRENT_CLASS))
        val instructions = transform(pluginLiteral, primitiveLiteral, currentLiteral)
            .methods.single().instructions.toArray()
        
        assertIndy(
            assertIs(instructions[0]),
            "()Ljava/lang/Class;",
            "proxyClass",
            PLUGIN_NAME,
            PLUGIN_TYPE
        )
        assertSame(primitiveLiteral, instructions[1])
        assertSame(currentLiteral, instructions[2])
    }
    
    @Test
    fun `plugin array class literal is proxied with original descriptor`() {
        val pluginArrayLiteral = LdcInsnNode(Type.getType("[[L$PLUGIN_TYPE;"))
        
        val transformed = assertIs<InvokeDynamicInsnNode>(
            transform(pluginArrayLiteral).methods.single().instructions.single()
        )
        
        assertEquals("proxyClass", transformed.bsm.name)
        assertEquals(listOf(PLUGIN_NAME, "[[L$PLUGIN_TYPE;"), transformed.bsmArgs.toList())
    }
    
    private fun lambdaIndy(factoryDesc: String, target: Handle, dynamicDesc: String) =
        InvokeDynamicInsnNode(
            "invoke",
            factoryDesc,
            bootstrap(
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
            ),
            Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
            target,
            Type.getMethodType(dynamicDesc)
        )
    
    private fun altLambdaIndy(
        factoryDesc: String,
        target: Handle,
        dynamicDesc: String,
        flags: Int,
        vararg optionalArgs: Any
    ) = InvokeDynamicInsnNode(
        "invoke",
        factoryDesc,
        bootstrap(
            "java/lang/invoke/LambdaMetafactory",
            "altMetafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"
        ),
        Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
        target,
        Type.getMethodType(dynamicDesc),
        flags,
        *optionalArgs
    )
    
    private fun bootstrap(owner: String, name: String, desc: String = "()Ljava/lang/invoke/CallSite;") =
        Handle(Opcodes.H_INVOKESTATIC, owner, name, desc, false)
    
    private fun transform(vararg instructions: AbstractInsnNode): ClassNode =
        classWith(*instructions).also { DynamicInvoker.transform(it, PLUGIN_NAME) }
    
    private fun classWith(
        vararg instructions: AbstractInsnNode,
        methodDesc: String = "()V"
    ) = ClassNode().apply {
        version = Opcodes.V21
        access = Opcodes.ACC_PUBLIC
        name = CURRENT_CLASS
        superName = "java/lang/Object"
        methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", methodDesc, null, null).apply {
            instructions.forEach(this.instructions::add)
        })
    }
    
    private fun assertIndy(
        instruction: AbstractInsnNode,
        desc: String,
        bootstrapName: String,
        vararg bootstrapArgs: Any
    ) {
        val indy = assertIs<InvokeDynamicInsnNode>(instruction)
        assertEquals(desc, indy.desc)
        assertEquals("xyz/xenondevs/origami/PluginProxy", indy.bsm.owner)
        assertEquals(bootstrapName, indy.bsm.name)
        assertEquals(bootstrapArgs.toList(), indy.bsmArgs.toList())
    }

    private class VerifyingClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun defineAndResolve(name: String, bytecode: ByteArray): Class<*> {
            return defineClass(name, bytecode, 0, bytecode.size).also(::resolveClass)
        }
    }
    
    private companion object {
        const val CURRENT_CLASS = "example/plugin/mixin/IdMapperMixin"
        const val PLUGIN_TYPE = "example/plugin/PluginType"
        const val PLUGIN_INTERFACE = "example/plugin/PluginInterface"
        const val PLUGIN_OWNER = "example/plugin/PluginOwner"
        const val PLUGIN_NAME = "Nova"
    }
}
