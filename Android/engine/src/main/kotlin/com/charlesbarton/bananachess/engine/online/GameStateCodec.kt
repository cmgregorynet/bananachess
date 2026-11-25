package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PlayerPair
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RawValued
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.SmoothieProgress
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.rawValueOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [GameState] as JSON, in the exact shape the iOS engine's synthesized `Codable`
 * writes. The match payload embeds it, and anything else that stores a state should use
 * it too, so there is only ever one encoding of a match.
 *
 * Decoding trusts nothing: the text may have arrived in a link from anyone. Every
 * failure is [MatchCodecError.Malformed], and a state that decodes is safe to hand to
 * the rules (every square is on the board, no two pieces share an id or a square).
 */
object GameStateCodec {
    /** Compact, key-sorted JSON, byte-identical to the iOS engine's. */
    fun encode(state: GameState): String = SwiftJson.write(toJson(state))

    /** @throws MatchCodecError.Malformed when [json] is not a readable state. */
    fun decode(json: String): GameState = fromJson(parseJson(json))

    internal fun toJson(state: GameState): JsonObject =
        obj(
            "activePlayer" to raw(state.activePlayer),
            "blenderBattles" to JsonPrimitive(state.blenderBattles),
            "boardHistory" to JsonArray(state.boardHistory.map { JsonPrimitive(it) }),
            "boardSetup" to raw(state.boardSetup),
            "boardWrap" to raw(state.boardWrap),
            "history" to JsonArray(state.history.map(::actionToJson)),
            "pieces" to JsonArray(state.pieces.map(::pieceToJson)),
            "ruleSet" to raw(state.ruleSet),
            "scores" to obj("one" to JsonPrimitive(state.scores.one), "two" to JsonPrimitive(state.scores.two)),
            "status" to statusToJson(state.status),
            "turnNumber" to JsonPrimitive(state.turnNumber),
            "wildcards" to wildcardsToJson(state.wildcards),
        )

    /** @throws MatchCodecError.Malformed when [element] is not a readable state. */
    internal fun fromJson(element: JsonElement): GameState {
        val json = element.asObject()
        val pieces = json.array("pieces").map(::pieceFromJson)
        if (pieces.distinctBy { it.id }.size != pieces.size) malformed()
        val occupied = pieces.mapNotNull { it.position }
        if (occupied.distinct().size != occupied.size) malformed()
        val scores = json.child("scores")
        return GameState(
            pieces = pieces,
            activePlayer = json.enum("activePlayer"),
            scores = Scores(scores.int("one"), scores.int("two")),
            status = statusFromJson(json.required("status")),
            turnNumber = json.int("turnNumber"),
            history = json.array("history").map(::actionFromJson),
            ruleSet = json.enum("ruleSet"),
            boardSetup = json.enum("boardSetup"),
            boardWrap = json.enum("boardWrap"),
            blenderBattles = json.boolean("blenderBattles"),
            boardHistory = json.array("boardHistory").map { it.asULong() },
            wildcards = wildcardsFromJson(json.child("wildcards")),
        )
    }
}

// Parsing

internal fun malformed(): Nothing = throw MatchCodecError.Malformed

internal fun parseJson(text: String): JsonElement {
    if (text.length > MatchCodec.MAX_PAYLOAD_BYTES) malformed()
    return try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        malformed()
    }
}

// Writing. Swift's synthesized `Codable` leaves a nil field out, wraps an enum case in an
// object keyed by its name, and names an unlabelled associated value `_0`.

private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun raw(value: RawValued): JsonPrimitive = JsonPrimitive(value.rawValue)

private fun id(value: PieceId): JsonPrimitive = JsonPrimitive(value.rawValue)

private fun int(value: Int): JsonPrimitive = JsonPrimitive(value)

private fun positionToJson(position: Position): JsonObject =
    obj("column" to int(position.column), "row" to int(position.row))

private fun pieceToJson(piece: Piece): JsonObject =
    obj(
        "home" to piece.home?.let(::positionToJson),
        "id" to id(piece.id),
        "kind" to raw(piece.kind),
        "owner" to raw(piece.owner),
        "position" to piece.position?.let(::positionToJson),
        "respawnTurn" to piece.respawnTurn?.let(::int),
    )

internal fun actionToJson(action: GameAction): JsonObject =
    when (action) {
        is Move -> {
            val move =
                obj(
                    "destination" to positionToJson(action.destination),
                    "origin" to positionToJson(action.origin),
                    "pieceID" to id(action.pieceId),
                )
            obj("move" to obj("_0" to move))
        }

        is RestoreBlender -> {
            obj("restoreBlender" to obj("at" to positionToJson(action.at), "pieceID" to id(action.pieceId)))
        }
    }

private fun outcomeToJson(outcome: Outcome): JsonObject =
    when (outcome) {
        is Outcome.Win -> obj("win" to obj("_0" to raw(outcome.player), "by" to raw(outcome.by)))
        Outcome.Draw -> obj("draw" to obj())
    }

private fun statusToJson(status: GameStatus): JsonObject =
    when (status) {
        GameStatus.InProgress -> obj("inProgress" to obj())
        is GameStatus.Ended -> obj("ended" to obj("_0" to outcomeToJson(status.outcome)))
    }

private fun <T> pairToJson(
    pair: PlayerPair<T>,
    value: (T) -> JsonElement,
): JsonObject = obj("one" to value(pair.one), "two" to value(pair.two))

private fun smoothieToJson(progress: SmoothieProgress): JsonObject =
    obj(
        "banana" to JsonPrimitive(progress.banana),
        "pineapple" to JsonPrimitive(progress.pineapple),
        "strawberry" to JsonPrimitive(progress.strawberry),
    )

private fun wildcardsToJson(wildcards: WildcardProgress): JsonObject =
    obj(
        "bananasDelivered" to pairToJson(wildcards.bananasDelivered, ::int),
        "blenderamaCountered" to pairToJson(wildcards.blenderamaCountered) { JsonPrimitive(it) },
        "ownFruitBlended" to pairToJson(wildcards.ownFruitBlended, ::int),
        "smoothie" to pairToJson(wildcards.smoothie, ::smoothieToJson),
        "smoothieFired" to pairToJson(wildcards.smoothieFired) { JsonPrimitive(it) },
    )

private fun reportToJson(report: BattleReport): JsonObject =
    obj(
        "attacker" to raw(report.attacker),
        "attackerBonus" to int(report.attackerBonus),
        "attackerFruit" to int(report.attackerFruit),
        "attackerRoll" to int(report.attackerRoll),
        "defenderFruit" to int(report.defenderFruit),
        "defenderRoll" to int(report.defenderRoll),
    )

internal fun eventToJson(event: GameEvent): JsonObject =
    when (event) {
        is GameEvent.Moved -> {
            obj(
                "moved" to
                    obj(
                        "from" to positionToJson(event.from),
                        "pieceID" to id(event.pieceId),
                        "to" to positionToJson(event.to),
                    ),
            )
        }

        is GameEvent.BlenderBattle -> {
            obj("blenderBattle" to obj("_0" to reportToJson(event.report), "at" to positionToJson(event.at)))
        }

        is GameEvent.BlenderDestroyed -> {
            obj(
                "blenderDestroyed" to
                    obj(
                        "at" to positionToJson(event.at),
                        "by" to id(event.by),
                        "owner" to raw(event.owner),
                        "pieceID" to id(event.pieceId),
                    ),
            )
        }

        is GameEvent.BlenderRestored -> {
            obj(
                "blenderRestored" to
                    obj(
                        "at" to positionToJson(event.at),
                        "cost" to int(event.cost),
                        "owner" to raw(event.owner),
                        "pieceID" to id(event.pieceId),
                    ),
            )
        }

        is GameEvent.BlenderRespawned -> {
            obj(
                "blenderRespawned" to
                    obj("at" to positionToJson(event.at), "owner" to raw(event.owner), "pieceID" to id(event.pieceId)),
            )
        }

        is GameEvent.BananaReturned -> {
            obj("bananaReturned" to obj("pieceID" to id(event.pieceId), "to" to positionToJson(event.to)))
        }

        is GameEvent.Blended -> {
            obj(
                "blended" to
                    obj(
                        "at" to positionToJson(event.at),
                        "awardedTo" to raw(event.awardedTo),
                        "fruitOwner" to raw(event.fruitOwner),
                        "kind" to raw(event.kind),
                        "pieceID" to id(event.pieceId),
                        "points" to int(event.points),
                    ),
            )
        }

        is GameEvent.Delivered -> {
            obj(
                "delivered" to
                    obj(
                        "at" to positionToJson(event.at),
                        "kind" to raw(event.kind),
                        "owner" to raw(event.owner),
                        "pieceID" to id(event.pieceId),
                        "points" to int(event.points),
                    ),
            )
        }

        is GameEvent.ScoreChanged -> {
            obj("scoreChanged" to obj("newScore" to int(event.newScore), "player" to raw(event.player)))
        }

        is GameEvent.TurnPassed -> {
            obj("turnPassed" to obj("skipped" to raw(event.skipped)))
        }

        is GameEvent.TurnChanged -> {
            obj("turnChanged" to obj("active" to raw(event.active)))
        }

        is GameEvent.GameEnded -> {
            obj("gameEnded" to obj("outcome" to outcomeToJson(event.outcome)))
        }

        is GameEvent.PineappleTurnedGolden -> {
            obj("pineappleTurnedGolden" to obj("owner" to raw(event.owner), "pieceID" to id(event.pieceId)))
        }

        is GameEvent.SmoothiePoured -> {
            obj("smoothiePoured" to obj("player" to raw(event.player), "points" to int(event.points)))
        }

        is GameEvent.Smoothie -> {
            obj(
                "smoothie" to
                    obj(
                        "pieceIDs" to JsonArray(event.pieceIds.map(::id)),
                        "player" to raw(event.player),
                        "respawnTurn" to int(event.respawnTurn),
                    ),
            )
        }
    }

// Reading

private val integer = Regex("-?[0-9]+")

internal fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: malformed()

private fun JsonElement.asString(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()

private fun JsonElement.asNumber(): String =
    (this as? JsonPrimitive)?.takeIf { !it.isString && integer.matches(it.content) }?.content ?: malformed()

private fun JsonElement.asULong(): ULong = asNumber().toULongOrNull() ?: malformed()

/** A missing key and an explicit `null` are the same thing, as they are to Swift's `decodeIfPresent`. */
internal fun JsonObject.optional(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

internal fun JsonObject.required(key: String): JsonElement = optional(key) ?: malformed()

internal fun JsonObject.child(key: String): JsonObject = required(key).asObject()

internal fun JsonObject.array(key: String): JsonArray = required(key) as? JsonArray ?: malformed()

internal fun JsonObject.string(key: String): String = required(key).asString()

internal fun JsonObject.int(key: String): Int = required(key).asNumber().toIntOrNull() ?: malformed()

private fun JsonObject.boolean(key: String): Boolean {
    val primitive = (required(key) as? JsonPrimitive)?.takeIf { !it.isString } ?: malformed()
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> malformed()
    }
}

internal inline fun <reified E> JsonObject.enum(key: String): E where E : Enum<E>, E : RawValued =
    rawValueOf<E>(string(key)) ?: malformed()

private fun JsonObject.pieceId(key: String): PieceId = PieceId(string(key))

private fun JsonObject.position(key: String): Position = positionFromJson(child(key))

/** The one case of a Swift enum with associated values: its name and the object holding them. */
private fun JsonElement.asCase(): Pair<String, JsonObject> {
    val entry = asObject().entries.singleOrNull() ?: malformed()
    return entry.key to entry.value.asObject()
}

// A square off the board never comes from a real match, and the rules index the board
// by position without checking, so one is refused here rather than carried inside.
private fun positionFromJson(json: JsonObject): Position =
    Position(row = json.int("row"), column = json.int("column")).takeIf(Board::contains) ?: malformed()

private fun pieceFromJson(element: JsonElement): Piece {
    val json = element.asObject()
    return Piece(
        id = json.pieceId("id"),
        owner = json.enum("owner"),
        kind = json.enum("kind"),
        position = json.optional("position")?.let { positionFromJson(it.asObject()) },
        home = json.optional("home")?.let { positionFromJson(it.asObject()) },
        respawnTurn = json.optional("respawnTurn")?.let { json.int("respawnTurn") },
    )
}

internal fun actionFromJson(element: JsonElement): GameAction {
    val (name, body) = element.asCase()
    return when (name) {
        "move" -> {
            val move = body.child("_0")
            Move(move.pieceId("pieceID"), origin = move.position("origin"), destination = move.position("destination"))
        }

        "restoreBlender" -> {
            RestoreBlender(body.pieceId("pieceID"), at = body.position("at"))
        }

        else -> {
            malformed()
        }
    }
}

private fun outcomeFromJson(element: JsonElement): Outcome {
    val (name, body) = element.asCase()
    return when (name) {
        "win" -> Outcome.Win(body.enum("_0"), by = body.enum("by"))
        "draw" -> Outcome.Draw
        else -> malformed()
    }
}

private fun statusFromJson(element: JsonElement): GameStatus {
    val (name, body) = element.asCase()
    return when (name) {
        "inProgress" -> GameStatus.InProgress
        "ended" -> GameStatus.Ended(outcomeFromJson(body.required("_0")))
        else -> malformed()
    }
}

private fun <T> pairFromJson(
    json: JsonObject,
    value: JsonObject.(String) -> T,
): PlayerPair<T> = PlayerPair(json.value("one"), json.value("two"))

private fun smoothieFromJson(json: JsonObject): SmoothieProgress =
    SmoothieProgress(
        banana = json.boolean("banana"),
        strawberry = json.boolean("strawberry"),
        pineapple = json.boolean("pineapple"),
    )

private fun wildcardsFromJson(json: JsonObject): WildcardProgress =
    WildcardProgress(
        smoothie = pairFromJson(json.child("smoothie")) { smoothieFromJson(child(it)) },
        smoothieFired = pairFromJson(json.child("smoothieFired"), JsonObject::boolean),
        bananasDelivered = pairFromJson(json.child("bananasDelivered"), JsonObject::int),
        ownFruitBlended = pairFromJson(json.child("ownFruitBlended"), JsonObject::int),
        blenderamaCountered = pairFromJson(json.child("blenderamaCountered"), JsonObject::boolean),
    )

private fun reportFromJson(json: JsonObject): BattleReport =
    BattleReport(
        attacker = json.enum("attacker"),
        attackerFruit = json.int("attackerFruit"),
        attackerBonus = json.int("attackerBonus"),
        attackerRoll = json.int("attackerRoll"),
        defenderFruit = json.int("defenderFruit"),
        defenderRoll = json.int("defenderRoll"),
    )

internal fun eventFromJson(element: JsonElement): GameEvent {
    val (name, body) = element.asCase()
    return when (name) {
        "moved" -> {
            GameEvent.Moved(body.pieceId("pieceID"), from = body.position("from"), to = body.position("to"))
        }

        "blenderBattle" -> {
            GameEvent.BlenderBattle(reportFromJson(body.child("_0")), at = body.position("at"))
        }

        "blenderDestroyed" -> {
            GameEvent.BlenderDestroyed(
                body.pieceId("pieceID"),
                owner = body.enum("owner"),
                by = body.pieceId("by"),
                at = body.position("at"),
            )
        }

        "blenderRestored" -> {
            GameEvent.BlenderRestored(
                body.pieceId("pieceID"),
                owner = body.enum("owner"),
                at = body.position("at"),
                cost = body.int("cost"),
            )
        }

        "blenderRespawned" -> {
            GameEvent.BlenderRespawned(body.pieceId("pieceID"), owner = body.enum("owner"), at = body.position("at"))
        }

        "bananaReturned" -> {
            GameEvent.BananaReturned(body.pieceId("pieceID"), to = body.position("to"))
        }

        "blended" -> {
            GameEvent.Blended(
                body.pieceId("pieceID"),
                kind = body.enum("kind"),
                fruitOwner = body.enum("fruitOwner"),
                at = body.position("at"),
                awardedTo = body.enum("awardedTo"),
                points = body.int("points"),
            )
        }

        "delivered" -> {
            GameEvent.Delivered(
                body.pieceId("pieceID"),
                kind = body.enum("kind"),
                owner = body.enum("owner"),
                at = body.position("at"),
                points = body.int("points"),
            )
        }

        "scoreChanged" -> {
            GameEvent.ScoreChanged(player = body.enum("player"), newScore = body.int("newScore"))
        }

        "turnPassed" -> {
            GameEvent.TurnPassed(skipped = body.enum("skipped"))
        }

        "turnChanged" -> {
            GameEvent.TurnChanged(active = body.enum("active"))
        }

        "gameEnded" -> {
            GameEvent.GameEnded(outcomeFromJson(body.required("outcome")))
        }

        "pineappleTurnedGolden" -> {
            GameEvent.PineappleTurnedGolden(body.pieceId("pieceID"), owner = body.enum("owner"))
        }

        "smoothiePoured" -> {
            GameEvent.SmoothiePoured(player = body.enum("player"), points = body.int("points"))
        }

        "smoothie" -> {
            GameEvent.Smoothie(
                player = body.enum("player"),
                pieceIds = body.array("pieceIDs").map { PieceId(it.asString()) },
                respawnTurn = body.int("respawnTurn"),
            )
        }

        else -> {
            malformed()
        }
    }
}
