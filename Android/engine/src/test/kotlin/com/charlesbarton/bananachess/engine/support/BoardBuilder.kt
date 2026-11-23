package com.charlesbarton.bananachess.engine.support

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Builds arbitrary positions for rule fixtures without going through a real opening.
 *
 * A port of `BoardBuilder` in `Engine/Tests/BananaChessEngineTests/TestSupport.swift`,
 * down to the ids it hands out: `{owner}.fruit.N` and `{owner}.blender.N`, numbered in
 * the order the pieces were added and shared between the two players.
 */
class BoardBuilder {
    private val placed = mutableListOf<Piece>()
    private var fruitCount = 0
    private var blenderCount = 0

    fun fruit(
        kind: PieceKind,
        owner: PlayerId,
        at: Position,
        home: Position = at,
    ): PieceId {
        val id = PieceId("${owner.rawValue}.fruit.$fruitCount")
        fruitCount += 1
        placed += Piece(id, owner, kind, position = at, home = home)
        return id
    }

    fun blender(
        owner: PlayerId,
        at: Position,
        home: Position = at,
    ): PieceId = addBlender(owner, at, home)

    /** A blender already destroyed, for fixtures that start after a loss. */
    fun destroyedBlender(
        owner: PlayerId,
        home: Position,
    ): PieceId = addBlender(owner, null, home)

    fun state(
        active: PlayerId = PlayerId.One,
        ruleSet: RuleSet = RuleSet.Standard,
        scores: Scores = Scores(),
    ): GameState = GameState(pieces = placed.toList(), activePlayer = active, scores = scores, ruleSet = ruleSet)

    private fun addBlender(
        owner: PlayerId,
        at: Position?,
        home: Position,
    ): PieceId {
        val id = PieceId("${owner.rawValue}.blender.$blenderCount")
        blenderCount += 1
        placed += Piece(id, owner, PieceKind.Blender, position = at, home = home)
        return id
    }
}

fun position(
    row: Int,
    column: Int,
): Position = Position(row, column)

/** The move of [id] from wherever it stands now. The piece must be on the board. */
fun GameState.move(
    id: PieceId,
    to: Position,
): Move = Move(id, origin = checkNotNull(piece(id)?.position) { "$id is not on the board" }, destination = to)

fun GameState.square(id: PieceId): Position? = piece(id)?.position

/** Still in the match but standing nowhere. False for a piece that has left it altogether. */
fun GameState.isOffBoard(id: PieceId): Boolean = piece(id)?.let { !it.isOnBoard } ?: false

// Wildcard progress. The Swift tests assign into `state.wildcards` in place; these are the same edits as copies.

fun GameState.withBananasDelivered(
    player: PlayerId,
    count: Int,
): GameState = updated(wildcards = wildcards.copy(bananasDelivered = wildcards.bananasDelivered.updated(player, count)))

fun GameState.withOwnFruitBlended(
    player: PlayerId,
    count: Int,
): GameState = updated(wildcards = wildcards.copy(ownFruitBlended = wildcards.ownFruitBlended.updated(player, count)))

fun GameState.withBlenderamaCountered(player: PlayerId): GameState =
    updated(wildcards = wildcards.copy(blenderamaCountered = wildcards.blenderamaCountered.updated(player, true)))

/** Marks [kinds] as already through [player]'s blenders, without firing a smoothie. */
fun GameState.withSmoothieCollected(
    player: PlayerId,
    vararg kinds: PieceKind,
): GameState {
    val collected = kinds.fold(wildcards.smoothie[player]) { progress, kind -> progress.recording(kind) }
    return updated(wildcards = wildcards.copy(smoothie = wildcards.smoothie.updated(player, collected)))
}

/**
 * Plays one deliberately inert move for the active player, so global turns can pass
 * without the filler disturbing the fixture. Prefers a fruit step that scores nothing,
 * lands on an empty square, keeps off every piece's home, and leaves [protecting] alone.
 */
fun idleTransition(
    state: GameState,
    protecting: Set<PieceId> = emptySet(),
): RulesEngine.Transition {
    val homes = state.pieces.mapNotNull { it.home }.toSet()
    val legal = RulesEngine.legalMoves(state)

    val inert =
        legal.firstOrNull { move ->
            val piece = state.piece(move.pieceId)
            move.pieceId !in protecting &&
                piece != null &&
                piece.isFruit &&
                state.pieceAt(move.destination) == null &&
                move.destination !in homes &&
                move.destination.row != piece.owner.deliveryRow
        }

    // Fall back rather than fail: some fixtures run until nothing inert is left.
    val move =
        inert
            ?: legal.firstOrNull { it.pieceId !in protecting }
            ?: legal.firstOrNull()
            ?: return RulesEngine.Transition(state, emptyList())

    return RulesEngine.apply(move, state)
}

fun advanceTurns(
    count: Int,
    from: GameState,
    protecting: Set<PieceId> = emptySet(),
): GameState {
    var current = from
    repeat(count) {
        if (current.status.isOver) return current
        current = idleTransition(current, protecting).state
    }
    return current
}

val List<GameEvent>.blends: List<GameEvent.Blended> get() = filterIsInstance<GameEvent.Blended>()

val List<GameEvent>.destructions: List<GameEvent.BlenderDestroyed>
    get() = filterIsInstance<GameEvent.BlenderDestroyed>()

val List<GameEvent>.restorations: List<GameEvent.BlenderRestored>
    get() = filterIsInstance<GameEvent.BlenderRestored>()

val List<GameEvent>.deliveries: List<GameEvent.Delivered> get() = filterIsInstance<GameEvent.Delivered>()

val List<GameEvent>.battleReports: List<BattleReport>
    get() = filterIsInstance<GameEvent.BlenderBattle>().map { it.report }

fun List<GameEvent>.bananaReturn(of: PieceId): Position? =
    filterIsInstance<GameEvent.BananaReturned>().firstOrNull { it.pieceId == of }?.to

/** Runs [block], which must be refused with exactly [expected]. */
fun assertRefusedWith(
    expected: RulesError,
    block: () -> Unit,
) {
    assertEquals(expected, assertFailsWith<RulesError> { block() })
}
