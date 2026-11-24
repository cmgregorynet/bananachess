package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.WildcardStanding
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.model.wildcardStanding
import com.charlesbarton.bananachess.engine.rules.RulesEngine

/**
 * A short, non-blocking label over the board: the ×2 on a delivery, the wry note on a
 * self-blend. Never interrupts play.
 */
data class BoardFlash(
    val id: Long,
    val text: String,
    val position: Position,
)

/** Pulp thrown at the square where a fruit was blended. */
data class BlendBurst(
    val id: Long,
    val kind: PieceKind,
    val position: Position,
    val seed: ULong,
) {
    companion object {
        /** How long a burst stays on the board: its animation and a little to spare. */
        const val LIFETIME_MILLIS = 750L
    }
}

/**
 * The occasional splatter that clears the board. Tied to the burst that threw it so the
 * view can find where on screen it came from.
 */
data class Splatter(
    val id: Long,
    val burstId: Long,
    val kind: PieceKind,
    val seed: ULong,
) {
    companion object {
        /** How long a splatter stays on screen, drying included. */
        const val LIFETIME_MILLIS = 1_700L
    }
}

/**
 * Where a self-blend sits relative to Blenderama, which is the only reason to make one that
 * costs this much. Spelled out at the moment of the decision: blending your own fruit with
 * the run already dead is pure loss, and a player should never discover that only when the
 * match ends.
 */
sealed interface BlenderamaStanding {
    data object NotPlaying : BlenderamaStanding

    data object CompletesIt : BlenderamaStanding

    data class Counts(
        val soFar: Int,
        val target: Int,
    ) : BlenderamaStanding

    data object AlreadyOff : BlenderamaStanding
}

/** A self-blend the player has aimed but not yet agreed to pay for. */
data class PendingSelfBlend(
    val move: Move,
    val kind: PieceKind,
    val blenderama: BlenderamaStanding,
) {
    val prompt: String get() = "Blend your own ${kind.rawValue}?"

    val detail: String
        get() {
            val points = if (kind.baseValue == 1) "point" else "points"
            val cost = "It leaves the board and costs you ${kind.baseValue} $points."
            return when (blenderama) {
                BlenderamaStanding.NotPlaying -> {
                    cost
                }

                BlenderamaStanding.CompletesIt -> {
                    "$cost It is also your last one — this wins the match by Blenderama."
                }

                is BlenderamaStanding.Counts -> {
                    "$cost Blenderama: ${blenderama.soFar + 1} of ${blenderama.target} once this is done."
                }

                BlenderamaStanding.AlreadyOff -> {
                    "$cost Blenderama is already off for this match, so this is points down and nothing else."
                }
            }
        }
}

/** Everything one side's scoreboard shows, decided here so the view only lays it out. */
data class Scoreboard(
    val name: String,
    val score: Int,
    val remainingFruit: List<Piece>,
    val pointsOnBoard: Int,
    val isActive: Boolean,
    /** Fruit is the clock: the last one on either side ends the match. */
    val isOnLastFruit: Boolean,
    val wildcards: WildcardStanding?,
    val accessibilityLabel: String,
) {
    /** The line beside the fruit icons. */
    val fruitDetail: String
        get() =
            when {
                remainingFruit.isEmpty() -> "no fruit left"
                isOnLastFruit -> "· last fruit — ends the match"
                else -> "· $pointsOnBoard pts on the board"
            }
}

/**
 * Everything the Match screen shows. Stored fields are what the view model changes; the
 * rest is read off them here, so no view ever works out a rule or a label for itself.
 */
data class MatchUiState(
    val mode: MatchMode,
    /**
     * What the board is showing right now. During a move this walks the transition's events
     * one at a time; between moves it equals [authoritativeState].
     */
    val displayState: GameState,
    /** The engine's truth. */
    val authoritativeState: GameState,
    val selectedPieceId: PieceId? = null,
    val legalDestinations: Set<Position> = emptySet(),
    val isResolving: Boolean = false,
    val isOpponentThinking: Boolean = false,
    val flash: BoardFlash? = null,
    val blendBursts: List<BlendBurst> = emptyList(),
    val splatter: Splatter? = null,
    val pendingSelfBlend: PendingSelfBlend? = null,
    val errorMessage: String? = null,
    val isShowingRestartConfirmation: Boolean = false,
    val hasUndoHistory: Boolean = false,
    /** The first-match introduction, over everything else, until the player closes it. */
    val isTeachingOverlayShowing: Boolean = false,
) {
    // Board geometry

    val bottomPlayer: PlayerId get() = mode.bottomPlayer
    val topPlayer: PlayerId get() = mode.bottomPlayer.opponent

    fun seat(player: PlayerId): PlayerSeat = if (player == bottomPlayer) PlayerSeat.Bottom else PlayerSeat.Top

    /**
     * The board is rotated a half turn when the bottom seat belongs to player two, so each
     * device always looks at the game from its own player's side.
     */
    val isRotated: Boolean get() = bottomPlayer == PlayerId.Two

    // Interaction

    val isInputLocked: Boolean
        get() =
            isResolving ||
                isOpponentThinking ||
                authoritativeState.status.isOver ||
                !mode.canControl(authoritativeState.activePlayer)

    fun isSelectable(piece: Piece): Boolean = !isInputLocked && piece.owner == displayState.activePlayer

    /**
     * The destroyed blender that tapping this spawn would buy back, if any. A blender may
     * come back on either of its owner's spawns, so the square decides which purchase is on
     * offer, not the piece.
     */
    fun restorableBlender(square: Position): PieceId? {
        if (isInputLocked) return null
        return RulesEngine
            .legalActions(authoritativeState)
            .firstNotNullOfOrNull { action -> (action as? RestoreBlender)?.takeIf { it.at == square }?.pieceId }
    }

    /** Spawn squares that currently show a buy-back offer. */
    val purchasableSpawns: List<Position>
        get() = RulesEngine.legalActions(displayState).mapNotNull { (it as? RestoreBlender)?.at }

    // Match controls

    /** A link match belongs to both players, so only the modes played on one phone restart. */
    val canPlayAgain: Boolean get() = mode !is MatchMode.ByLink

    val canUndo: Boolean get() = hasUndoHistory && !isResolving && mode !is MatchMode.ByLink

    // Presentation

    val outcome: Outcome? get() = displayState.outcome

    val resultText: String?
        get() =
            when (val outcome = outcome) {
                null -> null
                Outcome.Draw -> "A draw — nothing left to blend."
                is Outcome.Win -> "${mode.name(outcome.player)} ${outcome.by.headline}."
            }

    /** The one-line explanation under the result, for wins that were not about points. */
    val resultDetail: String?
        get() =
            when (outcome?.reason) {
                WinReason.BananaSplit -> "Every banana across. The score did not matter."
                WinReason.Blenderama -> "Every one of their own fruit, through their own blenders."
                WinReason.Score, null -> null
            }

    val ruleSet: RuleSet get() = displayState.ruleSet

    val turnIndicatorText: String
        get() {
            if (displayState.status.isOver) return "Match over"
            if (isOpponentThinking && mode is MatchMode.Computer) {
                return "${mode.difficulty.displayName} CPU is thinking…"
            }
            return "${mode.name(displayState.activePlayer)} to move"
        }

    fun scoreboard(player: PlayerId): Scoreboard {
        val name = mode.name(player)
        val score = displayState.scores[player]
        val fruit = displayState.fruit(player)
        val isActive = displayState.activePlayer == player && !displayState.status.isOver
        return Scoreboard(
            name = name,
            score = score,
            remainingFruit = fruit,
            pointsOnBoard = displayState.fruitValueRemaining(player),
            isActive = isActive,
            isOnLastFruit = fruit.size == 1,
            wildcards = if (ruleSet.usesWildcards) displayState.wildcardStanding(player) else null,
            accessibilityLabel = "$name, score $score, ${fruit.size} fruit left" + if (isActive) ", to move" else "",
        )
    }

    // Board presentation

    val piecesOnBoard: List<Piece> get() = displayState.pieces.filter { it.isOnBoard }

    val blendersAwaitingRespawn: List<Piece> get() = displayState.destroyedBlenders.filter { it.isAwaitingRespawn }

    /**
     * Read from the match, not from a constant: where the spawns are depends on which board
     * this match was dealt.
     */
    val blenderHomes: Set<Position> get() = displayState.allSpawnSquares

    val selectedSquare: Position? get() = selectedPieceId?.let { displayState.piece(it)?.position }

    /** Which seat's fruit scores by reaching this square, if either. */
    fun deliveryEdge(position: Position): PlayerSeat? =
        when (position.row) {
            PlayerId.One.deliveryRow -> seat(PlayerId.One)
            PlayerId.Two.deliveryRow -> seat(PlayerId.Two)
            else -> null
        }
}
