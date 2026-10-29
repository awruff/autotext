package org.polyfrost.autotext.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

object MacroCodec {
    private const val CURRENT_VERSION = 1
    const val EMPTY_PAYLOAD = "{\"version\":1,\"macros\":[]}"
    const val MAX_PAYLOAD_LENGTH = 1_048_576
    const val MAX_MACROS = 100
    const val MAX_ID_LENGTH = 128
    private const val LEGACY_MAX_NAME_LENGTH = 48
    const val MAX_TEXT_LENGTH = 1024
    const val MAX_BINDING_INPUTS = 16
    const val MAX_KEY_CODE = 512
    const val MAX_MOUSE_BUTTON = 31
    private const val MAX_DELIVERY_LENGTH = 16

    fun encode(macros: List<MacroDefinition>): String {
        require(macros.size <= MAX_MACROS) { "Too many macros" }
        val root = JsonObject()
        root.addProperty("version", CURRENT_VERSION)
        root.add("macros", JsonArray().apply {
            macros.forEach { macro ->
                require(macro.id.length <= MAX_ID_LENGTH) { "Macro ID is too long" }
                require(macro.text.length <= MAX_TEXT_LENGTH) { "Macro text is too long" }
                require(macro.binding.keys.size <= MAX_BINDING_INPUTS) { "Too many keys in a binding" }
                require(macro.binding.mouseButtons.size <= MAX_BINDING_INPUTS) { "Too many mouse buttons in a binding" }
                require(macro.binding.keys.all { it in 1..MAX_KEY_CODE }) { "Invalid key code" }
                require(macro.binding.mouseButtons.all { it in 0..MAX_MOUSE_BUTTON }) { "Invalid mouse button" }
                require(macro.binding.modifiers in 0..15) { "Invalid modifier mask" }

                add(JsonObject().apply {
                    addProperty("id", macro.id)
                    addProperty("text", macro.text)
                    addProperty("enabled", macro.enabled)
                    addProperty("delivery", macro.delivery.name)
                    add("binding", JsonObject().apply {
                        add("keys", macro.binding.keys.toJsonArray())
                        add("mouseButtons", macro.binding.mouseButtons.toJsonArray())
                        addProperty("modifiers", macro.binding.modifiers.toInt())
                    })
                })
            }
        })
        return root.toString().also {
            require(it.length <= MAX_PAYLOAD_LENGTH) { "Encoded macro payload is too large" }
        }
    }

    fun decode(payload: String): MacroDecodeResult {
        if (payload.length > MAX_PAYLOAD_LENGTH) return MacroDecodeResult(emptyList(), false)
        if (payload.isBlank()) return MacroDecodeResult(emptyList(), true)
        if (!hasSafeSchema(payload)) return MacroDecodeResult(emptyList(), false)

        return runCatching {
            val root = JsonParser.parseString(payload).asJsonObject
            if (root.int("version") != CURRENT_VERSION) return MacroDecodeResult(emptyList(), false)
            val list = root.getAsJsonArray("macros") ?: return MacroDecodeResult(emptyList(), false)
            if (list.size() > MAX_MACROS) return MacroDecodeResult(emptyList(), false)

            val macros = ArrayList<MacroDefinition>(list.size())
            for (element in list) {
                macros.add(decodeMacro(element) ?: return MacroDecodeResult(emptyList(), false))
            }
            MacroDecodeResult(macros, true)
        }.getOrElse { MacroDecodeResult(emptyList(), false) }
    }

    private fun decodeMacro(value: JsonElement): MacroDefinition? = runCatching {
        val obj = value.asJsonObject
        val binding = obj.objectOrNull("binding")
        val id = obj.stringOrDefault("id", "").also {
            require(it.length <= MAX_ID_LENGTH) { "Macro ID is too long" }
        }
        val text = obj.stringOrDefault("text", "").also {
            require(it.length <= MAX_TEXT_LENGTH) { "Macro text is too long" }
        }
        val modifiers = binding?.intOrDefault("modifiers", 0) ?: 0
        require(modifiers in 0..15) { "Invalid modifier mask" }

        MacroDefinition(
            id = id,
            text = text,
            enabled = obj.booleanOrDefault("enabled", false),
            delivery = obj.optionalString("delivery")?.let(MacroDelivery::valueOf) ?: MacroDelivery.PREFILL,
            binding = MacroBinding(
                keys = binding?.intArrayOrDefault("keys", 1..MAX_KEY_CODE)?.distinct()?.toIntArray() ?: intArrayOf(),
                mouseButtons = binding?.intArrayOrDefault("mouseButtons", 0..MAX_MOUSE_BUTTON)?.distinct()?.toIntArray() ?: intArrayOf(),
                modifiers = modifiers.toByte(),
            ),
        )
    }.getOrNull()

    private fun IntArray.toJsonArray(): JsonArray = JsonArray().also { out ->
        forEach { out.add(it) }
    }

    private fun hasSafeSchema(payload: String): Boolean = runCatching {
        JsonReader(StringReader(payload)).use { reader ->
            reader.beginObject()
            val fields = HashSet<String>(2)
            while (reader.hasNext()) {
                when (val name = reader.nextName()) {
                    "version" -> {
                        if (!fields.add(name) || reader.peek() != JsonToken.NUMBER) return@use false
                        if (reader.nextString().toIntOrNull() != CURRENT_VERSION) return@use false
                    }
                    "macros" -> if (!fields.add(name) || !readMacros(reader)) return@use false
                    else -> return@use false
                }
            }
            reader.endObject()
            fields.size == 2 && reader.peek() == JsonToken.END_DOCUMENT
        }
    }.getOrDefault(false)

    private fun readMacros(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return false
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            if (++count > MAX_MACROS || !readMacro(reader)) return false
        }
        reader.endArray()
        return true
    }

    private fun readMacro(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return false
        reader.beginObject()
        val fields = HashSet<String>(6)
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "id" -> if (!fields.add(name) || !readBoundedString(reader, MAX_ID_LENGTH)) return false
                "name" -> if (!fields.add(name) || !readBoundedString(reader, LEGACY_MAX_NAME_LENGTH)) return false
                "text" -> if (!fields.add(name) || !readBoundedString(reader, MAX_TEXT_LENGTH)) return false
                "enabled" -> {
                    if (!fields.add(name) || reader.peek() != JsonToken.BOOLEAN) return false
                    reader.nextBoolean()
                }
                "delivery" -> if (!fields.add(name) || !readBoundedString(reader, MAX_DELIVERY_LENGTH)) return false
                "binding" -> if (!fields.add(name) || !readBinding(reader)) return false
                else -> return false
            }
        }
        reader.endObject()
        return true
    }

    private fun readBinding(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return false
        reader.beginObject()
        val fields = HashSet<String>(3)
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "keys" -> if (!fields.add(name) || !readInputArray(reader, 1..MAX_KEY_CODE)) return false
                "mouseButtons" -> if (!fields.add(name) || !readInputArray(reader, 0..MAX_MOUSE_BUTTON)) return false
                "modifiers" -> {
                    if (!fields.add(name) || reader.peek() != JsonToken.NUMBER) return false
                    val modifiers = reader.nextString().toIntOrNull() ?: return false
                    if (modifiers !in 0..15) return false
                }
                else -> return false
            }
        }
        reader.endObject()
        return true
    }

    private fun readInputArray(reader: JsonReader, validCodes: IntRange): Boolean {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return false
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            if (++count > MAX_BINDING_INPUTS || reader.peek() != JsonToken.NUMBER) return false
            val code = reader.nextString().toIntOrNull() ?: return false
            if (code !in validCodes) return false
        }
        reader.endArray()
        return true
    }

    private fun readBoundedString(reader: JsonReader, maxLength: Int): Boolean =
        reader.peek() == JsonToken.STRING && reader.nextString().length <= maxLength

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        require(!value.isJsonNull) { "$name cannot be null" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
        return value.asString
    }

    private fun JsonObject.stringOrDefault(name: String, default: String): String = optionalString(name) ?: default

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean {
        val value = get(name) ?: return default
        require(!value.isJsonNull) { "$name cannot be null" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return value.asBoolean
    }

    private fun JsonObject.int(name: String): Int? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toIntOrNull()

    private fun JsonObject.intOrDefault(name: String, default: Int): Int {
        val value = get(name) ?: return default
        require(!value.isJsonNull) { "$name cannot be null" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        return requireNotNull(value.asString.toIntOrNull()) { "$name must be an integer" }
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val value = get(name) ?: return null
        require(!value.isJsonNull) { "$name cannot be null" }
        require(value.isJsonObject) { "$name must be an object" }
        return value.asJsonObject
    }

    private fun JsonObject.intArrayOrDefault(name: String, validCodes: IntRange): IntArray {
        val value = get(name) ?: return intArrayOf()
        require(!value.isJsonNull) { "$name cannot be null" }
        require(value.isJsonArray) { "$name must be an integer array" }
        require(value.asJsonArray.size() <= MAX_BINDING_INPUTS) { "$name contains too many inputs" }
        return value.asJsonArray.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$name must contain integers" }
            requireNotNull(element.asString.toIntOrNull()) { "$name must contain integers" }.also {
                require(it in validCodes) { "$name contains an invalid input code" }
            }
        }.toIntArray()
    }
}
