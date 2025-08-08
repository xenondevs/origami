@file:Suppress("unused")

package xyz.xenondevs.origami.util

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.reflect.KClass

@DslMarker
annotation class InsnDsl

/**
 * DSL builder for ASMs [InsnList]
 */
@InsnDsl
class InsnBuilder {
    
    /**
     * The current [instruction list][InsnList]
     */
    val list = InsnList()
    
    /**
     * Adds the [instruction][insn] to the current [list]
     */
    fun add(insn: AbstractInsnNode) = list.add(insn)
    
    /**
     * Adds the [instruction list][InsnList] to the current [list]
     */
    fun add(insnList: InsnList) = list.add(insnList)
    
    /**
     * Adds a [label][LabelNode] to the current [list]
     */
    fun addLabel(): LabelNode = LabelNode().also(::add)
    
    /**
     * Adds a zero operand instruction
     */
    private fun insnOf(opcode: Int) = add(InsnNode(opcode))
    
    /**
     * Do nothing
     */
    fun nop() = insnOf(NOP)
    
    /**
     * Throw an exception
     */
    fun aThrow() = insnOf(ATHROW)
    
    //<editor-fold desc="Constant Values" defaultstate=”collapsed”>
    
    // ------------------------- //
    //      Constant Values      //
    // ------------------------- //
    
    /**
     * Adds null on top of the stack
     */
    fun constNull() = insnOf(ACONST_NULL)
    
    /**
     * Adds the [int] on top of the stack
     */
    fun ldc(int: Int) = add(int.toLdcInsn())
    
    /**
     * Adds the [long] on top of the stack
     */
    fun ldc(long: Long) = add(long.toLdcInsn())
    
    /**
     * Adds the [float] on top of the stack
     */
    fun ldc(float: Float) = add(float.toLdcInsn())
    
    /**
     * Adds the [double] on top of the stack
     */
    fun ldc(double: Double) = add(double.toLdcInsn())
    
    /**
     * Adds the [string] on top of the stack
     */
    fun ldc(string: String) = add(LdcInsnNode(string))
    
    /**
     * Adds the [type] on top of the stack
     */
    fun ldc(type: Type) = add(LdcInsnNode(type))
    
    /**
     * Adds the [handle] on top of the stack
     */
    fun ldc(handle: Handle) = add(LdcInsnNode(handle))
    //</editor-fold>
    
    //<editor-fold desc="Local Variables" defaultstate=”collapsed”>
    
    // ------------------------- //
    //      Local Variables      //
    // ------------------------- //
    
    /**
     * Stores an [Int] in the local variables at [index]
     */
    fun iStore(index: Int) = add(VarInsnNode(ISTORE, index))
    
    /**
     * Loads an [Int] from the local variables at [index]
     */
    fun iLoad(index: Int) = add(VarInsnNode(ILOAD, index))
    
    /**
     * Stores a [Long] in the local variables at [index]
     */
    fun lStore(index: Int) = add(VarInsnNode(LSTORE, index))
    
    /**
     * Loads a [Long] from the local variables at [index]
     */
    fun lLoad(index: Int) = add(VarInsnNode(LLOAD, index))
    
    /**
     * Stores a [Float] in the local variables at [index]
     */
    fun fStore(index: Int) = add(VarInsnNode(FSTORE, index))
    
    /**
     * Loads a [Float] from the local variables at [index]
     */
    fun fLoad(index: Int) = add(VarInsnNode(FLOAD, index))
    
    /**
     * Stores a [Double] in the local variables at [index]
     */
    fun dStore(index: Int) = add(VarInsnNode(DSTORE, index))
    
    /**
     * Loads a [Double] from the local variables at [index]
     */
    fun dLoad(index: Int) = add(VarInsnNode(DLOAD, index))
    
    /**
     * Stores an object reference in the local variables at [index]
     */
    fun aStore(index: Int) = add(VarInsnNode(ASTORE, index))
    
    /**
     * Loads an object reference from the local variables at [index]
     */
    fun aLoad(index: Int) = add(VarInsnNode(ALOAD, index))
    
    //</editor-fold>
    
    //<editor-fold desc="Array Manipulation" defaultstate=”collapsed”>
    
    // ------------------------- //
    //    Array Manipulation     //
    // ------------------------- //
    
    fun iastore() = insnOf(IASTORE)
    fun iaload() = insnOf(IALOAD)
    fun lastore() = insnOf(LASTORE)
    fun laload() = insnOf(LALOAD)
    fun fastore() = insnOf(FASTORE)
    fun faload() = insnOf(FALOAD)
    fun dastore() = insnOf(DASTORE)
    fun daload() = insnOf(DALOAD)
    fun aastore() = insnOf(AASTORE)
    fun aaload() = insnOf(AALOAD)
    fun bastore() = insnOf(BASTORE)
    fun baload() = insnOf(BALOAD)
    fun castore() = insnOf(CASTORE)
    fun caload() = insnOf(CALOAD)
    fun sastore() = insnOf(SASTORE)
    fun saload() = insnOf(SALOAD)
    
    //</editor-fold>
    
    //<editor-fold desc="Stack Manipulation" defaultstate=”collapsed”>
    
    // ------------------------- //
    //    Stack Manipulation     //
    // ------------------------- //
    
    fun pop() = insnOf(POP)
    fun pop2() = insnOf(POP2)
    fun dup() = insnOf(DUP)
    fun dupx1() = insnOf(DUP_X1)
    fun dupx2() = insnOf(DUP_X2)
    fun dup2() = insnOf(DUP2)
    fun dup2x1() = insnOf(DUP2_X1)
    fun dup2x2() = insnOf(DUP2_X2)
    fun swap() = insnOf(SWAP)
    
    //</editor-fold>
    
    //<editor-fold desc="Arithmetic & Bitwise" defaultstate=”collapsed”>
    
    // ------------------------- //
    //   Arithmetic & Bitwise    //
    // ------------------------- //
    
    fun iadd() = insnOf(IADD)
    fun isub() = insnOf(ISUB)
    fun imul() = insnOf(IMUL)
    fun idiv() = insnOf(IDIV)
    fun irem() = insnOf(IREM)
    fun ineg() = insnOf(INEG)
    fun ishl() = insnOf(ISHL)
    fun ishr() = insnOf(ISHR)
    fun iushr() = insnOf(IUSHR)
    fun iand() = insnOf(IAND)
    fun ior() = insnOf(IOR)
    fun ixor() = insnOf(IXOR)
    fun iinc(index: Int, amount: Int) = add(IincInsnNode(index, amount))
    
    fun ladd() = insnOf(LADD)
    fun lsub() = insnOf(LSUB)
    fun lmul() = insnOf(LMUL)
    fun ldiv() = insnOf(LDIV)
    fun lrem() = insnOf(LREM)
    fun lneg() = insnOf(LNEG)
    fun lshl() = insnOf(LSHL)
    fun lshr() = insnOf(LSHR)
    fun lushr() = insnOf(LUSHR)
    fun lor() = insnOf(LOR)
    fun land() = insnOf(LAND)
    fun lxor() = insnOf(LXOR)
    
    fun fadd() = insnOf(FADD)
    fun fsub() = insnOf(FSUB)
    fun fmul() = insnOf(FMUL)
    fun fdiv() = insnOf(FDIV)
    fun frem() = insnOf(FREM)
    fun fneg() = insnOf(FNEG)
    
    fun dadd() = insnOf(DADD)
    fun dsub() = insnOf(DSUB)
    fun dmul() = insnOf(DMUL)
    fun ddiv() = insnOf(DDIV)
    fun drem() = insnOf(DREM)
    fun dneg() = insnOf(DNEG)
    
    //</editor-fold>
    
    //<editor-fold desc="Type Conversion" defaultstate=”collapsed”>
    
    // ------------------------- //
    //      Type Conversion      //
    // ------------------------- //
    
    fun i2l() = insnOf(I2L)
    fun i2f() = insnOf(I2F)
    fun i2d() = insnOf(I2D)
    fun i2b() = insnOf(I2B)
    fun i2c() = insnOf(I2C)
    fun i2s() = insnOf(I2S)
    fun l2i() = insnOf(L2I)
    fun l2f() = insnOf(L2F)
    fun l2d() = insnOf(L2D)
    fun f2i() = insnOf(F2I)
    fun f2l() = insnOf(F2L)
    fun f2d() = insnOf(F2D)
    fun d2i() = insnOf(D2I)
    fun d2l() = insnOf(D2L)
    fun d2f() = insnOf(D2F)
    
    //</editor-fold>
    
    //<editor-fold desc="Comparisons" defaultstate=”collapsed”>
    
    // ------------------------- //
    //        Comparisons        //
    // ------------------------- //
    
    fun lcmp() = insnOf(LCMP)
    fun fcmpl() = insnOf(FCMPL)
    fun fcmpg() = insnOf(FCMPG)
    fun dcmpl() = insnOf(DCMPL)
    fun dcmpg() = insnOf(DCMPG)
    
    //</editor-fold>
    
    //<editor-fold desc="Jumps" defaultstate=”collapsed”>
    
    // ------------------------- //
    //           Jumps           //
    // ------------------------- //
    
    private fun jumpOf(opcode: Int, label: LabelNode) = add(JumpInsnNode(opcode, label))
    
    fun ifeq(label: LabelNode) = jumpOf(IFEQ, label)
    fun ifne(label: LabelNode) = jumpOf(IFNE, label)
    fun iflt(label: LabelNode) = jumpOf(IFLT, label)
    fun ifle(label: LabelNode) = jumpOf(IFLE, label)
    fun ifge(label: LabelNode) = jumpOf(IFGE, label)
    fun ifgt(label: LabelNode) = jumpOf(IFGT, label)
    
    fun if_icmplt(label: LabelNode) = jumpOf(IF_ICMPLT, label)
    fun if_icmple(label: LabelNode) = jumpOf(IF_ICMPLE, label)
    fun if_icmpge(label: LabelNode) = jumpOf(IF_ICMPGE, label)
    fun if_icmpgt(label: LabelNode) = jumpOf(IF_ICMPGT, label)
    fun if_icmpeq(label: LabelNode) = jumpOf(IF_ICMPEQ, label)
    fun if_icmpne(label: LabelNode) = jumpOf(IF_ICMPNE, label)
    
    fun goto(label: LabelNode) = jumpOf(GOTO, label)
    fun jsr(label: LabelNode) = jumpOf(JSR, label)
    
    fun ifnull(label: LabelNode) = jumpOf(IFNULL, label)
    fun ifnonnull(label: LabelNode) = jumpOf(IFNONNULL, label)
    
    //</editor-fold>
    
    //<editor-fold desc="Returns" defaultstate=”collapsed”>
    
    // ------------------------- //
    //          Returns          //
    // ------------------------- //
    
    fun ireturn() = insnOf(IRETURN)
    fun lreturn() = insnOf(LRETURN)
    fun freturn() = insnOf(FRETURN)
    fun dreturn() = insnOf(DRETURN)
    fun areturn() = insnOf(ARETURN)
    fun _return() = insnOf(RETURN)
    
    //</editor-fold>
    
    //<editor-fold desc="Fields" defaultstate=”collapsed”>
    
    // ------------------------- //
    //          Fields           //
    // ------------------------- //
    
    private fun getField(opcode: Int, owner: String, name: String, desc: String) =
        add(FieldInsnNode(opcode, owner, name, desc))
    
    private fun getField(opcode: Int, field: Field) =
        getField(opcode, field.declaringClass.internalName, field.name, Type.getDescriptor(field.type))
    
    fun getStatic(owner: String, name: String, desc: String) = getField(GETSTATIC, owner, name, desc)
    fun getStatic(field: Field) = getField(GETSTATIC, field)
    fun putStatic(owner: String, name: String, desc: String) = getField(PUTSTATIC, owner, name, desc)
    fun putStatic(field: Field) = getField(PUTSTATIC, field)
    fun getField(owner: String, name: String, desc: String) = getField(GETFIELD, owner, name, desc)
    fun getField(field: Field) = getField(GETFIELD, field)
    fun putField(owner: String, name: String, desc: String) = getField(PUTFIELD, owner, name, desc)
    fun putField(field: Field) = getField(PUTFIELD, field)
    
    //</editor-fold>
    
    //<editor-fold desc="Methods" defaultstate=”collapsed”>
    
    // ------------------------- //
    //          Methods          //
    // ------------------------- //
    
    private fun invoke(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) =
        add(MethodInsnNode(opcode, owner, name, desc, isInterface))
    
    private fun invoke(opcode: Int, method: Method, isInterface: Boolean) =
        invoke(opcode, method.declaringClass.internalName, method.name, Type.getMethodDescriptor(method), isInterface)
    
    fun invokeVirtual(owner: String, name: String, desc: String, isInterface: Boolean = false) =
        invoke(INVOKEVIRTUAL, owner, name, desc, isInterface)
    
    fun invokeVirtual(method: Method, isInterface: Boolean = false) =
        invoke(INVOKEVIRTUAL, method, isInterface)
    
    fun invokeSpecial(owner: String, name: String, desc: String, isInterface: Boolean = false) =
        invoke(INVOKESPECIAL, owner, name, desc, isInterface)
    
    fun invokeSpecial(method: Method, isInterface: Boolean = false) =
        invoke(INVOKESPECIAL, method, isInterface)
    
    fun invokeSpecial(constructor: Constructor<*>, isInterface: Boolean = false) =
        invoke(INVOKESPECIAL, constructor.declaringClass.internalName, "<init>", Type.getConstructorDescriptor(constructor), isInterface)
    
    fun invokeStatic(owner: String, name: String, desc: String, isInterface: Boolean = false) =
        invoke(INVOKESTATIC, owner, name, desc, isInterface)
    
    fun invokeStatic(method: Method, isInterface: Boolean = false) =
        invoke(INVOKESTATIC, method, isInterface)
    
    fun invokeInterface(owner: String, name: String, desc: String, isInterface: Boolean = true) =
        invoke(INVOKEINTERFACE, owner, name, desc, isInterface)
    
    fun invokeInterface(method: Method, isInterface: Boolean = true) =
        invoke(INVOKEINTERFACE, method, isInterface)
    
    //</editor-fold>
    
    //<editor-fold desc="Types" defaultstate=”collapsed”>
    
    // ------------------------- //
    //           Types           //
    // ------------------------- //
    
    fun new(type: String) = add(TypeInsnNode(NEW, type))
    fun new(type: KClass<*>) = add(TypeInsnNode(NEW, type.internalName))
    fun new(type: Class<*>) = add(TypeInsnNode(NEW, type.internalName))
    fun newArray(type: Int) = add(IntInsnNode(NEWARRAY, type))
    fun aNewArray(desc: String) = add(TypeInsnNode(ANEWARRAY, desc))
    fun newBooleanArray() = newArray(T_BOOLEAN)
    fun newCharArray() = newArray(T_CHAR)
    fun newByteArray() = newArray(T_BYTE)
    fun newShortArray() = newArray(T_SHORT)
    fun newIntArray() = newArray(T_INT)
    fun newLongArray() = newArray(T_LONG)
    fun newFloatArray() = newArray(T_FLOAT)
    fun newDoubleArray() = newArray(T_DOUBLE)
    
    fun arraylength() = insnOf(ARRAYLENGTH)
    
    fun checkCast(desc: String) = add(TypeInsnNode(CHECKCAST, desc))
    fun checkCast(type: KClass<*>) = add(TypeInsnNode(CHECKCAST, type.internalName))
    fun checkCast(type: Class<*>) = add(TypeInsnNode(CHECKCAST, type.internalName))
    fun instanceOf(desc: String) = add(TypeInsnNode(INSTANCEOF, desc))
    fun instanceOf(type: KClass<*>) = add(TypeInsnNode(INSTANCEOF, type.internalName))
    fun instanceOf(type: Class<*>) = add(TypeInsnNode(INSTANCEOF, type.internalName))
    
    //</editor-fold>
    
    //<editor-fold desc="Types" defaultstate=”collapsed”>
    
    // ------------------------- //
    //          Locking          //
    // ------------------------- //
    
    fun monitorEnter() = insnOf(MONITORENTER)
    
    fun monitorExit() = insnOf(MONITOREXIT)
    
    // </editor-fold>
    
    fun print(text: String) {
        getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
        ldc(text)
        invokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
    }
    
    fun printFromStack(type: String = "Ljava/lang/Object;") {
        getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
        if (type == "J" || type == "D") {
            dupx2() // insert the PrintStream under the 2 32-bit values
            pop() // Pop the old PrintStream
        } else {
            swap() // Swap the PrintStream and the value
        }
        invokeVirtual("java/io/PrintStream", "println", "($type)V")
    }
}

@InsnDsl
inline fun buildInsnList(builder: InsnBuilder.() -> Unit) = InsnBuilder().apply(builder).list

inline fun buildClass(className: String, defaultCtor: Boolean = false, builder: ClassNode.() -> Unit): ClassNode =
    ClassNode().apply {
        this.name = className
        this.superName = "java/lang/Object"
        this.access = ACC_PUBLIC
        version = V21
        if (defaultCtor) {
            methods.add(MethodNode().apply {
                access = ACC_PUBLIC
                name = "<init>"
                desc = "()V"
                instructions = buildInsnList {
                    addLabel()
                    aLoad(0)
                    invokeSpecial("java/lang/Object", "<init>", "()V")
                    _return()
                }
            })
        }
        builder()
    }

@InsnDsl
fun insnListOf(vararg insns: AbstractInsnNode) = InsnList().apply { insns.forEach { add(it) } }