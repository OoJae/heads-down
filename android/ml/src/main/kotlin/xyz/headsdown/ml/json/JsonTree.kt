package xyz.headsdown.ml.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Strict little helpers over the kotlinx tree API: a malformed model file fails loudly at load. */
internal fun JsonObject.obj(key: String): JsonObject =
    (this[key] ?: throw IllegalArgumentException("missing '$key'")).jsonObject

internal fun JsonObject.arr(key: String): JsonArray =
    (this[key] ?: throw IllegalArgumentException("missing '$key'")).jsonArray

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("missing string '$key'")

internal fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.num(key: String): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("missing number '$key'")

internal fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("missing int '$key'")

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("missing long '$key'")

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("missing boolean '$key'")

internal fun JsonElement.doubles(): DoubleArray = jsonArray.let { a ->
    DoubleArray(a.size) { a[it].jsonPrimitive.doubleOrNull ?: throw IllegalArgumentException("not a number: ${a[it]}") }
}

internal fun JsonElement.ints(): IntArray = jsonArray.let { a ->
    IntArray(a.size) { a[it].jsonPrimitive.intOrNull ?: throw IllegalArgumentException("not an int: ${a[it]}") }
}

/** A number, or NaN for JSON null (vectors encode NaN inputs as null). */
internal fun JsonElement.doubleOrNaN(): Double = if (this is JsonNull) Double.NaN else jsonPrimitive.doubleOrNull ?: Double.NaN
