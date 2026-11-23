package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.position
import com.charlesbarton.bananachess.engine.support.withOwnFruitBlended
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/BlenderamaDefenceTests.swift`.
 *
 * Blenderama is a shutout: it dies the moment the defender scores at all. The Swift suite
 * is mostly about how the CPU's evaluation prices that, and the evaluation is not part of
 * the rules. What is ported here is the rule those tests rest on. The comparisons of
 * `Evaluation.score` (all of `scoringRouteOutranksBlenderApproach`, and the last assertion
 * of `scoringBeatsHovering`) belong with the evaluation port.
 */
@DisplayName("Blenderama defence")
class BlenderamaDefenceTests {
    @Test
    @DisplayName("actually scoring beats standing next to a score (rules half: scoring shuts the run out)")
    fun scoringBeatsHovering() {
        val builder = BoardBuilder()
        val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 0))
        builder.blender(PlayerId.One, at = position(5, 0), home = position(5, 0))
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(2, 5))
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(3, 4))
        builder.blender(PlayerId.Two, at = position(0, 3), home = position(0, 3))
        // Four of the attacker's six already blended: two more and it is over.
        val state =
            builder
                .state(active = PlayerId.One, ruleSet = RuleSet.Wildcard)
                .withOwnFruitBlended(PlayerId.Two, GameSetup.fruitCount - 2)

        val actions = RulesEngine.legalActions(state)
        val delivery =
            assertNotNull(
                actions.firstOrNull {
                    it is Move && it.pieceId == fruit && it.destination.row == PlayerId.One.deliveryRow
                },
            )
        val after = RulesEngine.apply(delivery, state).state
        // Scoring is what shuts the run out, permanently.
        assertTrue(after.wildcards.blenderamaCountered[PlayerId.Two])
    }
}
