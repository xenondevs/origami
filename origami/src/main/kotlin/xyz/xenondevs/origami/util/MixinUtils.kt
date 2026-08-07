package xyz.xenondevs.origami.util

import org.spongepowered.asm.mixin.MixinEnvironment

fun finishMixinPhases() {
    val method = MixinEnvironment::class.java.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase::class.java)
    method.isAccessible = true
    method.invoke(null, MixinEnvironment.Phase.INIT)
    method.invoke(null, MixinEnvironment.Phase.DEFAULT)
}