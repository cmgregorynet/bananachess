package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.online.MatchOptions
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the match on the Match route comes from. It travels as the route's argument, so it is
 * saved with the back stack and the same match can be opened again after the process was killed
 * under it. Values only: nothing here may hold a live object.
 */
@Serializable
sealed interface MatchSource {
    /**
     * A match dealt fresh from the start screen's choices. [seed] deals a Random board, so the
     * same board comes back after a process restart; the other boards do not use it.
     *
     * No defaults: every field is written into the saved route, so a later default can never
     * change a match that was saved under an earlier one.
     */
    @Serializable
    @SerialName("new")
    data class New(
        val kind: Kind,
        val ruleSet: RuleSet,
        val boardSetup: BoardSetup,
        val boardWrap: BoardWrap,
        val blenderBattles: Boolean,
        val seed: ULong,
    ) : MatchSource {
        constructor(kind: Kind, options: MatchOptions, seed: ULong) : this(
            kind = kind,
            ruleSet = options.ruleSet,
            boardSetup = options.boardSetup,
            boardWrap = options.boardWrap,
            blenderBattles = options.blenderBattles,
            seed = seed,
        )

        val options: MatchOptions get() = MatchOptions(ruleSet, boardSetup, boardWrap, blenderBattles)

        val mode: MatchMode
            get() =
                when (kind) {
                    Kind.Local -> MatchMode.LocalVersus
                    is Kind.Computer -> MatchMode.Computer(kind.difficulty, kind.humanPlays)
                }

        /** The opening position, the same every time for the same source. Player one always moves first. */
        fun deal(): MatchStart =
            MatchStart(
                mode = mode,
                state =
                    GameSetup.newGame(
                        startingPlayer = PlayerId.One,
                        ruleSet = ruleSet,
                        boardSetup = boardSetup,
                        boardWrap = boardWrap,
                        blenderBattles = blenderBattles,
                        seed = seed,
                    ),
            )
    }

    /** The match saved on this phone. Reserved: S20 adds the snapshot store that loads it. */
    @Serializable
    @SerialName("saved")
    data object Saved : MatchSource

    /**
     * A match by link: an invitation just made here, or a link someone sent. [link] is the URL
     * exactly as it was made or received, so the match it opens is the one the link holds, byte
     * for byte; [localPlayer] is the seat this phone plays.
     */
    @Serializable
    @SerialName("byLink")
    data class ByLink(
        val link: String,
        val localPlayer: PlayerId,
    ) : MatchSource

    /** Who a new match is played against. */
    @Serializable
    sealed interface Kind {
        @Serializable
        @SerialName("local")
        data object Local : Kind

        @Serializable
        @SerialName("computer")
        data class Computer(
            val difficulty: CpuDifficulty,
            val humanPlays: PlayerId,
        ) : Kind
    }
}
