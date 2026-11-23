package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet

/** Who is playing, and from which seats. */
sealed interface MatchMode {
    /** Two players sharing one phone. */
    data object LocalVersus : MatchMode

    data class Computer(
        val difficulty: CpuDifficulty,
        val humanPlays: PlayerId,
    ) : MatchMode

    /**
     * Correspondence play by link: no server, no accounts. Each side sends the other a link
     * containing the whole match.
     */
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

/** The choices on the start screen that decide what board is dealt and which rules it is played under. */
data class MatchOptions(
    val ruleSet: RuleSet = RuleSet.Standard,
    val boardSetup: BoardSetup = BoardSetup.Standard,
    val boardWrap: BoardWrap = BoardWrap.Flat,
    val blenderBattles: Boolean = false,
)

/** Everything the Match screen needs to open: who is playing, and the position to open on. */
data class MatchStart(
    val mode: MatchMode,
    val state: GameState,
)
