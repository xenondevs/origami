package xyz.xenondevs.origami.jit

import org.spongepowered.asm.logging.ILogger
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

// TODO: Separate between pre and post Minecraft init to allow proper logging before Minecraft is initialized.

private val ADAPTER_CONSTRUCTOR: MethodHandle = run {
    val lookup = OrigamiJit.minecraftLookup!!
    val adapterClass = lookup.findClass("xyz.xenondevs.origami.Slf4jLoggerAdapter")
    val loggerClass = lookup.findClass("org.slf4j.Logger")
    lookup.findConstructor(adapterClass, MethodType.methodType(Void.TYPE, String::class.java, loggerClass))
}

private val GET_SLF4J_LOGGER: MethodHandle = run {
    val lookup = OrigamiJit.minecraftLookup!!
    val loggerClass = lookup.findClass("org.slf4j.Logger")
    val factoryClass = lookup.findClass("org.slf4j.LoggerFactory")
    lookup.findStatic(factoryClass, "getLogger", MethodType.methodType(loggerClass, String::class.java))
}

private val loggers = ConcurrentHashMap<String, ILogger>()

/**
 * Gets an [ILogger] based on SLF4J's `LoggerFactory.create` (from the Minecraft classpath).
 */
fun getLoggerAdapter(name: String): ILogger = loggers.computeIfAbsent(name) {
    ADAPTER_CONSTRUCTOR.invoke("Minecraft", GET_SLF4J_LOGGER.invoke(name)) as ILogger
}