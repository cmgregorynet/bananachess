package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.cpu.Evaluation
import com.charlesbarton.bananachess.engine.cpu.EvaluationWeights
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.position
import com.charlesbarton.bananachess.engine.support.withOwnFruitBlended
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class DefenceFixture(
    val state: GameState,
    val fruit: PieceId,
    val blender: PieceId,
)

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/BlenderamaDefenceTests.swift`.
 *
 * Blenderama is a shutout: it dies the moment the defender scores at all. The CPU's
 * counter has to be priced on *that*, on the nearest route to the scoreboard, and not
 * on walking a blender toward the attacker's fruit, which was the answer under the
 * rules as they stood before the shutout came in.
 */
@DisplayName("Blenderama defence")
class BlenderamaDefenceTests {
    /**
     * The attacker is two self-blends from an outright win, which is far enough ahead to
     * sit beyond any search horizon a phone will reach, so the evaluation, not the
     * search, has to point at the answer.
     *
     * From here the defender has two plausible moves: walk its fruit a row nearer home,
     * or walk its blender a step nearer the attacker's fruit. Only the first one leads
     * anywhere: it is two moves from a delivery, while the blender is still four from a
     * blend. The fruit move therefore has to evaluate higher.
     */
    private fun fixture(): DefenceFixture {
        val builder = BoardBuilder()
        // Defender: one fruit three rows from its delivery row, one distant blender.
        val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(3, 0))
        val blender = builder.blender(PlayerId.One, at = position(5, 0), home = position(5, 0))
        // Attacker: two fruit left and a blender already touching one of them.
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(1, 4))
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(2, 5))
        builder.blender(PlayerId.Two, at = position(0, 3), home = position(0, 3))

        // Four of the attacker's six already blended: two more and it is over.
        val state =
            builder
                .state(active = PlayerId.One, ruleSet = RuleSet.Wildcard)
                .withOwnFruitBlended(PlayerId.Two, GameSetup.fruitCount - 2)
        return DefenceFixture(state, fruit, blender)
    }

    @Test
    @DisplayName("the nearest route to the scoreboard is what answers a Blenderama")
    fun scoringRouteOutranksBlenderApproach() {
        val (state, fruit, blender) = fixture()
        val weights = EvaluationWeights()
        val actions = RulesEngine.legalActions(state)

        fun resulting(
            id: PieceId,
            to: Position,
        ): Int {
            val action = assertNotNull(actions.firstOrNull { it is Move && it.pieceId == id && it.destination == to })
            val after = RulesEngine.apply(action, state).state
            return Evaluation.score(after, PlayerId.One, weights)
        }

        // The attacker's Blenderama is live, so there is something to answer.
        assertFalse(state.wildcards.blenderamaCountered[PlayerId.Two])

        val advanceFruit = resulting(fruit, to = position(2, 0))
        val advanceBlender = resulting(blender, to = position(4, 1))

        assertTrue(
            advanceFruit > advanceBlender,
            "a Blenderama is two moves off: closing on a delivery ($advanceFruit) " +
                "must beat marching a blender ($advanceBlender)",
        )
    }

    /**
     * The counter credit must stay strictly below the value of the threat it answers,
     * or the CPU hovers one move from scoring forever rather than scoring.
     */
    @Test
    @DisplayName("actually scoring beats standing next to a score")
    fun scoringBeatsHovering() {
        val builder = BoardBuilder()
        val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 0))
        builder.blender(PlayerId.One, at = position(5, 0), home = position(5, 0))
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(2, 5))
        builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(3, 4))
        builder.blender(PlayerId.Two, at = position(0, 3), home = position(0, 3))
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

        val sideways =
            actions.firstOrNull {
                it is Move && it.pieceId == fruit && it.destination.row != PlayerId.One.deliveryRow
            }
        if (sideways != null) {
            val hovering = RulesEngine.apply(sideways, state).state
            assertTrue(
                Evaluation.score(after, PlayerId.One, EvaluationWeights()) >
                    Evaluation.score(hovering, PlayerId.One, EvaluationWeights()),
            )
        }
    }
}
