package com.charlesbarton.bananachess.match

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charlesbarton.bananachess.AppContainer
import com.charlesbarton.bananachess.AppDispatchers
import com.charlesbarton.bananachess.BananaChessApp
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matches against the computer. Most run on virtual time with a stand-in computer that answers
 * at once; the ones about threads give it a real thread of its own, and one plays the real
 * engine from Home.
 */
@RunWith(RobolectricTestRunner::class)
class CpuPlayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val cpuThread = Executors.newSingleThreadExecutor()
    private val cpuDispatcher: CoroutineDispatcher = cpuThread.asCoroutineDispatcher()
    private val viewModels = ViewModelStore()

    @After
    fun tearDown() {
        viewModels.clear()
        cpuThread.shutdownNow()
    }

    /** Plays the first legal action of whatever position it is shown, straight away. */
    private val firstLegal = CpuOpponent { state, _ -> RulesEngine.legalActions(state).first() }

    private fun TestScope.cpuMatch(
        humanPlays: PlayerId,
        cpu: CpuOpponent = firstLegal,
        difficulty: CpuDifficulty = CpuDifficulty.Easy,
    ): MatchViewModel = matchViewModel(mode = MatchMode.Computer(difficulty, humanPlays), cpuOpponent = { cpu })

    /** A match whose computer thinks on a real thread, while the board stays on the test's clock. */
    private fun TestScope.cpuMatchOnAThread(
        humanPlays: PlayerId,
        cpu: CpuOpponent,
    ): MatchViewModel {
        val viewModel =
            ViewModelProvider.create(
                viewModels,
                viewModelFactory {
                    initializer {
                        MatchViewModel(
                            mode = MatchMode.Computer(CpuDifficulty.Brutal, humanPlays),
                            initialState = GameSetup.newGame(),
                            settings = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true)),
                            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                            cpuDispatcher = cpuDispatcher,
                            cpuOpponent = { cpu },
                        )
                    }
                },
            )[MatchViewModel::class]
        testScheduler.runCurrent()
        return viewModel
    }

    /** Runs the board's clock until [done], for as long as a real thread may need to answer. */
    private fun TestScope.settleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out waiting for the computer" }
            testScheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    private fun MatchViewModel.playFirstLegalMove() {
        val move = quietMove(ui.authoritativeState)
        tap(move.origin)
        tap(move.destination)
    }

    /** A move to an empty square, which the board takes on the second tap without asking anything. */
    private fun quietMove(state: GameState): Move =
        RulesEngine
            .legalActions(state)
            .filterIsInstance<Move>()
            .first { state.pieceAt(it.destination) == null }

    private val MatchUiState.moves: Int get() = authoritativeState.history.size

    // Taking a turn

    @Test
    fun `the computer opens when the human plays second, and the board is turned for them`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two)

            assertTrue(viewModel.ui.isOpponentThinking)
            assertTrue(viewModel.ui.isRotated)
            assertEquals("Easy CPU is thinking…", viewModel.ui.turnIndicatorText)
            settle()

            assertEquals(1, viewModel.ui.moves)
            assertEquals(
                RulesEngine.legalActions(GameSetup.newGame()).first(),
                viewModel.ui.authoritativeState.history
                    .single(),
            )
            assertFalse(viewModel.ui.isOpponentThinking)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
        }

    @Test
    fun `the computer waits for the human when the human plays first`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.One)
            settle()

            assertFalse(viewModel.ui.isOpponentThinking)
            assertEquals(0, viewModel.ui.moves)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
            assertEquals("Easy CPU", viewModel.ui.scoreboard(PlayerId.Two).name)
            assertEquals("You", viewModel.ui.scoreboard(PlayerId.One).name)
        }

    @Test
    fun `an instant answer is still not applied before 320 ms`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two)

            advance(319)
            assertTrue(viewModel.ui.isOpponentThinking)
            assertEquals(0, viewModel.ui.moves)

            advance(1)
            assertFalse(viewModel.ui.isOpponentThinking)
            assertEquals(1, viewModel.ui.moves)
            assertTrue(viewModel.ui.isResolving, "the reply should play out like any other move")
        }

    @Test
    fun `the computer answers the human's move once it has played out`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.One, difficulty = CpuDifficulty.Hard)
            viewModel.playFirstLegalMove()
            assertTrue(viewModel.ui.isResolving)
            assertFalse(viewModel.ui.isOpponentThinking, "not while the human's move is still on the board")

            settleUntilThinking(viewModel)
            assertEquals("Hard CPU is thinking…", viewModel.ui.turnIndicatorText)
            settle()

            assertEquals(2, viewModel.ui.moves)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    private fun TestScope.settleUntilThinking(viewModel: MatchViewModel) {
        while (!viewModel.ui.isOpponentThinking) {
            check(testScheduler.currentTime < 60_000) { "the computer never started thinking" }
            advance(10)
        }
    }

    @Test
    fun `input is locked while the computer thinks`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two)
            val before = viewModel.ui
            assertTrue(before.isInputLocked)

            // The human's own banana, and a square it could otherwise move to.
            viewModel.tap(square(1, 0))
            viewModel.tap(square(2, 0))

            assertEquals(before, viewModel.ui)
            assertNull(viewModel.ui.selectedPieceId)
        }

    @Test
    fun `a computer that finds nothing to play leaves the turn alone`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two, cpu = { _, _ -> null })
            settle()

            assertFalse(viewModel.ui.isOpponentThinking)
            assertEquals(0, viewModel.ui.moves)
            assertNull(viewModel.ui.errorMessage)
        }

    // Leaving and coming back

    @Test
    fun `an answer that arrives while the player is away waits for them`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two)
            val opening = viewModel.ui.displayState
            viewModel.onSceneInactive()

            settle()
            // Decided, but not shown: the board still has the position the player left.
            assertEquals(1, viewModel.ui.moves)
            assertFalse(viewModel.ui.isOpponentThinking)
            assertEquals(opening, viewModel.ui.displayState)
            assertTrue(viewModel.ui.isInputLocked)

            viewModel.onSceneActive()
            settle()
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    // Restart, undo and stale answers

    @Test
    fun `restarting cancels a pending answer`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.One)
            viewModel.playFirstLegalMove()
            settleUntilThinking(viewModel)

            viewModel.restart()
            assertFalse(viewModel.ui.isOpponentThinking)
            settle()

            assertEquals(0, viewModel.ui.moves)
            assertEquals(GameSetup.newGame().pieces, viewModel.ui.authoritativeState.pieces)
            assertNull(viewModel.ui.errorMessage)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `playing again against a computer that opens lets it open again, once`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.Two)
            advance(100)

            assertTrue(viewModel.ui.canPlayAgain)
            viewModel.restart()
            assertTrue(viewModel.ui.isOpponentThinking)
            settle()

            assertEquals(1, viewModel.ui.moves)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `undo while the computer thinks never applies its old answer`() =
        runTest {
            val viewModel = cpuMatch(humanPlays = PlayerId.One)
            viewModel.playFirstLegalMove()
            settleUntilThinking(viewModel)

            viewModel.undo()
            assertFalse(viewModel.ui.isOpponentThinking)
            settle()

            assertEquals(0, viewModel.ui.moves)
            assertNull(viewModel.ui.errorMessage)
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `an answer to a position that has since changed is dropped`() =
        runTest {
            var searched: GameState? = null
            val cpu =
                CpuOpponent { state, _ ->
                    searched = state
                    RulesEngine.legalActions(state).first()
                }
            val viewModel = cpuMatch(humanPlays = PlayerId.One, cpu = cpu)
            viewModel.playFirstLegalMove()
            settleUntilThinking(viewModel)
            advance(10)
            val answered = checkNotNull(searched)

            // A move arrives from elsewhere while the computer's answer is still on its way.
            val elsewhere: GameAction = RulesEngine.legalActions(answered).last()
            viewModel.perform(elsewhere)
            settle()

            assertEquals(2, viewModel.ui.moves)
            assertEquals(
                elsewhere,
                viewModel.ui.authoritativeState.history
                    .last(),
            )
            assertNull(viewModel.ui.errorMessage)
            assertFalse(viewModel.ui.isOpponentThinking)
        }

    // Threads

    @Test
    fun `a computer that blocks for two seconds does not block the board`() =
        runTest {
            val cpu =
                CpuOpponent { state, _ ->
                    Thread.sleep(2_000)
                    RulesEngine.legalActions(state).first()
                }
            val started = System.nanoTime()
            val viewModel = cpuMatchOnAThread(humanPlays = PlayerId.Two, cpu = cpu)

            // The board's thread is free: it keeps running, and shows the computer thinking.
            advance(1_000)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "the board waited for the search")
            assertTrue(viewModel.ui.isOpponentThinking)
            assertEquals("Brutal CPU is thinking…", viewModel.ui.turnIndicatorText)
            viewModel.tap(square(1, 0))
            assertNull(viewModel.ui.selectedPieceId)

            settleUntil { viewModel.ui.moves == 1 && !viewModel.ui.isResolving }
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(2))
            assertEquals("You to move", viewModel.ui.turnIndicatorText)
        }

    /** A search that runs until it is told to stop, and says when it was. */
    private class StoppableSearch : CpuOpponent {
        val stopped = CountDownLatch(1)

        override fun selectAction(
            state: GameState,
            shouldStop: () -> Boolean,
        ): GameAction? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!shouldStop()) {
                if (System.nanoTime() > deadline) return null
                Thread.sleep(1)
            }
            stopped.countDown()
            return RulesEngine.legalActions(state).first()
        }
    }

    @Test
    fun `restarting stops the search, not just the wait for it`() =
        runTest {
            val search = StoppableSearch()
            val viewModel = cpuMatchOnAThread(humanPlays = PlayerId.Two, cpu = search)
            assertTrue(viewModel.ui.isOpponentThinking)

            viewModel.restart()

            assertTrue(search.stopped.await(5, TimeUnit.SECONDS), "the first search was never told to stop")
            // Its answer comes back to a cancelled job, and is never applied.
            Thread.sleep(50)
            testScheduler.advanceUntilIdle()
            assertEquals(0, viewModel.ui.moves, "the stopped search's answer was applied")
            assertTrue(viewModel.ui.isOpponentThinking, "the fresh board's computer should be thinking")
        }

    @Test
    fun `clearing the view model stops the search`() =
        runTest {
            val search = StoppableSearch()
            val viewModel = cpuMatchOnAThread(humanPlays = PlayerId.Two, cpu = search)
            assertTrue(viewModel.ui.isOpponentThinking)

            viewModels.clear()

            assertTrue(search.stopped.await(5, TimeUnit.SECONDS), "the search outlived its match")
            testScheduler.advanceUntilIdle()
            assertEquals(0, viewModel.ui.moves)
        }

    // The app

    @Test
    fun `home starts an Easy match against the real computer, which answers the human's move`() {
        val scope = TestScope()
        val main = StandardTestDispatcher(scope.testScheduler)
        val container =
            AppContainer(
                settings = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true, cpuHumanPlaysFirst = true)),
                // The app's own construction of the match, with the board and the search on the test's clock.
                dispatchers = AppDispatchers(main = main, default = main, io = main),
            )
        var created: MatchViewModel? = null
        composeRule.setContent {
            PaperTheme {
                BananaChessApp(
                    container = container,
                    createMatch = { start, reduceMotion ->
                        com.charlesbarton.bananachess
                            .newMatchViewModel(container, start, reduceMotion)
                            .also { created = it }
                    },
                )
            }
        }
        composeRule.onNodeWithText("Play Computer").performClick()
        composeRule.onNodeWithText("Easy").performClick()
        composeRule.onNodeWithText("Start match").performClick()
        composeRule.waitForIdle()
        scope.testScheduler.runCurrent()
        val viewModel = checkNotNull(created)
        assertEquals(MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.One), viewModel.ui.mode)
        turnIndicatorShows("You to move")

        val move = quietMove(viewModel.ui.authoritativeState)
        composeRule.onNodeWithTag("square_${move.origin.row}_${move.origin.column}").performClick()
        composeRule.onNodeWithTag("square_${move.destination.row}_${move.destination.column}").performClick()
        while (!viewModel.ui.isOpponentThinking) {
            check(scope.testScheduler.currentTime < 60_000) { "the computer never started thinking" }
            scope.testScheduler.advanceTimeBy(10)
            scope.testScheduler.runCurrent()
        }
        composeRule.waitForIdle()
        turnIndicatorShows("Easy CPU is thinking…")
        composeRule.onNodeWithTag(MatchTags.THINKING, useUnmergedTree = true).assertExists()

        scope.testScheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        assertEquals(2, viewModel.ui.moves)
        turnIndicatorShows("You to move")
        composeRule.onNodeWithTag(MatchTags.THINKING, useUnmergedTree = true).assertDoesNotExist()
        container.close()
    }

    private fun turnIndicatorShows(text: String) =
        composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assert(hasText(text, substring = true))
}
