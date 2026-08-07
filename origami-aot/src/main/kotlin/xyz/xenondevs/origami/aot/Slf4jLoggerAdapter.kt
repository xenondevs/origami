package xyz.xenondevs.origami.aot

import org.slf4j.Logger
import org.spongepowered.asm.logging.Level
import org.spongepowered.asm.logging.LoggerAdapterAbstract

class Slf4jLoggerAdapter(val logger: Logger) : LoggerAdapterAbstract(logger.name) {
    
    override fun getType() = "OrigamiAot"
    
    override fun catching(level: Level, t: Throwable?) {
        this.log(level, "Caught exception", t)
    }
    
    override fun <T : Throwable?> throwing(t: T?): T? {
        this.logger.error("Throwing exception", t)
        return t
    }
    
    override fun log(level: Level, message: String?, t: Throwable?) {
        when (level) {
            Level.FATAL, Level.ERROR -> this.logger.error(message, t)
            Level.WARN -> this.logger.warn(message, t)
            Level.INFO -> this.logger.info(message, t)
            Level.DEBUG -> this.logger.debug(message, t)
            Level.TRACE -> this.logger.trace(message, t)
        }
    }
    
    override fun log(level: Level, message: String?, vararg params: Any?) {
        when (level) {
            Level.FATAL, Level.ERROR -> this.logger.error(message, *params)
            Level.WARN -> this.logger.warn(message, *params)
            Level.INFO -> this.logger.info(message, *params)
            Level.DEBUG -> this.logger.debug(message, *params)
            Level.TRACE -> this.logger.trace(message, *params)
        }
    }
    
}