package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.rules.RulesEngine

/** What a screen reader says for the square at [position], rows and columns counted from one. */
fun MatchUiState.squareDescription(position: Position): String {
    val square = "row ${position.row + 1}, column ${position.column + 1}"
    val piece = displayState.pieceAt(position)
    if (piece == null) {
        val description = StringBuilder("Empty square, $square")
        if (position in blenderHomes) description.append(", blender home")
        val waiting = displayState.destroyedBlenders.firstOrNull { it.isAwaitingRespawn && it.home == position }
        if (waiting != null) {
            val turns = displayState.turnsUntilRespawn(waiting) ?: 0
            description.append(
                ", ${mode.name(waiting.owner)} blender blown up by a smoothie, " +
                    "back in $turns ${if (turns == 1) "turn" else "turns"}",
            )
        }
        if (restorableBlender(position) != null) {
            description.append(
                ", a destroyed blender can come back here, " +
                    "tap to buy it back for ${RulesEngine.BLENDER_RESTORE_COST} points",
            )
        }
        return description.append(legalMoveSuffix(position)).toString()
    }

    val owner = mode.name(piece.owner)
    val golden = if (displayState.isGolden(piece.id)) "golden " else ""
    val described =
        if (piece.isBlender) {
            "$owner blender, $square"
        } else {
            "$owner $golden${piece.kind.rawValue}, $square, value ${piece.kind.baseValue}"
        }
    return described + legalMoveSuffix(position)
}

/**
 * Blenders can land on occupied squares, so an occupied square may well be a legal
 * destination, and what it costs depends on whose fruit is standing there. Saying so out
 * loud is the only way that reaches a TalkBack player.
 */
private fun MatchUiState.legalMoveSuffix(position: Position): String {
    if (position !in legalDestinations) return ""
    val selected = selectedPieceId?.let(displayState::piece)
    val occupant = displayState.pieceAt(position)
    if (selected == null || occupant == null) return ", legal move"

    if (occupant.isBlender) return ", legal move, knocks both blenders out"
    val value = occupant.kind.baseValue
    return if (occupant.owner == selected.owner) {
        ", legal move, blends your own ${occupant.kind.rawValue}, minus $value"
    } else {
        ", legal move, blends for plus $value"
    }
}
