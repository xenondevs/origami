package xyz.xenondevs.origami.asm;

import java.lang.invoke.MethodHandles;

public final class LambdaProxyTarget {
    
    private LambdaProxyTarget() {
    }
    
    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }
    
    @SuppressWarnings("unused")
    private static Object identity(Object value) {
        return value;
    }
}
