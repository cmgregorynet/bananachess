package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.rules.RulesEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Why a payload was refused. [message] is written for the player. */
sealed class MatchCodecError(
    override val message: String,
) : Exception(message) {
    data class UnsupportedSchema(
        val found: Int,
        val supported: Int,
    ) : MatchCodecError("This match was played on a newer version of Banana Chess. Update to continue.")

    data class OutdatedSchema(
        val found: Int,
        val supported: Int,
    ) : MatchCodecError(PREVIOUS_RULES)

    data class IncompatibleRules(
        val found: Int,
        val supported: Int,
    ) : MatchCodecError(PREVIOUS_RULES)

    data object Malformed : MatchCodecError("This match's data could not be read.")

    private companion object {
        const val PREVIOUS_RULES =
            "This match was started under the previous rules and can't be continued. " +
                "Start a new match to play with the current ones."
    }
}

/**
 * Reads and writes a [MatchPayload] as the JSON the iOS engine exchanges. Writing is
 * byte-identical to iOS; reading accepts only the exact schema and rules versions.
 */
object MatchCodec {
    /**
     * The largest payload [decode] will look at. A real match is a few kilobytes and grows
     * by about a hundred bytes a move, so this is far beyond any game and well short of
     * anything that could exhaust memory.
     */
    const val MAX_PAYLOAD_BYTES: Int = 1 shl 20

    fun encode(payload: MatchPayload): String =
        SwiftJson.write(
            JsonObject(
                buildMap {
                    payload.lastMove?.let { put("lastMove", lastMoveToJson(it)) }
                    put("matchID", JsonPrimitive(payload.matchId))
                    put("moveNumber", JsonPrimitive(payload.moveNumber))
                    put("rulesVersion", JsonPrimitive(payload.rulesVersion))
                    put("schemaVersion", JsonPrimitive(payload.schemaVersion))
                    put("state", GameStateCodec.toJson(payload.state))
                },
            ),
        )

    fun encodeToBytes(payload: MatchPayload): ByteArray = encode(payload).encodeToByteArray()

    /** @throws MatchCodecError */
    fun decode(bytes: ByteArray): MatchPayload {
        if (bytes.size > MAX_PAYLOAD_BYTES) malformed()
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                malformed()
            }
        return decode(text)
    }

    /** @throws MatchCodecError */
    fun decode(json: String): MatchPayload {
        val root = parseJson(json).asObject()
        val schemaVersion = root.int("schemaVersion")
        val rulesVersion = root.int("rulesVersion")
        // Both directions are refused. A newer payload cannot be understood; an older one
        // was played by rules that no longer exist, so continuing it would silently change
        // the game underneath whoever started it.
        //
        // The versions are read before anything else, unlike on iOS, which decodes the
        // whole payload first. A different schema usually means a different shape, and
        // "update the app" is a more useful answer to that than "could not be read".
        if (schemaVersion > MatchPayload.CURRENT_SCHEMA_VERSION) {
            throw MatchCodecError.UnsupportedSchema(schemaVersion, MatchPayload.CURRENT_SCHEMA_VERSION)
        }
        if (schemaVersion != MatchPayload.CURRENT_SCHEMA_VERSION) {
            throw MatchCodecError.OutdatedSchema(schemaVersion, MatchPayload.CURRENT_SCHEMA_VERSION)
        }
        if (rulesVersion != RulesEngine.RULES_VERSION) {
            throw MatchCodecError.IncompatibleRules(rulesVersion, RulesEngine.RULES_VERSION)
        }
        return MatchPayload(
            schemaVersion = schemaVersion,
            rulesVersion = rulesVersion,
            matchId = root.string("matchID"),
            moveNumber = root.int("moveNumber"),
            state = GameStateCodec.fromJson(root.required("state")),
            lastMove = root.optional("lastMove")?.let { lastMoveFromJson(it.asObject()) },
        )
    }

    private fun lastMoveToJson(lastMove: LastMove): JsonObject =
        JsonObject(
            mapOf(
                "action" to actionToJson(lastMove.action),
                "events" to JsonArray(lastMove.events.map(::eventToJson)),
                "playedBy" to JsonPrimitive(lastMove.playedBy.rawValue),
            ),
        )

    private fun lastMoveFromJson(json: JsonObject): LastMove =
        LastMove(
            action = actionFromJson(json.required("action")),
            playedBy = json.enum("playedBy"),
            events = json.array("events").map(::eventFromJson),
        )
}
