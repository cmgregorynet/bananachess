package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Scores

/**
 * Every point where the wildcard rule set changes a turn.
 *
 * **S06 seam.** [RulesEngine] already calls each of these at the moment the Swift engine
 * does, so the order of events is fixed. In this slice every function answers as a
 * standard match would, which makes a wildcard match play by standard rules until S06
 * fills the bodies in. Nothing in [RulesEngine] should need to move when it does.
 */
internal object WildcardRules {
    /**
     * What blending [fruit] is worth before the sign is applied.
     * S06: doubled for a golden pineapple (`RulesEngine.swift:532`).
     */
    fun blendValue(
        fruit: Piece,
        state: GameState,
    ): Int = fruit.kind.baseValue

    /**
     * What delivering [fruit] pays.
     * S06: three times the base value for a golden pineapple (`RulesEngine.swift:656`).
     */
    fun deliveryValue(
        fruit: Piece,
        state: GameState,
    ): Int = fruit.kind.deliveryValue

    /**
     * Whether a delivered [fruit] goes off the board to come back, rather than leaving
     * the match.
     * S06: true for a banana under wildcard rules, and `bananasDelivered` is counted
     * here, before the piece leaves the board (`RulesEngine.swift:660-671`).
     */
    fun recyclesOnDelivery(
        fruit: Piece,
        turn: TurnInProgress,
    ): Boolean = false

    /**
     * Called after a blend has been scored and announced.
     * S06: own-fruit count, the Blenderama counter, smoothie progress and the smoothie
     * award with its two events (`RulesEngine.swift:557-588`).
     */
    fun recordBlend(
        fruit: Piece,
        blendedBy: PlayerId,
        turn: TurnInProgress,
    ) = Unit

    /**
     * Called once the action is in the history, before the turn is finished.
     * S06: emit `PineappleTurnedGolden` for each player, one then two, who has just
     * dropped to a last pineapple (`RulesEngine.swift:363`).
     */
    fun announceGoldenPineapples(
        previous: GameState,
        turn: TurnInProgress,
    ) = Unit

    /**
     * Called straight after [announceGoldenPineapples].
     * S06: a turn that nets [player] points counters the opponent's Blenderama
     * (`RulesEngine.swift:385`).
     */
    fun recordScoringTurn(
        player: PlayerId,
        previousScores: Scores,
        turn: TurnInProgress,
    ) = Unit

    /**
     * An outright win, if the state holds one. Asked twice per turn: before the turn
     * number advances and again after pieces have come back.
     * S06: Banana Split then Blenderama, for player one then two (`RulesEngine.swift:631`).
     */
    fun win(state: GameState): Outcome? = null

    /**
     * Called at the turn boundary, after the turn number has advanced.
     * S06: delivered bananas return home in id order when the square is empty, each
     * with `BananaReturned` (`RulesEngine.swift:742`).
     */
    fun returnWaitingBananas(turn: TurnInProgress) = Unit

    /**
     * Called straight after [returnWaitingBananas].
     * S06: blenders with a due `respawnTurn` return home in id order when the square is
     * empty, each with `BlenderRespawned`. Not gated on the rule set in Swift
     * (`RulesEngine.swift:619`).
     */
    fun returnScheduledBlenders(turn: TurnInProgress) = Unit
}
