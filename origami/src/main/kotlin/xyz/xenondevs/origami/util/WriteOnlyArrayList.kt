package xyz.xenondevs.origami.util

import java.util.function.Predicate

class WriteOnlyArrayList<T> : ArrayList<T>() {
    
    override fun remove(o: T): Boolean {
        throwUnsupported()
    }
    
    override fun removeFirst(): T {
        throwUnsupported()
    }
    
    override fun removeLast(): T {
        throwUnsupported()
    }
    
    override fun removeRange(fromIndex: Int, toIndex: Int) {
        throwUnsupported()
    }
    
    override fun removeAll(c: Collection<T>): Boolean {
        throwUnsupported()
    }
    
    override fun removeIf(filter: Predicate<in T>): Boolean {
        return super.removeIf(filter)
    }
    
    override fun removeAt(index: Int): T {
        throwUnsupported()
    }
    
    fun throwUnsupported(): Nothing {
        throw UnsupportedOperationException("This list is write-only and does not support removal of elements.")
    }
    
}