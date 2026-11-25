package com.charlesbarton.bananachess.engine.support

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RawValued
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.rawValueOf
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The conformance fixture written by the Swift engine: 108 complete games.
 *
 * Parsed once per test JVM. Use `ConformanceFixture.games`; each [FixtureGame] exposes
 * its settings as engine types, `newGame()` for the opening, and its recorded steps.
 */
object ConformanceFixture {
    val file: FixtureFile by lazy {
        val path = checkNotNull(System.getProperty("conformance.fixture")) { "conformance.fixture is not set" }
        val text =
            GZIPInputStream(File(path).inputStream()).use { it.readBytes().decodeToString() }
        lenientJson.decodeFromString<FixtureFile>(text)
    }

    val games: List<FixtureGame> get() = file.games
}

@Serializable
data class FixtureFile(
    val schemaVersion: Int,
    val rulesVersion: Int,
    val games: List<FixtureGame>,
)

@Serializable
data class FixtureGame(
    val ruleSet: String,
    val boardSetup: String,
    val boardWrap: String,
    val blenderBattles: Boolean,
    val startingPlayer: String,
    val seed: ULong,
    val driver: String,
    /** For example `one by score`. Absent when the recorded game stopped before it ended. */
    val outcome: String? = null,
    val opening: FixtureOpening,
    val steps: List<FixtureStep>,
) {
    val ruleSetValue: RuleSet get() = raw(ruleSet)
    val boardSetupValue: BoardSetup get() = raw(boardSetup)
    val boardWrapValue: BoardWrap get() = raw(boardWrap)
    val startingPlayerValue: PlayerId get() = raw(startingPlayer)

    /** A label for assertion messages. */
    val label: String get() = "$ruleSet/$boardSetup/$boardWrap/battles=$blenderBattles/$driver/seed=$seed"

    /** The opening this game was recorded from, rebuilt by the Kotlin engine. */
    fun newGame(): GameState =
        GameSetup.newGame(
            startingPlayer = startingPlayerValue,
            ruleSet = ruleSetValue,
            boardSetup = boardSetupValue,
            boardWrap = boardWrapValue,
            blenderBattles = blenderBattles,
            seed = seed,
        )
}

@Serializable
data class FixtureOpening(
    val activePlayer: String,
    val pieces: List<FixturePiece>,
) {
    val activePlayerValue: PlayerId get() = raw(activePlayer)
}

@Serializable
data class FixturePiece(
    val id: String,
    val owner: String,
    val kind: String,
    val row: Int? = null,
    val column: Int? = null,
    val homeRow: Int? = null,
    val homeColumn: Int? = null,
) {
    fun toPiece(): Piece =
        Piece(
            id = PieceId(id),
            owner = raw(owner),
            kind = raw<PieceKind>(kind),
            position = square(row, column),
            home = square(homeRow, homeColumn),
        )

    private fun square(
        row: Int?,
        column: Int?,
    ): Position? = if (row != null && column != null) Position(row, column) else null
}

@Serializable
data class FixtureStep(
    /** The action's description string. */
    val action: String,
    val events: List<String>,
    /** `id@row,col` or `id@-`, space separated, in id order. */
    val after: String,
    val activePlayer: String,
    /** Player one, then player two. */
    val scores: List<Int>,
    val turnNumber: Int,
    /** Every legal action in canonical order. Present on the first 8 steps only. */
    val legalBefore: List<String>? = null,
) {
    val activePlayerValue: PlayerId get() = raw(activePlayer)
}

private val lenientJson = Json { ignoreUnknownKeys = true }

private inline fun <reified E> raw(value: String): E where E : Enum<E>, E : RawValued =
    checkNotNull(rawValueOf<E>(value)) { "unknown ${E::class.simpleName} '$value'" }

private val movePattern = Regex("""^(\S+) r(\d+)c(\d+)→r(\d+)c(\d+)$""")
private val restorePattern = Regex("""^restore (\S+) at r(\d+)c(\d+)$""")

/** Parses an action description string (the inverse of `GameAction.toString`). */
fun parseAction(description: String): GameAction {
    movePattern.matchEntire(description)?.destructured?.let { (id, fromRow, fromColumn, toRow, toColumn) ->
        return Move(
            PieceId(id),
            Position(fromRow.toInt(), fromColumn.toInt()),
            Position(toRow.toInt(), toColumn.toInt()),
        )
    }
    restorePattern.matchEntire(description)?.destructured?.let { (id, row, column) ->
        return RestoreBlender(PieceId(id), Position(row.toInt(), column.toInt()))
    }
    error("not an action description: '$description'")
}
