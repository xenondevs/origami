package xyz.xenondevs.origami.mixin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.logging.Level
import org.spongepowered.asm.logging.LoggerAdapterAbstract
import xyz.xenondevs.origami.Origami
import xyz.xenondevs.origami.asm.LookupProxy
import xyz.xenondevs.origami.asm.PatchClassWriter
import xyz.xenondevs.origami.util.InsnBuilder
import xyz.xenondevs.origami.util.buildInsnList
import xyz.xenondevs.origami.util.internalName
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

private const val LOGGER_WRAPPER_NAME = "Origami\$LoggerWrapper"

/**
 * Lets mixins use Minecraft's logger implementation.
 *
 * TODO: Separate between pre and post Minecraft init to allow proper logging before Minecraft is initialized.
 */
object MixinLoggers {
    
    val wrapperConstructor: MethodHandle
    
    val factoryHandle: MethodHandle
    
    val loggers = ConcurrentHashMap<String, ILogger>()
    
    init {
        val minecraftLoader = Origami.instance.minecraftLoader
        
        val bytes = PatchClassWriter().also(createLoggerWrapper()::accept).toByteArray()
        val wrapperClass = minecraftLoader.createClass(LOGGER_WRAPPER_NAME, bytes)
        val loggerClass = Class.forName("org.slf4j.Logger", true, minecraftLoader)
        val factoryClass = Class.forName("org.slf4j.LoggerFactory", true, minecraftLoader)
        
        val lookup = LookupProxy.getLookupFor()
        
        wrapperConstructor = lookup.findConstructor(wrapperClass, MethodType.methodType(Void.TYPE, String::class.java, loggerClass))
        factoryHandle = lookup.findStatic(factoryClass, "getLogger", MethodType.methodType(loggerClass, String::class.java))
    }
    
    fun getLogger(name: String): ILogger {
        return loggers.computeIfAbsent(name) {
            val wrapper = wrapperConstructor.invoke(name, factoryHandle.invoke(name))
            wrapper as ILogger
        }
        
    }
    
    //<editor-fold desc="Logger Wrapper bytecode" defaultstate="collapsed">
    
    fun createLoggerWrapper(): ClassNode {
        return ClassNode().apply clazz@{
            name = LOGGER_WRAPPER_NAME
            superName = LoggerAdapterAbstract::class.internalName
            version = Opcodes.V21
            
            // private final Logger logger;
            fields.add(FieldNode(Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE, "logger", "Lorg/slf4j/Logger;", null, null))
            
            // ILogger#<init>()
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC
                name = "<init>"
                desc = "(Ljava/lang/String;Lorg/slf4j/Logger;)V"
                instructions = buildInsnList {
                    aLoad(0)
                    aLoad(1)
                    invokeSpecial(LoggerAdapterAbstract::class.internalName, "<init>", "(Ljava/lang/String;)V")
                    aLoad(0)
                    aLoad(2)
                    putField(this@clazz.name, "logger", "Lorg/slf4j/Logger;")
                    addLabel()
                    _return()
                }
            })
            
            // ILogger#getType()
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC
                name = "getType"
                desc = "()Ljava/lang/String;"
                instructions = buildInsnList {
                    ldc("Minecraft")
                    areturn()
                }
            })
            
            // ILogger#catching(level: Level, t: Throwable)
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC
                name = "catching"
                desc = "(L${Level::class.internalName};Ljava/lang/Throwable;)V"
                instructions = buildInsnList {
                    aLoad(0)
                    aLoad(1)
                    ldc("Caught exception")
                    aLoad(2)
                    invokeVirtual(this@clazz.name, "log", "(L${Level::class.internalName};Ljava/lang/String;Ljava/lang/Throwable;)V")
                    addLabel()
                    _return()
                }
            })
            
            val getInner: InsnBuilder.() -> Unit = {
                aLoad(0)
                getField(this@clazz.name, "logger", "Lorg/slf4j/Logger;")
            }
            
            // ILogger#throwing(T t): T
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC
                name = "throwing"
                desc = "(Ljava/lang/Throwable;)Ljava/lang/Throwable;"
                instructions = buildInsnList {
                    getInner()
                    ldc("Throwing exception")
                    aLoad(1)
                    invokeInterface("org/slf4j/Logger", "error", "(Ljava/lang/String;Ljava/lang/Throwable;)V")
                    addLabel()
                    aLoad(1)
                    areturn()
                }
            })
            
            
            val makeJumpTarget: (paramType: String, level: Level, returnLabel: LabelNode) -> Pair<InsnList, LabelNode> = { paramType, level, ret ->
                val label = LabelNode()
                buildInsnList {
                    add(label)
                    getInner()
                    aLoad(2)
                    aLoad(3)
                    invokeInterface("org/slf4j/Logger", if (level == Level.FATAL) "error" else level.name.lowercase(), "(Ljava/lang/String;$paramType)V")
                    goto(ret)
                } to label
            }
            
            val makeUnknownTarget: () -> Pair<InsnList, LabelNode> = {
                val label = LabelNode()
                buildInsnList {
                    add(label)
                    new("java/lang/IllegalArgumentException")
                    dup()
                    ldc("Unknown log level")
                    invokeSpecial("java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V")
                    aThrow()
                } to label
            }
            
            // ILogger#log(Level level, String message, Throwable t)
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC
                name = "log"
                desc = "(L${Level::class.internalName};Ljava/lang/String;Ljava/lang/Throwable;)V"
                instructions = buildInsnList {
                    val ret = LabelNode()
                    
                    val (unknownInsns, unknownLabel) = makeUnknownTarget()
                    val targets = Array<LabelNode?>(Level.entries.size) { null }
                    val targetInsns = Level.entries.mapIndexed { index, level ->
                        val (insns, label) = makeJumpTarget("Ljava/lang/Throwable;", level, ret)
                        targets[index] = label
                        insns
                    }
                    
                    aLoad(1)
                    invokeVirtual(Level::class.internalName, "ordinal", "()I")
                    add(TableSwitchInsnNode(
                        Level.FATAL.ordinal,
                        Level.TRACE.ordinal,
                        unknownLabel,
                        *targets
                    ))
                    
                    add(unknownInsns)
                    
                    targetInsns.forEach { add(it) }
                    
                    add(ret)
                    _return()
                }
            })
            
            // ILogger#log(Level level, String message, Object params...)
            methods.add(MethodNode().apply {
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_VARARGS
                name = "log"
                desc = "(L${Level::class.internalName};Ljava/lang/String;[Ljava/lang/Object;)V"
                instructions = buildInsnList {
                    val ret = LabelNode()
                    
                    val (unknownInsns, unknownLabel) = makeUnknownTarget()
                    val targets = Array<LabelNode?>(Level.entries.size) { null }
                    val targetInsns = Level.entries.mapIndexed { index, level ->
                        val (insns, label) = makeJumpTarget("[Ljava/lang/Object;", level, ret)
                        targets[index] = label
                        insns
                    }
                    
                    aLoad(1)
                    invokeVirtual(Level::class.internalName, "ordinal", "()I")
                    add(TableSwitchInsnNode(
                        Level.FATAL.ordinal,
                        Level.TRACE.ordinal,
                        unknownLabel,
                        *targets
                    ))
                    
                    add(unknownInsns)
                    
                    targetInsns.forEach { add(it) }
                    
                    add(ret)
                    _return()
                }
            })
            
        }
    }
    
    //</editor-fold>
}