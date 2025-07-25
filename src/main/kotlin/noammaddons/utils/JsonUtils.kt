package noammaddons.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import net.minecraft.util.ResourceLocation
import noammaddons.NoammAddons.Companion.mc
import java.io.*


object JsonUtils {
    val gson = Gson()
    val gsonBuilder = GsonBuilder().setPrettyPrinting().create()
    val json = Json { ignoreUnknownKeys = true }

    fun JsonObject.getObj(key: String) = this[key]?.jsonObject
    fun JsonObject.getString(key: String) = this[key]?.jsonPrimitive?.content
    fun JsonObject.getInt(key: String) = this[key]?.jsonPrimitive?.int
    fun JsonObject.getDouble(key: String) = this[key]?.jsonPrimitive?.double


    fun stringToJson(s: String): JsonObject {
        return try {
            parseToJsonElement(s).jsonObject
        }
        catch (e: Exception) {
            e.printStackTrace()
            mc.shutdown()
            throw UnsupportedEncodingException("Failed to parse JSON: ${e.message}")
        }
    }

    fun <T> fromJson(file: File, clazz: Class<T>): T? {
        return try {
            FileReader(file).use { reader -> gson.fromJson(reader, clazz) }
        }
        catch (e: Exception) {
            println("[PogObject] Failed to parse JSON: Type: ${clazz.javaClass.simpleName} ${e.message}")
            null
        }
    }

    fun toJson(file: File, data: Any) {
        FileWriter(file).use { writer ->
            gsonBuilder.toJson(data, writer)
        }
    }

    fun readJsonFile(resourcePath: String): JsonObject? {
        val resourceLocation = ResourceLocation(resourcePath)

        return try {
            mc.resourceManager.getResource(resourceLocation).inputStream.use { inputStream ->
                val reader = InputStreamReader(inputStream)
                Gson().fromJson(reader, JsonObject::class.java)
            }
        }
        catch (e: Exception) {
            println("[PogObject] Failed to read JSON: ${e.message}")
            null
        }
    }
}