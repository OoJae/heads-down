package xyz.headsdown.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Test fixtures: the Python-generated vectors and model files, and the shipped assets. */
object TestResources {
    fun text(path: String): String =
        TestResources::class.java.getResource(path)?.readText() ?: error("missing test resource $path")

    fun json(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    /** A file under this module (Gradle runs unit tests with the module as working directory). */
    fun moduleFile(relative: String): File = File(System.getProperty("user.dir"), relative)

    fun asset(name: String): File = moduleFile("src/main/assets/foreman/$name")

    fun window(input: JsonObject): MotionWindow {
        val t = input["t_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val xyz = input["xyz"]!!.jsonArray
        val samples = t.indices.map { i ->
            val row = xyz[i].jsonArray
            AccelSample(t[i], row.f(0), row.f(1), row.f(2))
        }
        return MotionWindow(input["trigger_ns"]!!.jsonPrimitive.long, samples)
    }

    /** JSON number -> double -> float (what the phone's parser does); null -> NaN. */
    private fun JsonArray.f(i: Int): Float = this[i].let { if (it is JsonNull) Float.NaN else it.jsonPrimitive.double.toFloat() }

    fun doubles(e: JsonElement): DoubleArray = e.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
}
