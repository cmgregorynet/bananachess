package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.support.ConformanceFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Holds `GameState.applying` to the engine: replaying a turn's events must land where the turn did. */
class EventReplayTest {
    @Test
    fun `replaying each step's events reproduces the step for every fixture game`() {
        var steps = 0
        val kindsSeen = mutableSetOf<String>()

        for (game in ConformanceFixture.games) {
            var before = game.newGame()
            replayFixtureGame(game).forEachIndexed { index, transition ->
                assertReplays(before, transition, "${game.label} step $index")
                transition.events.mapTo(kindsSeen) { it::class.simpleName.orEmpty() }
                before = transition.state
                steps += 1
            }
        }

        assertEquals(EXPECTED_STEPS, steps)
        // Everything the engine emits today. The other three are covered below.
        assertEquals(
            setOf(
                "Moved",
                "BlenderBattle",
                "BlenderDestroyed",
                "BlenderRestored",
                "BananaReturned",
                "Blended",
                "Delivered",
                "ScoreChanged",
                "SmoothiePoured",
                "PineappleTurnedGolden",
                "TurnChanged",
                "GameEnded",
            ),
            kindsSeen,
        )
    }

    @Test
    fun `a smoothie blast takes each blender off the board with its return date`() {
        val state = GameState(pieces = listOf(blender("two.blender.0", 2, 2), blender("two.blender.1", 2, 4), fruit))

        val after =
            state.applying(
                GameEvent.Smoothie(PlayerId.One, listOf(id("two.blender.0"), id("two.blender.1")), 3),
            )

        for (blender in after.blenders(PlayerId.Two)) {
            assertNull(blender.position)
            assertEquals(3, blender.respawnTurn)
            assertTrue(blender.isAwaitingRespawn)
        }
        assertEquals(fruit, after.piece(fruit.id))
    }

    @Test
    fun `a respawn puts the blender back and clears its return date`() {
        val waiting = blender("two.blender.0", 2, 2).copy(position = null, respawnTurn = 3)
        val state = GameState(pieces = listOf(waiting, fruit))

        val after = state.applying(GameEvent.BlenderRespawned(waiting.id, PlayerId.Two, Position(0, 1)))

        assertEquals(waiting.copy(position = Position(0, 1), respawnTurn = null), after.piece(waiting.id))
    }

    @Test
    fun `a buy-back clears a return date too`() {
        val waiting = blender("two.blender.0", 2, 2).copy(position = null, respawnTurn = 3)
        val state = GameState(pieces = listOf(waiting, fruit))

        val after = state.applying(GameEvent.BlenderRestored(waiting.id, PlayerId.Two, Position(0, 4), cost = 3))

        assertEquals(waiting.copy(position = Position(0, 4), respawnTurn = null), after.piece(waiting.id))
    }

    @Test
    fun `cues for the UI change nothing`() {
        val state = GameState(pieces = listOf(blender("two.blender.0", 2, 2), fruit), ruleSet = RuleSet.Wildcard)

        assertEquals(state, state.applying(GameEvent.TurnPassed(PlayerId.Two)))
        assertEquals(state, state.applying(GameEvent.SmoothiePoured(PlayerId.One, 3)))
        assertEquals(state, state.applying(GameEvent.PineappleTurnedGolden(fruit.id, PlayerId.One)))
    }

    @Test
    fun `a delivered banana is kept off the board in a wildcard match and removed in a standard one`() {
        val banana = Piece(id("one.banana.0"), PlayerId.One, PieceKind.Banana, Position(0, 3), home = Position(4, 3))
        val delivered = GameEvent.Delivered(banana.id, PieceKind.Banana, PlayerId.One, Position(0, 3), 2)

        val wildcard = GameState(pieces = listOf(banana, fruit), ruleSet = RuleSet.Wildcard).applying(delivered)
        assertEquals(banana.copy(position = null), wildcard.piece(banana.id))

        val standard = GameState(pieces = listOf(banana, fruit)).applying(delivered)
        assertNull(standard.piece(banana.id))
    }

    @Test
    fun `replay leaves turn number, history, arrangements and wildcard progress alone`() {
        val state = GameState(pieces = listOf(blender("two.blender.0", 2, 2), fruit), ruleSet = RuleSet.Wildcard)
        val events =
            listOf(
                GameEvent.Moved(fruit.id, Position(3, 2), Position(2, 2)),
                GameEvent.Blended(fruit.id, PieceKind.Pineapple, PlayerId.One, Position(2, 2), PlayerId.Two, 3),
                GameEvent.ScoreChanged(PlayerId.Two, 3),
                GameEvent.TurnChanged(PlayerId.Two),
                GameEvent.GameEnded(Outcome.Win(PlayerId.Two, WinReason.Score)),
            )

        val after = events.fold(state) { replayed, event -> replayed.applying(event) }

        assertNull(after.piece(fruit.id))
        assertEquals(Scores(one = 0, two = 3), after.scores)
        assertEquals(PlayerId.Two, after.activePlayer)
        assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.Two)), after.status)
        assertEquals(state.turnNumber, after.turnNumber)
        assertEquals(state.history, after.history)
        assertEquals(state.boardHistory, after.boardHistory)
        assertEquals(state.wildcards, after.wildcards)
    }

    private val fruit = Piece(id("one.pineapple.0"), PlayerId.One, PieceKind.Pineapple, Position(3, 2), Position(4, 2))

    private fun id(raw: String) = PieceId(raw)

    private fun blender(
        id: String,
        row: Int,
        column: Int,
    ): Piece = Piece(PieceId(id), PlayerId.Two, PieceKind.Blender, Position(row, column), home = Position(0, 1))

    private companion object {
        const val EXPECTED_STEPS = 13_829
    }
}

/**
 * Folds `applying` over [transition]'s events from [before] and checks it lands on the
 * same pieces, scores, active player and status as the engine's own state.
 */
fun assertReplays(
    before: GameState,
    transition: RulesEngine.Transition,
    at: String = "the transition",
) {
    val replayed = transition.events.fold(before) { state, event -> state.applying(event) }
    val expected = transition.state

    assertEquals(expected.pieces, replayed.pieces, "pieces after replaying $at")
    assertEquals(expected.scores, replayed.scores, "scores after replaying $at")
    assertEquals(expected.activePlayer, replayed.activePlayer, "active player after replaying $at")
    assertEquals(expected.status, replayed.status, "status after replaying $at")
}
