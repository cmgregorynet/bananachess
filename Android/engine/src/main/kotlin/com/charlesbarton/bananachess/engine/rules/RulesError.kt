package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.Position

/** Why the rules refused an action. [message] is written for the player. */
sealed class RulesError(
    override val message: String,
) : Exception(message) {
    data object GameOver : RulesError("The match has already finished.")

    data class UnknownPiece(
        val pieceId: PieceId,
    ) : RulesError("That piece is no longer on the board.")

    data class NotActivePlayersPiece(
        val pieceId: PieceId,
    ) : RulesError("That piece belongs to the other player.")

    data class PieceIsOffBoard(
        val pieceId: PieceId,
    ) : RulesError("That blender is still waiting to come back.")

    data class OriginMismatch(
        val expected: Position,
        val given: Position,
    ) : RulesError("That piece has moved since the move was created.")

    data class IllegalDestination(
        val destination: Position,
    ) : RulesError("That square is not a legal destination.")

    data class BlenderNotDestroyed(
        val pieceId: PieceId,
    ) : RulesError("That blender is already on the board.")

    data class BlenderReturningOnItsOwn(
        val pieceId: PieceId,
    ) : RulesError("That blender is coming back on its own.")

    data class NotASpawnSquare(
        val square: Position,
    ) : RulesError("A blender can only come back on one of your spawns.")

    data class SpawnOccupied(
        val pieceId: PieceId,
    ) : RulesError("Something is standing on that blender's spawn.")

    data object WouldRepeatPosition : RulesError("That would put the board back where it has already been twice.")
}
