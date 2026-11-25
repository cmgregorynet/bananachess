package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Outcome

// The strings the Swift fixture writer records (`Engine/Sources/ConformanceFixtures/main.swift`).

fun describe(action: GameAction): String = action.toString()

fun describe(event: GameEvent): String =
    when (event) {
        is GameEvent.Moved -> {
            "moved ${event.pieceId} ${event.from} ${event.to}"
        }

        is GameEvent.BlenderBattle -> {
            val report = event.report
            "battle ${report.attacker.rawValue} ${report.attackerFruit}+${report.attackerBonus}" +
                "+${report.attackerRoll} v ${report.defenderFruit}+${report.defenderRoll} at ${event.at}"
        }

        is GameEvent.BlenderDestroyed -> {
            "blenderDestroyed ${event.pieceId} ${event.owner.rawValue} by ${event.by} at ${event.at}"
        }

        is GameEvent.BlenderRestored -> {
            "blenderRestored ${event.pieceId} ${event.owner.rawValue} at ${event.at} cost ${event.cost}"
        }

        is GameEvent.BlenderRespawned -> {
            "blenderRespawned ${event.pieceId} ${event.owner.rawValue} at ${event.at}"
        }

        is GameEvent.BananaReturned -> {
            "bananaReturned ${event.pieceId} ${event.to}"
        }

        is GameEvent.Blended -> {
            "blended ${event.pieceId} ${event.kind.rawValue} of ${event.fruitOwner.rawValue} at ${event.at}" +
                " to ${event.awardedTo.rawValue} ${event.points}"
        }

        is GameEvent.Delivered -> {
            "delivered ${event.pieceId} ${event.kind.rawValue} ${event.owner.rawValue} at ${event.at} ${event.points}"
        }

        is GameEvent.ScoreChanged -> {
            "score ${event.player.rawValue} ${event.newScore}"
        }

        is GameEvent.SmoothiePoured -> {
            "smoothie ${event.player.rawValue} ${event.points}"
        }

        is GameEvent.Smoothie -> {
            val ids = event.pieceIds.joinToString(",") { it.rawValue }
            "smoothieBlast ${event.player.rawValue} $ids ${event.respawnTurn}"
        }

        is GameEvent.PineappleTurnedGolden -> {
            "golden ${event.pieceId} ${event.owner.rawValue}"
        }

        is GameEvent.TurnPassed -> {
            "turnPassed ${event.skipped.rawValue}"
        }

        is GameEvent.TurnChanged -> {
            "turnChanged ${event.active.rawValue}"
        }

        is GameEvent.GameEnded -> {
            "ended ${describe(event.outcome)}"
        }
    }

fun describe(outcome: Outcome): String =
    when (outcome) {
        Outcome.Draw -> "draw"
        is Outcome.Win -> "${outcome.player.rawValue} by ${outcome.by.rawValue}"
    }

/** The compact board: `id@row,column`, or `id@-` for a piece off the board, in id order. */
fun places(state: GameState): String =
    state.pieces.joinToString(" ") { piece ->
        val square = piece.position
        if (square == null) "${piece.id}@-" else "${piece.id}@${square.row},${square.column}"
    }
