package com.charlesbarton.bananachess.engine.model

/**
 * The ordered record of everything one move caused. The UI animates these in order
 * and never recomputes rules or scores for itself.
 */
sealed interface GameEvent {
    data class Moved(
        val pieceId: PieceId,
        val from: Position,
        val to: Position,
    ) : GameEvent

    /**
     * A blender battle was fought over a square. Carries its whole arithmetic so the
     * board can show why it went the way it did; the [BlenderDestroyed] that follows
     * says which blender actually left.
     */
    data class BlenderBattle(
        val report: BattleReport,
        val at: Position,
    ) : GameEvent

    /**
     * A blender ran into another and destroyed it. The attacker takes the square; the
     * victim is gone until its owner buys it back.
     */
    data class BlenderDestroyed(
        val pieceId: PieceId,
        val owner: PlayerId,
        val by: PieceId,
        val at: Position,
    ) : GameEvent

    /** A destroyed blender bought back onto its spawn. */
    data class BlenderRestored(
        val pieceId: PieceId,
        val owner: PlayerId,
        val at: Position,
        val cost: Int,
    ) : GameEvent

    /** A smoothie victim returning on its own, for nothing. */
    data class BlenderRespawned(
        val pieceId: PieceId,
        val owner: PlayerId,
        val at: Position,
    ) : GameEvent

    /** A delivered banana reappearing on its spawn. */
    data class BananaReturned(
        val pieceId: PieceId,
        val to: Position,
    ) : GameEvent

    /**
     * [points] is signed: positive when a blender took the other player's fruit,
     * negative when it took its owner's own.
     */
    data class Blended(
        val pieceId: PieceId,
        val kind: PieceKind,
        val fruitOwner: PlayerId,
        val at: Position,
        val awardedTo: PlayerId,
        val points: Int,
    ) : GameEvent

    data class Delivered(
        val pieceId: PieceId,
        val kind: PieceKind,
        val owner: PlayerId,
        val at: Position,
        val points: Int,
    ) : GameEvent

    data class ScoreChanged(
        val player: PlayerId,
        val newScore: Int,
    ) : GameEvent

    data class TurnPassed(
        val skipped: PlayerId,
    ) : GameEvent

    data class TurnChanged(
        val active: PlayerId,
    ) : GameEvent

    data class GameEnded(
        val outcome: Outcome,
    ) : GameEvent

    /** The surviving pineapple has been promoted after the first one was blended. */
    data class PineappleTurnedGolden(
        val pieceId: PieceId,
        val owner: PlayerId,
    ) : GameEvent

    /** A player completed a Tropical Smoothie and collected the bonus. */
    data class SmoothiePoured(
        val player: PlayerId,
        val points: Int,
    ) : GameEvent

    /**
     * A player completed a Tropical Smoothie; the opponent's blenders are blown off
     * the board and return by themselves at the start of [respawnTurn].
     *
     * **Retained, not emitted.** The smoothie pays points now instead. Kept whole
     * against the day the blowout comes back.
     */
    data class Smoothie(
        val player: PlayerId,
        val pieceIds: List<PieceId>,
        val respawnTurn: Int,
    ) : GameEvent
}

sealed interface Outcome {
    /** [by] defaults to the ordinary case: whoever had the higher score when the fruit ran out. */
    data class Win(
        val player: PlayerId,
        val by: WinReason = WinReason.Score,
    ) : Outcome

    data object Draw : Outcome

    val winner: PlayerId? get() = (this as? Win)?.player

    val reason: WinReason? get() = (this as? Win)?.by

    fun isWinFor(player: PlayerId): Boolean = winner == player
}
