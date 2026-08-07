package xyz.xenondevs.origami;

import org.slf4j.Logger;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/**
 * Adapter class for using a {@link Logger} as a {@link ILogger}.
 */
public final class Slf4jLoggerAdapter extends LoggerAdapterAbstract {
    
    private final String type;
    private final Logger logger;
    
    public Slf4jLoggerAdapter(String type, Logger logger) {
        super(logger.getName());
        this.type = type;
        this.logger = logger;
    }
    
    @Override
    public String getType() {
        return this.type;
    }
    
    @Override
    public void catching(Level level, Throwable t) {
        this.log(level, "Caught exception", t);
    }
    
    @Override
    public <T extends Throwable> T throwing(T t) {
        this.logger.error("Throwing exception", t);
        return t;
    }
    
    @Override
    public void log(Level level, String message, Throwable t) {
        switch (level) {
            case Level.FATAL, Level.ERROR -> this.logger.error(message, t);
            case Level.WARN -> this.logger.warn(message, t);
            case Level.INFO -> this.logger.info(message, t);
            case Level.DEBUG -> this.logger.debug(message, t);
            case Level.TRACE -> this.logger.trace(message, t);
        }
    }
    
    @Override
    public void log(Level level, String message, Object... params) {
        switch (level) {
            case Level.FATAL, Level.ERROR -> this.logger.error(message, params);
            case Level.WARN -> this.logger.warn(message, params);
            case Level.INFO -> this.logger.info(message, params);
            case Level.DEBUG -> this.logger.debug(message, params);
            case Level.TRACE -> this.logger.trace(message, params);
        }
    }
    
}
