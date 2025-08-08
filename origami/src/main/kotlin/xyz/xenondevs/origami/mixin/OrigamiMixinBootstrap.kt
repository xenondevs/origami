package xyz.xenondevs.origami.mixin

import org.spongepowered.asm.service.IMixinServiceBootstrap

class OrigamiMixinBootstrap : IMixinServiceBootstrap {
    override fun getName(): String {
        return "Origami"
    }
    
    override fun getServiceClassName(): String {
        return "xyz.xenondevs.origami.mixin.OrigamiMixinService"
    }
    
    override fun bootstrap() {
    }
}