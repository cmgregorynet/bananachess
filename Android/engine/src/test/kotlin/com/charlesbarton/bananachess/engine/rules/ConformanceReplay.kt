package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.support.FixtureGame
import com.charlesbarton.bananachess.engine.support.parseAction
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays one fixture game through [RulesEngine] and fails on the first step that
 * differs from what the Swift engine recorded, naming the game and the step.
 *
 * Checks, per step: the legal actions when the fixture lists them (exact list, in
 * order), then the events, the board, the active player, the scores and the turn
 * number after applying the recorded action. At the end: the outcome, or that the game
 * is still in progress when the recorder stopped at its step cap.
 *
 * Returns every transition in order, so a caller can make further checks on them.
 */
fun replayFixtureGame(game: FixtureGame): List<RulesEngine.Transition> {
    var state: GameState = game.newGame()
    val transitions = mutableListOf<RulesEngine.Transition>()

    game.steps.forEachIndexed { index, step ->
        val at = "${game.label} step $index (${step.action})"
        val legal = RulesEngine.legalActions(state)

        step.legalBefore?.let { assertEquals(it, legal.map(::describe), "legal actions before $at") }

        val action =
            legal.firstOrNull { describe(it) == step.action }
                ?: fail("$at is not legal; legal actions are ${legal.map(::describe)}")
        assertEquals(parseAction(step.action), action, "parsed action at $at")

        val transition = RulesEngine.apply(action, state)
        val after = transition.state

        assertEquals(step.events, transition.events.map(::describe), "events at $at")
        assertEquals(step.after, places(after), "board after $at")
        assertEquals(step.activePlayerValue, after.activePlayer, "active player after $at")
        assertEquals(step.scores, listOf(after.scores.one, after.scores.two), "scores after $at")
        assertEquals(step.turnNumber, after.turnNumber, "turn number after $at")

        transitions += transition
        state = after
    }

    val expectedOutcome = game.outcome
    if (expectedOutcome == null) {
        assertTrue(!state.status.isOver, "${game.label} should still be in progress, was ${state.status}")
    } else {
        assertEquals(expectedOutcome, state.outcome?.let(::describe), "outcome of ${game.label}")
    }
    return transitions
}
