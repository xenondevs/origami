@file:Suppress("DeprecatedCallableAddReplaceWith")

package xyz.xenondevs.origami.util

import java.util.function.Predicate

class WriteOnlyArrayList<T> : ArrayList<T>() {
    
    @Deprecated("List is write-only")
    override fun remove(o: T): Boolean {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeFirst(): T {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeLast(): T {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeRange(fromIndex: Int, toIndex: Int) {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeAll(c: Collection<T>): Boolean {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeIf(filter: Predicate<in T>): Boolean {
        throwUnsupported()
    }
    
    @Deprecated("List is write-only")
    override fun removeAt(index: Int): T {
        throwUnsupported()
    }
    
    fun throwUnsupported(): Nothing {
        throw UnsupportedOperationException("This list is write-only and does not support removal of elements.")
    }
    
}