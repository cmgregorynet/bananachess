package com.charlesbarton.bananachess.engine.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks `boardKey` and `positionKey` against values the Swift engine computed for
 * positions taken from real games (`golden.json.gz`). Only the fields the keys read
 * are pulled out of each payload; the full codec is a later slice.
 */
class GoldenKeysTest {
    private data class Sample(
        val label: String,
        val state: GameState,
        val lastRecordedBoardKey: ULong,
        val boardKey: ULong,
        val positionKey: ULong,
    )

    @Test
    fun `board and position keys match the Swift engine`() {
        val samples = loadSamples()
        assertTrue(samples.size > 100, "expected the full golden set, found ${samples.size}")
        assertTrue(samples.any { sample -> sample.state.pieces.any { !it.isOnBoard } })
        assertTrue(samples.any { it.state.scores.one < 0 || it.state.scores.two < 0 })

        for (sample in samples) {
            assertEquals(sample.boardKey, sample.state.boardKey, "boardKey of ${sample.label}")
            assertEquals(sample.positionKey, sample.state.positionKey, "positionKey of ${sample.label}")
            assertEquals(sample.lastRecordedBoardKey, sample.state.boardKey, "boardHistory of ${sample.label}")
        }
    }

    private fun loadSamples(): List<Sample> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden.json.gz")) { "golden.json.gz is missing" }
        val root = Json.parseToJsonElement(GZIPInputStream(stream).use { it.readBytes().decodeToString() }).jsonObject
        return root.getValue("games").jsonArray.flatMapIndexed { gameIndex, game ->
            game.jsonObject.getValue("samples").jsonArray.map { element ->
                val sample = element.jsonObject
                val payload = Json.parseToJsonElement(sample.string("payloadJSON")).jsonObject
                val state = payload.getValue("state").jsonObject
                Sample(
                    label = "game $gameIndex step ${sample.getValue("step").jsonPrimitive.content}",
                    state = keyedState(state),
                    lastRecordedBoardKey =
                        state
                            .getValue("boardHistory")
                            .jsonArray
                            .last()
                            .jsonPrimitive.content
                            .toULong(),
                    boardKey = sample.string("boardKey").toULong(),
                    positionKey = sample.string("positionKey").toULong(),
                )
            }
        }
    }

    private fun keyedState(state: JsonObject): GameState {
        val scores = state.getValue("scores").jsonObject
        return GameState(
            pieces =
                state.getValue("pieces").jsonArray.map { element ->
                    val piece = element.jsonObject
                    Piece(
                        id = PieceId(piece.string("id")),
                        owner = checkNotNull(rawValueOf<PlayerId>(piece.string("owner"))),
                        kind = checkNotNull(rawValueOf<PieceKind>(piece.string("kind"))),
                        position = piece["position"]?.jsonObject?.let(::position),
                        home = piece["home"]?.jsonObject?.let(::position),
                        respawnTurn = piece["respawnTurn"]?.jsonPrimitive?.int,
                    )
                },
            activePlayer = checkNotNull(rawValueOf<PlayerId>(state.string("activePlayer"))),
            scores = Scores(scores.getValue("one").jsonPrimitive.int, scores.getValue("two").jsonPrimitive.int),
        )
    }

    private fun position(json: JsonObject): Position =
        Position(json.getValue("row").jsonPrimitive.int, json.getValue("column").jsonPrimitive.int)

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
