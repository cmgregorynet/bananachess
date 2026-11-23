package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.GoldenFixture
import com.charlesbarton.bananachess.engine.support.GoldenSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks [Evaluation] against the scores the Swift engine gave to positions sampled
 * from 18 real games, each rebuilt here by replaying the game through the Kotlin rules.
 */
class EvaluationGoldenTest {
    private data class Sampled(
        val label: String,
        val sample: GoldenSample,
        val state: GameState,
    )

    private val sampled: List<Sampled> by lazy {
        GoldenFixture.games.flatMap { game ->
            val states = game.states()
            game.samples.map { Sampled("${game.label} step ${it.step}", it, states[it.step]) }
        }
    }

    @Test
    fun `every golden sample has the Swift keys and evaluations`() {
        assertEquals(18, GoldenFixture.games.size)
        assertEquals(146, sampled.size)

        for ((label, sample, state) in sampled) {
            assertEquals(sample.boardKeyValue, state.boardKey, "boardKey of $label")
            assertEquals(sample.positionKeyValue, state.positionKey, "positionKey of $label")
            assertEquals(sample.evaluationOne, Evaluation.score(state, PlayerId.One), "evaluationOne of $label")
            assertEquals(sample.evaluationTwo, Evaluation.score(state, PlayerId.Two), "evaluationTwo of $label")
        }
    }

    @Test
    fun `replaying to one step gives the same state as replaying the whole game`() {
        val game = GoldenFixture.games.first()
        val states = game.states()
        assertEquals(game.actions.size + 1, states.size)
        for (sample in game.samples) {
            assertEquals(states[sample.step], game.stateAt(sample.step), "${game.label} step ${sample.step}")
        }
    }

    /**
     * Guards the oracle itself: a weight no sample reads could be ported wrong without
     * the golden comparison noticing.
     */
    @Test
    fun `every weight changes the evaluation of at least one golden sample`() {
        val nudged =
            mapOf<String, EvaluationWeights>(
                "score" to EvaluationWeights(score = 101),
                "fruitOnBoard" to EvaluationWeights(fruitOnBoard = 19),
                "advancement" to EvaluationWeights(advancement = 7),
                "threatenedFruit" to EvaluationWeights(threatenedFruit = -15),
                "blenderThreat" to EvaluationWeights(blenderThreat = 13),
                "blenderOnCoveredSpawn" to EvaluationWeights(blenderOnCoveredSpawn = -321),
                "blenderMobility" to EvaluationWeights(blenderMobility = 4),
                "fruitOnOwnBlenderHome" to EvaluationWeights(fruitOnOwnBlenderHome = -9),
                "blenderDestroyed" to EvaluationWeights(blenderDestroyed = -254),
                "clockPressure" to EvaluationWeights(clockPressure = 8),
                "alternateWinThreat" to EvaluationWeights(alternateWinThreat = 8_000),
                "smoothieProgress" to EvaluationWeights(smoothieProgress = 23),
                "goldenPineapple" to EvaluationWeights(goldenPineapple = 31),
            )

        val unread =
            nudged.filterValues { weights ->
                sampled.none { Evaluation.score(it.state, PlayerId.One, weights) != it.sample.evaluationOne }
            }
        assertEquals(emptySet(), unread.keys, "weights no golden sample reads")
    }

    @Test
    fun `a position scores for one player the negation of what it scores for the other`() {
        for ((label, _, state) in sampled) {
            assertEquals(-Evaluation.score(state, PlayerId.One), Evaluation.score(state, PlayerId.Two), label)
        }
    }

    @Test
    fun `a finished match scores a win by its margin and a draw as nothing`() {
        val ahead = GameSetup.newGame().updated(scores = Scores(one = 7, two = 3))

        val wonByOne = ahead.updated(status = GameStatus.Ended(Outcome.Win(PlayerId.One)))
        assertEquals(Evaluation.WIN_SCORE + 4, Evaluation.score(wonByOne, PlayerId.One))
        assertEquals(-Evaluation.WIN_SCORE - 4, Evaluation.score(wonByOne, PlayerId.Two))

        // An alternate win goes to its winner even from behind on points.
        val wonByTwo = ahead.updated(status = GameStatus.Ended(Outcome.Win(PlayerId.Two, WinReason.BananaSplit)))
        assertEquals(Evaluation.WIN_SCORE + 4, Evaluation.score(wonByTwo, PlayerId.Two))
        assertEquals(-Evaluation.WIN_SCORE - 4, Evaluation.score(wonByTwo, PlayerId.One))

        val drawn = ahead.updated(status = GameStatus.Ended(Outcome.Draw))
        assertEquals(0, Evaluation.score(drawn, PlayerId.One))
        assertEquals(0, Evaluation.score(drawn, PlayerId.Two))
    }

    @Test
    fun `the golden samples include finished matches`() {
        assertTrue(sampled.any { it.state.status.isOver })
    }

    // The golden samples never reach the cases below, so each is pinned by hand from the Swift source.

    @Test
    fun `a blender due back after a smoothie costs a quarter of one that must be bought back`() {
        val blender = PieceId("one.blender.0")
        val opening = GameSetup.newGame()
        val boughtBack = opening.withPieces { if (it.id == blender) it.copy(position = null) else it }
        val dueBack = opening.withPieces { if (it.id == blender) it.copy(position = null, respawnTurn = 5) else it }

        // -250 against -250 / 4, which truncates toward zero to -62.
        assertEquals(-250 + 62, Evaluation.score(boughtBack, PlayerId.One) - Evaluation.score(dueBack, PlayerId.One))

        // -254 / 4 is -63.5, so truncation gives -63 where flooring would give -64.
        val heavier = EvaluationWeights(blenderDestroyed = -254)
        assertEquals(1, Evaluation.score(dueBack, PlayerId.One) - Evaluation.score(dueBack, PlayerId.One, heavier))
        assertEquals(
            4,
            Evaluation.score(boughtBack, PlayerId.One) - Evaluation.score(boughtBack, PlayerId.One, heavier),
        )
    }

    @Test
    fun `fruit on its own blender spawn is only a liability while that blender is alive`() {
        val blender = PieceId("one.blender.0")
        val fruit = PieceId("one.fruit.0")
        val spawn = Position(5, 1)
        val heavier = EvaluationWeights(fruitOnOwnBlenderHome = -9)

        fun parked(blenderAt: Position?) =
            GameSetup.newGame().withPieces {
                when (it.id) {
                    blender -> it.copy(position = blenderAt)
                    fruit -> it.copy(position = spawn)
                    else -> it
                }
            }

        val away = parked(blenderAt = Position(3, 3))
        assertEquals(1, Evaluation.score(away, PlayerId.One) - Evaluation.score(away, PlayerId.One, heavier))

        val destroyed = parked(blenderAt = null)
        assertEquals(Evaluation.score(destroyed, PlayerId.One), Evaluation.score(destroyed, PlayerId.One, heavier))
    }

    @Test
    fun `a player with no blender standing gets no credit for closing on a banana`() {
        val state =
            GameSetup
                .newGame(ruleSet = RuleSet.Wildcard)
                .withPieces { if (it.isBlender && it.owner == PlayerId.Two) it.copy(position = null) else it }

        // Threat terms only, isolated by switching the weight off.
        // One: split 4000/14² = 20, blenderama 4000/7² = 81, answering two's blenderama
        // (4000/12² = 27, four moves from scoring) 27·2/6 = 9, answering two's split 20·2/6 = 6.
        // Two: split 20, blenderama 27, answering one's blenderama by delivery 81·2/6 = 27,
        // and nothing for one's split because no blender of theirs can reach a banana.
        val threats = (20 + 81 + 9 + 6) - (20 + 27 + 27)
        val without = EvaluationWeights(alternateWinThreat = 0)
        assertEquals(threats, Evaluation.score(state, PlayerId.One) - Evaluation.score(state, PlayerId.One, without))
    }

    private fun GameState.withPieces(change: (Piece) -> Piece): GameState =
        GameState(
            pieces = pieces.map(change),
            activePlayer = activePlayer,
            scores = scores,
            status = status,
            turnNumber = turnNumber,
            history = history,
            ruleSet = ruleSet,
            boardSetup = boardSetup,
            boardWrap = boardWrap,
            blenderBattles = blenderBattles,
            wildcards = wildcards,
        )
}
