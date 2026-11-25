package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * Every point where the wildcard rule set changes a turn: Golden Pineapple, Banana
 * Split, Tropical Smoothie and Blenderama.
 *
 * [RulesEngine] calls each of these at the moment the Swift engine does, which is what
 * fixes the order of events. All but [returnScheduledBlenders] leave a standard match
 * exactly as it was.
 */
internal object WildcardRules {
    private const val GOLDEN_BLEND_MULTIPLIER = 2
    private const val GOLDEN_DELIVERY_MULTIPLIER = 3

    /**
     * What blending [fruit] is worth before the sign is applied. A Golden Pineapple is
     * worth double to whoever puts it through a blender, and costs its owner double for
     * the same reason. Asked while the fruit is still on the board.
     */
    fun blendValue(
        fruit: Piece,
        state: GameState,
    ): Int = if (state.isGolden(fruit.id)) fruit.kind.baseValue * GOLDEN_BLEND_MULTIPLIER else fruit.kind.baseValue

    /**
     * What delivering [fruit] pays. A Golden Pineapple that makes it across is worth
     * three times its base value rather than the usual two.
     */
    fun deliveryValue(
        fruit: Piece,
        state: GameState,
    ): Int =
        if (state.isGolden(fruit.id)) fruit.kind.baseValue * GOLDEN_DELIVERY_MULTIPLIER else fruit.kind.deliveryValue

    /**
     * Whether a delivered [fruit] goes off the board to come back, rather than leaving
     * the match. True for a wildcard banana, and the delivery is counted toward a Banana
     * Split here.
     */
    fun recyclesOnDelivery(
        fruit: Piece,
        turn: TurnInProgress,
    ): Boolean {
        if (!turn.state.ruleSet.usesWildcards || fruit.kind != PieceKind.Banana) return false
        turn.updateWildcards {
            it.copy(bananasDelivered = it.bananasDelivered.updated(fruit.owner, it.bananasDelivered[fruit.owner] + 1))
        }
        return true
    }

    /** Called after a blend has been scored and announced. */
    fun recordBlend(
        fruit: Piece,
        blendedBy: PlayerId,
        turn: TurnInProgress,
    ) {
        if (!turn.state.ruleSet.usesWildcards) return

        turn.updateWildcards {
            val counted =
                if (fruit.owner == blendedBy) {
                    it.copy(ownFruitBlended = it.ownFruitBlended.updated(blendedBy, it.ownFruitBlended[blendedBy] + 1))
                } else {
                    // Stealing one of their self-blends is the hard counter: their
                    // Blenderama is off for the rest of the match, whatever they do after.
                    it.copy(blenderamaCountered = it.blenderamaCountered.updated(fruit.owner, true))
                }
            counted.copy(
                smoothie = counted.smoothie.updated(blendedBy, counted.smoothie[blendedBy].recording(fruit.kind)),
            )
        }

        val progress = turn.state.wildcards
        if (!progress.smoothie[blendedBy].isComplete || progress.smoothieFired[blendedBy]) return
        turn.updateWildcards { it.copy(smoothieFired = it.smoothieFired.updated(blendedBy, true)) }
        awardSmoothie(blendedBy, turn)
    }

    /**
     * One of every fruit type through your blenders, whoever grew them, pays
     * [RulesEngine.SMOOTHIE_AWARD] points. Once each per match.
     */
    private fun awardSmoothie(
        player: PlayerId,
        turn: TurnInProgress,
    ) {
        turn.addPoints(player, RulesEngine.SMOOTHIE_AWARD)
        turn.emit(GameEvent.SmoothiePoured(player, RulesEngine.SMOOTHIE_AWARD))
        turn.emitScore(player)
    }

    /**
     * The old smoothie: blows the opponent's standing blenders off the board until
     * [RulesEngine.SMOOTHIE_BLACKOUT_TURNS] turns from now, when they return by
     * themselves. A blender already off the board cannot be blown up again.
     *
     * **Retained, no longer called.** Completing a smoothie pays points now. Kept whole
     * and tested against its return; wire it back in by calling it from [recordBlend] in
     * place of [awardSmoothie].
     */
    fun pourSmoothie(
        player: PlayerId,
        turn: TurnInProgress,
    ) {
        val victims =
            turn.state
                .activeBlenders(player.opponent)
                .map { it.id }
                .sorted()
        if (victims.isEmpty()) return

        val due = turn.state.turnNumber + RulesEngine.SMOOTHIE_BLACKOUT_TURNS
        for (id in victims) {
            turn.setPosition(null, id)
            turn.state = turn.state.withRespawnTurn(due, id)
        }
        turn.emit(GameEvent.Smoothie(player, victims, due))
    }

    /**
     * A pineapple turning golden changes no state, because `GameState.isGolden` reads it
     * off the board, so this exists purely so the UI has a beat to play it on. Emitted
     * the moment a player drops to their last pineapple, however the other one went.
     */
    fun announceGoldenPineapples(
        previous: GameState,
        turn: TurnInProgress,
    ) {
        if (!turn.state.ruleSet.usesWildcards) return
        for (player in PlayerId.entries) {
            if (previous.pineapples(player).size <= 1) continue
            val survivor = turn.state.goldenPineapple(player) ?: continue
            turn.emit(GameEvent.PineappleTurnedGolden(survivor.id, player))
        }
    }

    /**
     * Blenderama is a shutout, not just a solo run: any turn that nets its [player]
     * points takes the *other* player's Blenderama off for the rest of the match.
     *
     * Measured on the score rather than the action. It falls out that a Blenderama run
     * never negates itself and buying a blender back never negates theirs: both cost
     * points rather than earning them.
     */
    fun recordScoringTurn(
        player: PlayerId,
        previousScores: Scores,
        turn: TurnInProgress,
    ) {
        if (!turn.state.ruleSet.usesWildcards || turn.state.scores[player] <= previousScores[player]) return
        turn.updateWildcards { it.copy(blenderamaCountered = it.blenderamaCountered.updated(player.opponent, true)) }
    }

    /**
     * An outright win, if the state holds one: Banana Split then Blenderama, for player
     * one then two. Asked twice per turn, before the turn number advances and again
     * after pieces have come back.
     */
    fun win(state: GameState): Outcome? {
        if (!state.ruleSet.usesWildcards) return null
        val progress = state.wildcards
        for (player in PlayerId.entries) {
            if (progress.bananasDelivered[player] >= GameSetup.BANANA_DELIVERIES_TO_WIN) {
                return Outcome.Win(player, WinReason.BananaSplit)
            }
            if (progress.ownFruitBlended[player] >= GameSetup.fruitCount && !progress.blenderamaCountered[player]) {
                return Outcome.Win(player, WinReason.Blenderama)
            }
        }
        return null
    }

    /**
     * A delivered banana reappears on its spawn as soon as that square is clear. Without
     * this a three-banana win would be impossible whenever a spawn happened to be
     * occupied at the wrong moment.
     */
    fun returnWaitingBananas(turn: TurnInProgress) {
        if (!turn.state.ruleSet.usesWildcards) return
        for (banana in turn.state.pieces.filter { it.isWaitingToReturn }) {
            val home = banana.home ?: continue
            if (turn.state.pieceAt(home) != null) continue
            turn.setPosition(home, banana.id)
            turn.emit(GameEvent.BananaReturned(banana.id, home))
        }
    }

    /**
     * Puts back blenders whose smoothie blackout has elapsed. If the spawn happens to be
     * occupied they simply wait for it, exactly as a delivered banana does. Not gated on
     * the rule set: only a pour schedules a blender, whatever the match is playing.
     */
    fun returnScheduledBlenders(turn: TurnInProgress) {
        for (blender in turn.state.pieces.filter { it.isAwaitingRespawn }) {
            val due = blender.respawnTurn ?: continue
            if (due > turn.state.turnNumber) continue
            val home = blender.home ?: continue
            if (turn.state.pieceAt(home) != null) continue
            turn.setPosition(home, blender.id)
            turn.state = turn.state.withRespawnTurn(null, blender.id)
            turn.emit(GameEvent.BlenderRespawned(blender.id, blender.owner, home))
        }
    }

    private fun TurnInProgress.updateWildcards(change: (WildcardProgress) -> WildcardProgress) {
        state = state.updated(wildcards = change(state.wildcards))
    }
}
