package com.charlesbarton.bananachess.engine.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream

/** One position from `golden.json.gz`, as the Swift engine wrote it. */
internal data class GoldenSample(
    val label: String,
    val payloadJson: String,
    val link: String,
    val boardKey: ULong,
    val positionKey: ULong,
)

internal object GoldenSamples {
    val all: List<GoldenSample> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden.json.gz")) { "golden.json.gz is missing" }
        val root = Json.parseToJsonElement(GZIPInputStream(stream).use { it.readBytes().decodeToString() }).jsonObject
        root.getValue("games").jsonArray.flatMap { game ->
            val seed = game.jsonObject.string("seed")
            game.jsonObject.getValue("samples").jsonArray.map { element ->
                val sample = element.jsonObject
                GoldenSample(
                    label = "seed $seed step ${sample.string("step")}",
                    payloadJson = sample.string("payloadJSON"),
                    link = sample.string("link"),
                    boardKey = sample.string("boardKey").toULong(),
                    positionKey = sample.string("positionKey").toULong(),
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
