package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.support.ConformanceFixture
import com.charlesbarton.bananachess.engine.support.FixtureGame
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Holds the Kotlin rules to the games the Swift engine recorded, move by move. */
class ConformanceReplayTest {
    /**
     * The fixture games these rules can already play: standard rules, no blender battles.
     * S06 widens this to every game by making [isInScope] true and [EXPECTED_GAMES] 108.
     */
    private fun isInScope(game: FixtureGame): Boolean = game.ruleSetValue == RuleSet.Standard && !game.blenderBattles

    private val games: List<FixtureGame> get() = ConformanceFixture.games.filter(::isInScope)

    @Test
    fun `the replay covers every game in scope`() {
        assertEquals(EXPECTED_GAMES, games.size)
        assertTrue(games.any { it.outcome != null }, "expected at least one finished game")
        assertTrue(games.any { it.outcome == null }, "expected at least one game stopped at the step cap")
        assertTrue(games.sumOf { it.steps.size } > 1_000, "expected full-length games")
    }

    @TestFactory
    fun `fixture games replay with zero differences`(): List<DynamicTest> =
        games.map { game -> dynamicTest(game.label) { replayFixtureGame(game) } }

    private companion object {
        const val EXPECTED_GAMES = 27
    }
}
