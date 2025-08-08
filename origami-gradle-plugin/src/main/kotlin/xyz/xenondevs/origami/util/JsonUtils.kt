package xyz.xenondevs.origami.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type
import java.nio.file.Path

val GSON: Gson = GsonBuilder().registerTypeAdapter(Path::class.java, PathDeserializer()).create()

class PathDeserializer : JsonDeserializer<Path?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Path? {
        return json?.asString?.let { Path.of(it) }
    }
}