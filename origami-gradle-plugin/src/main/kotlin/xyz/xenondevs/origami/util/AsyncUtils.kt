package xyz.xenondevs.origami.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AsyncUtils {
    
    fun createPool(name: String, threadCount: Int = Runtime.getRuntime().availableProcessors()): ExecutorService {
        return Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "origami-$name").apply { isDaemon = true }
        }
    }
    
}