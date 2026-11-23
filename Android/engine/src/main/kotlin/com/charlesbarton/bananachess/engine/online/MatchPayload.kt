package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * What one device hands the next. Versioned, complete, and free of anything the UI
 * merely wants: the state in here is the whole truth of the match.
 */
data class MatchPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val rulesVersion: Int = RulesEngine.RULES_VERSION,
    val matchId: String,
    /** Monotonic count of committed moves. The turn-submission guard hangs off this. */
    val moveNumber: Int,
    val state: GameState,
    val lastMove: LastMove? = null,
) {
    companion object {
        /**
         * Bumped by the iOS engine whenever the wire format changes shape. Payloads that
         * do not match exactly are refused rather than guessed at.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 16

        fun newMatch(
            id: String,
            startingPlayer: PlayerId = PlayerId.One,
            options: MatchOptions = MatchOptions(),
        ): MatchPayload =
            MatchPayload(
                matchId = id,
                moveNumber = 0,
                state =
                    GameSetup.newGame(
                        startingPlayer = startingPlayer,
                        ruleSet = options.ruleSet,
                        boardSetup = options.boardSetup,
                        boardWrap = options.boardWrap,
                        blenderBattles = options.blenderBattles,
                    ),
            )
    }
}

/**
 * Enough for the receiving device to animate what the opponent just did instead of
 * snapping the board to a new arrangement.
 */
data class LastMove(
    val action: GameAction,
    val playedBy: PlayerId,
    val events: List<GameEvent>,
) {
    val move: Move? get() = action as? Move
}

/** Everything a match is created with. */
data class MatchOptions(
    val ruleSet: RuleSet = RuleSet.Standard,
    val boardSetup: BoardSetup = BoardSetup.Standard,
    val boardWrap: BoardWrap = BoardWrap.Flat,
    val blenderBattles: Boolean = false,
)
