package com.qmusic.wear.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ------------------------- JSON 读取快捷方式（本地持久化用） -------------------------

fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

fun JsonElement?.asLongOrNull(default: Long = 0L): Long = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong() ?: default
    else -> default
}

fun JsonElement?.asIntOrNull(default: Int = 0): Int = asLongOrNull(default.toLong()).toInt()

fun JsonObject.getObj(key: String): JsonObject? = (this[key] as? JsonObject)

fun JsonObject.getArr(key: String): JsonArray? = (this[key] as? JsonArray)

fun JsonObject.str(key: String): String = this[key].asStringOrNull().orEmpty()

fun JsonObject.long(key: String): Long = this[key].asLongOrNull()

fun JsonObject.int(key: String): Int = this[key].asIntOrNull()
