package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.PieceKind

/**
 * Replays one event onto a state, for presentation only. The UI walks a transition's
 * events in order through this so the board animates each beat (the slide, then the
 * strike, then the blend) instead of snapping to the finished position.
 *
 * It deliberately does not touch the turn number, the history, the board history or the
 * wildcard progress: those come from the authoritative state the engine already returned.
 */
fun GameState.applying(event: GameEvent): GameState =
    when (event) {
        is GameEvent.Moved -> {
            withPosition(event.to, event.pieceId)
        }

        is GameEvent.BlenderDestroyed -> {
            withPosition(null, event.pieceId)
        }

        is GameEvent.BlenderRestored -> {
            withPosition(event.at, event.pieceId).withRespawnTurn(null, event.pieceId)
        }

        is GameEvent.BananaReturned -> {
            withPosition(event.to, event.pieceId)
        }

        is GameEvent.Smoothie -> {
            event.pieceIds.fold(this) { state, id ->
                state.withPosition(null, id).withRespawnTurn(event.respawnTurn, id)
            }
        }

        is GameEvent.BlenderRespawned -> {
            withPosition(event.at, event.pieceId).withRespawnTurn(null, event.pieceId)
        }

        is GameEvent.Blended -> {
            removing(event.pieceId)
        }

        is GameEvent.Delivered -> {
            // A wildcard banana is not consumed by delivery: it steps off the board and
            // waits for its spawn to clear.
            if (ruleSet.usesWildcards && event.kind == PieceKind.Banana) {
                withPosition(null, event.pieceId)
            } else {
                removing(event.pieceId)
            }
        }

        is GameEvent.ScoreChanged -> {
            updated(scores = scores.updated(event.player, event.newScore))
        }

        is GameEvent.TurnChanged -> {
            updated(activePlayer = event.active)
        }

        is GameEvent.GameEnded -> {
            updated(status = GameStatus.Ended(event.outcome))
        }

        // Cues for the UI that move nothing: the battle's `BlenderDestroyed` moves the
        // piece, the smoothie's points arrive in the `ScoreChanged` after it, and golden
        // is read off the board.
        is GameEvent.BlenderBattle,
        is GameEvent.SmoothiePoured,
        is GameEvent.PineappleTurnedGolden,
        is GameEvent.TurnPassed,
        -> {
            this
        }
    }
