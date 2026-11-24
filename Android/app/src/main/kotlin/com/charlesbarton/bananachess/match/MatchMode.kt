package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.online.LastMove
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who is playing, and from which seats. Serializable because the saved match records it; the
 * names it is written under are fixed, so renaming a case never orphans a saved match.
 */
@Serializable
sealed interface MatchMode {
    /** Two players sharing one phone. */
    @Serializable
    @SerialName("local")
    data object LocalVersus : MatchMode

    @Serializable
    @SerialName("computer")
    data class Computer(
        val difficulty: CpuDifficulty,
        val humanPlays: PlayerId,
    ) : MatchMode

    /**
     * Correspondence play by link: no server, no accounts. Each side sends the other a link
     * containing the whole match.
     */
    @Serializable
    @SerialName("byLink")
    data class ByLink(
        val matchId: String,
        val localPlayer: PlayerId,
    ) : MatchMode

    /**
     * Which engine player sits at the bottom of the screen. The human always does; in local
     * pass-and-play, player one does.
     */
    val bottomPlayer: PlayerId
        get() =
            when (this) {
                LocalVersus -> PlayerId.One
                is Computer -> humanPlays
                is ByLink -> localPlayer
            }

    /** Whether this device may move [player]'s pieces. */
    fun canControl(player: PlayerId): Boolean =
        when (this) {
            LocalVersus -> true
            is Computer -> player == humanPlays
            is ByLink -> player == localPlayer
        }

    fun name(player: PlayerId): String =
        when (this) {
            LocalVersus -> if (player == PlayerId.One) "Player One" else "Player Two"
            is Computer -> if (player == humanPlays) "You" else "${difficulty.displayName} CPU"
            is ByLink -> if (player == localPlayer) "You" else "Your opponent"
        }
}

/**
 * Everything the Match screen needs to open: who is playing, and the position to open on.
 *
 * @param lastMove in a link match, the move that produced [state] as the link carried it. It
 *   goes back out in the next link if the match ends without another local move.
 * @param animateFrom the position to show first, with [lastMove]'s events played from it so the
 *   opponent's move is seen rather than just its result. Null opens on [state] directly.
 */
data class MatchStart(
    val mode: MatchMode,
    val state: GameState,
    val lastMove: LastMove? = null,
    val animateFrom: GameState? = null,
)
