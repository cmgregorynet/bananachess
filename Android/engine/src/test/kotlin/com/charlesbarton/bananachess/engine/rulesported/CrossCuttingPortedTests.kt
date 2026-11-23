package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.cpu.CpuPlayer
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.online.MatchCodec
import com.charlesbarton.bananachess.engine.online.MatchOptions
import com.charlesbarton.bananachess.engine.online.MatchPayload
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.rules.applying
import com.charlesbarton.bananachess.engine.setup.GameSetup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tests of `SetupAndMovementTests.swift` and `EndStateAndDeterminismTests.swift`
 * that need the CPU or the payload codec as well as the rules, and so could not be
 * ported alongside the rest of those two files. Each nested class is the Swift suite
 * the test comes from and each test keeps its Swift function name.
 *
 * Swift bounds the CPU-driven tests with a time limit. Nothing here reads a clock: every
 * CPU is seeded, and the one that searches is given a clock that never expires, so each
 * match is the same on every run and is bounded by its turn limit alone.
 */
class CrossCuttingPortedTests {
    @Nested
    @DisplayName("Board setups")
    inner class BoardSetupTests {
        @Test
        @DisplayName("A random board plays: it is legal, scored, and finishes")
        fun randomBoardsPlayThrough() {
            for (seed in 1uL..12uL) {
                val start = GameSetup.newGame(ruleSet = RuleSet.Wildcard, boardSetup = BoardSetup.Random, seed = seed)
                val state =
                    playEasyMatch(start, seed, turnNumberLimit = 800) { current ->
                        val squares = current.pieces.mapNotNull { it.position }
                        assertEquals(squares.size, squares.toSet().size, "pieces overlapped on seed $seed")
                    }
                assertTrue(state.status.isOver, "seed $seed never finished")
                assertEquals(BoardSetup.Random, state.boardSetup, "and it still knows what it was dealt")
            }
        }

        @Test
        @DisplayName("The setup survives a round trip through a payload")
        fun setupSurvivesEncoding() {
            val payload =
                MatchPayload.newMatch(
                    id = "m",
                    options = MatchOptions(ruleSet = RuleSet.Wildcard, boardSetup = BoardSetup.Blendy),
                )
            val decoded = MatchCodec.decode(MatchCodec.encodeToBytes(payload))

            assertEquals(BoardSetup.Blendy, decoded.state.boardSetup)
            assertEquals(payload.state.pieces, decoded.state.pieces)
        }
    }

    @Nested
    @DisplayName("Board edges")
    inner class BoardWrapTests {
        @Test
        @DisplayName("The edges survive a round trip through a payload")
        fun wrapSurvivesEncoding() {
            val payload =
                MatchPayload.newMatch(
                    id = "m",
                    options = MatchOptions(boardSetup = BoardSetup.Random, boardWrap = BoardWrap.All),
                )
            val decoded = MatchCodec.decode(MatchCodec.encodeToBytes(payload))

            assertEquals(BoardWrap.All, decoded.state.boardWrap)
            assertEquals(BoardSetup.Random, decoded.state.boardSetup)
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(BoardWrap::class)
        @DisplayName("Matches on joined edges still play out")
        fun wrappedMatchesTerminate(wrap: BoardWrap) {
            for (seed in 1uL..6uL) {
                val start = GameSetup.newGame(ruleSet = RuleSet.Wildcard, boardWrap = wrap, seed = seed)
                val state =
                    playEasyMatch(start, seed, turnNumberLimit = 800) { current ->
                        val squares = current.pieces.mapNotNull { it.position }
                        assertEquals(squares.size, squares.toSet().size, "pieces overlapped under $wrap")
                        assertTrue(squares.all(Board::contains), "a piece left the board under $wrap")
                    }
                assertTrue(state.status.isOver, "$wrap seed $seed never finished")
            }
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class DeterminismTests {
        @Test
        @DisplayName("A full state survives an encode/decode round trip")
        fun codableRoundTrip() {
            var state = GameSetup.newGame()
            for (move in RulesEngine.legalMoves(state).take(1)) {
                state = RulesEngine.apply(move, state).state
            }

            val payload = MatchPayload(matchId = "m1", moveNumber = 1, state = state)
            val decoded = MatchCodec.decode(MatchCodec.encodeToBytes(payload))

            assertEquals(payload, decoded)
            assertEquals(state.pieces, decoded.state.pieces)
            assertEquals(state.positionKey, decoded.state.positionKey)
        }

        @Test
        @DisplayName("Encoding is byte-stable, so payload comparison means something")
        fun encodingIsStable() {
            val payload = MatchPayload.newMatch(id = "m1")
            assertContentEquals(MatchCodec.encodeToBytes(payload), MatchCodec.encodeToBytes(payload))
        }

        @Test
        @DisplayName("Event replay holds for a whole match, not just one clever fixture")
        fun eventReplayHoldsAcrossAMatch() {
            var state = GameSetup.newGame()
            val cpu = CpuPlayer(CpuDifficulty.Medium, seed = 31uL, clock = { 0L })
            var mismatches = 0

            while (!state.status.isOver && state.turnNumber < 400) {
                val action = cpu.selectAction(state) ?: break
                val result =
                    try {
                        RulesEngine.apply(action, state)
                    } catch (_: RulesError) {
                        break
                    }
                val replayed = result.events.fold(state) { replaying, event -> replaying.applying(event) }
                if (replayed.pieces != result.state.pieces || replayed.scores != result.state.scores) {
                    mismatches += 1
                }
                state = result.state
            }

            assertEquals(0, mismatches)
        }
    }

    @Nested
    @DisplayName("Repetition")
    inner class RepetitionTests {
        @Test
        @DisplayName("Matches finish on every board and rule set")
        fun nothingRunsForever() {
            for (setup in BoardSetup.entries) {
                for (wrap in BoardWrap.entries) {
                    for (seed in 1uL..3uL) {
                        val even = seed % 2uL == 0uL
                        val start =
                            GameSetup.newGame(
                                startingPlayer = if (even) PlayerId.One else PlayerId.Two,
                                ruleSet = RuleSet.Wildcard,
                                boardSetup = setup,
                                boardWrap = wrap,
                                blenderBattles = even,
                                seed = seed,
                            )
                        val state = playEasyMatch(start, seed, turnNumberLimit = 2_000)
                        assertTrue(
                            state.status.isOver,
                            "$setup/$wrap seed $seed was still going after ${state.turnNumber} turns",
                        )
                    }
                }
            }
        }
    }

    /**
     * The loop the Swift CPU-driven tests each spell out: Easy against Easy, seeded
     * `seed` and `seed * 7 + 3`, until the match ends, the turn number reaches
     * [turnNumberLimit], or a side has no action the engine accepts.
     */
    private fun playEasyMatch(
        start: GameState,
        seed: ULong,
        turnNumberLimit: Int,
        afterEachTurn: (GameState) -> Unit = {},
    ): GameState {
        val one = CpuPlayer(CpuDifficulty.Easy, seed = seed)
        val two = CpuPlayer(CpuDifficulty.Easy, seed = seed * 7uL + 3uL)
        var state = start
        while (!state.status.isOver && state.turnNumber < turnNumberLimit) {
            val cpu = if (state.activePlayer == PlayerId.One) one else two
            val action = cpu.selectAction(state) ?: break
            state =
                try {
                    RulesEngine.apply(action, state).state
                } catch (_: RulesError) {
                    break
                }
            afterEachTurn(state)
        }
        return state
    }
}
