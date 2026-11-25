package com.charlesbarton.bananachess.engine.model

/**
 * Everything a player can spend a turn on. Moving a piece is not the only one: a
 * blender destroyed in combat has to be bought back, and that costs the whole turn.
 *
 * The ordering is canonical, so action lists and CPU tie-breaks are reproducible:
 * every [Move] sorts before every [RestoreBlender]. `toString` is the description
 * string the conformance fixture matches on.
 */
sealed interface GameAction : Comparable<GameAction>

/**
 * Ordered by (pieceId, destination). The origin takes part in equality but not in the
 * ordering, exactly as in the Swift engine.
 */
data class Move(
    val pieceId: PieceId,
    val origin: Position,
    val destination: Position,
) : GameAction {
    override fun compareTo(other: GameAction): Int =
        when (other) {
            is Move -> compareValuesBy(this, other, Move::pieceId, Move::destination)
            is RestoreBlender -> -1
        }

    override fun toString(): String = "$pieceId $origin→$destination"
}

/**
 * Spend the whole turn and the restore cost to put a destroyed blender back on
 * *either* of its owner's spawn squares, whichever is free. Coming back on the other
 * spawn changes the colour the blender travels on, which is the only way a blender
 * ever switches diagonals.
 */
data class RestoreBlender(
    val pieceId: PieceId,
    val at: Position,
) : GameAction {
    override fun compareTo(other: GameAction): Int =
        when (other) {
            is Move -> 1
            is RestoreBlender -> compareValuesBy(this, other, RestoreBlender::pieceId, RestoreBlender::at)
        }

    override fun toString(): String = "restore $pieceId at $at"
}
