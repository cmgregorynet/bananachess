package com.charlesbarton.bananachess.engine.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes JSON exactly as Swift's `JSONEncoder` does with `.sortedKeys`: no whitespace,
 * keys in order, and `/` escaped. The iOS app writes its links this way, and a payload
 * that re-encodes to the same bytes on both platforms is what the golden tests check.
 */
internal object SwiftJson {
    fun write(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(
        element: JsonElement,
        out: StringBuilder,
    ) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    write(value, out)
                }
                out.append(']')
            }

            is JsonNull -> {
                out.append("null")
            }

            is JsonPrimitive -> {
                if (element.isString) writeString(element.content, out) else out.append(element.content)
            }
        }
    }

    // Foundation escapes the quote, the backslash, the slash and the C0 controls, and
    // writes everything else, including DEL and non-ASCII, as it is.
    private fun writeString(
        text: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> {
                    out.append("\\\"")
                }

                '\\' -> {
                    out.append("\\\\")
                }

                '/' -> {
                    out.append("\\/")
                }

                '\b' -> {
                    out.append("\\b")
                }

                '\u000C' -> {
                    out.append("\\f")
                }

                '\n' -> {
                    out.append("\\n")
                }

                '\r' -> {
                    out.append("\\r")
                }

                '\t' -> {
                    out.append("\\t")
                }

                else -> {
                    if (char <
                        ' '
                    ) {
                        out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        out.append('"')
    }
}
