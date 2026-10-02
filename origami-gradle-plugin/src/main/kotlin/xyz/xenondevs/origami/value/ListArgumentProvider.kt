package xyz.xenondevs.origami.value

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

internal abstract class ListArgumentProvider : CommandLineArgumentProvider {
    
    @get:Input
    abstract val args: ListProperty<String>
    
    override fun asArguments(): Iterable<String> =
        args.get()
    
}