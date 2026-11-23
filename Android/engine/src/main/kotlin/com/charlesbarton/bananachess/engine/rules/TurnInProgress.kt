package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position

/**
 * One action being resolved: the state so far and the events it has produced, in order.
 *
 * The Swift engine threads `inout GameState` and `inout [GameEvent]` through every step.
 * This is the same thing for immutable states: [state] is replaced, never edited, so the
 * state `apply` was given is untouched. Never outlives a single `apply` call.
 */
internal class TurnInProgress(
    var state: GameState,
) {
    private val recorded = mutableListOf<GameEvent>()

    val events: List<GameEvent> get() = recorded.toList()

    fun emit(event: GameEvent) {
        recorded += event
    }

    fun setPosition(
        position: Position?,
        id: PieceId,
    ) {
        state = state.withPosition(position, id)
    }

    fun remove(id: PieceId) {
        state = state.removing(id)
    }

    /** Adds [points], which may be negative. Scores are allowed below zero. */
    fun addPoints(
        player: PlayerId,
        points: Int,
    ) {
        state = state.updated(scores = state.scores.updated(player, state.scores[player] + points))
    }

    fun emitScore(player: PlayerId) {
        emit(GameEvent.ScoreChanged(player, state.scores[player]))
    }
}
