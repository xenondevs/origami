package xyz.xenondevs.origami.mixin

import org.spongepowered.asm.service.IGlobalPropertyService
import org.spongepowered.asm.service.IPropertyKey

class Blackboard : IGlobalPropertyService {
    
    override fun resolveKey(name: String): IPropertyKey {
        return keys.computeIfAbsent(name) { OrigamiPropertyKey(name) }
    }
    
    override fun <T : Any> getProperty(key: IPropertyKey): T? {
        return getProperty(key, null)
    }
    
    override fun setProperty(key: IPropertyKey, value: Any) {
        blackboard[key] = value
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getProperty(key: IPropertyKey, defaultValue: T?): T? {
        return blackboard[key] as? T? ?: defaultValue
    }
    
    override fun getPropertyString(key: IPropertyKey, defaultValue: String?): String? {
        return getProperty(key, defaultValue)
    }
    
    data class OrigamiPropertyKey(val name: String) : IPropertyKey {
        override fun toString(): String {
            return name
        }
    }
    
    companion object {
        val keys = mutableMapOf<String, IPropertyKey>()
        val blackboard = mutableMapOf<IPropertyKey, Any>()
    }
    
}