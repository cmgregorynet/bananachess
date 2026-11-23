package com.charlesbarton.bananachess.home

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchOptions
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class HomeViewModelTest {
    // A view model on the test's virtual time, already past its initial load.
    private fun TestScope.launched(store: SettingsStore): HomeViewModel {
        val viewModel = HomeViewModel(store, CoroutineScope(StandardTestDispatcher(testScheduler)))
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    private fun TestScope.change(
        store: SettingsStore,
        edit: HomeViewModel.() -> Unit,
    ): HomeUiState {
        launched(store).edit()
        testScheduler.advanceUntilIdle()
        return launched(store).state.value
    }

    @Test
    fun `a first launch shows the defaults`() =
        runTest {
            val state = launched(InMemorySettingsStore()).state.value

            assertEquals(CpuDifficulty.Medium, state.difficulty)
            assertEquals(true, state.humanPlaysFirst)
            assertEquals(false, state.usesWildcardRules)
            assertEquals(BoardSetup.Standard, state.boardSetup)
            assertEquals(BoardWrap.Flat, state.boardWrap)
            assertEquals(false, state.blenderBattles)
        }

    @Test
    fun `stored settings are shown on launch`() =
        runTest {
            val store =
                InMemorySettingsStore(
                    Settings(
                        cpuDifficulty = CpuDifficulty.Hard,
                        cpuHumanPlaysFirst = false,
                        wildcardRulesEnabled = true,
                        boardSetup = BoardSetup.Blendy,
                        boardWrap = BoardWrap.Sides,
                        blenderBattles = true,
                    ),
                )

            assertEquals(
                HomeUiState(
                    difficulty = CpuDifficulty.Hard,
                    humanPlaysFirst = false,
                    usesWildcardRules = true,
                    boardSetup = BoardSetup.Blendy,
                    boardWrap = BoardWrap.Sides,
                    blenderBattles = true,
                ),
                launched(store).state.value,
            )
        }

    @Test
    fun `a setter changes the screen at once`() =
        runTest {
            val viewModel = launched(InMemorySettingsStore())

            viewModel.setBoardSetup(BoardSetup.Random)

            assertEquals(BoardSetup.Random, viewModel.state.value.boardSetup)
        }

    @Test
    fun `difficulty persists to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setDifficulty(CpuDifficulty.Brutal) }

            assertEquals(HomeUiState(difficulty = CpuDifficulty.Brutal), state)
            assertEquals(Settings(cpuDifficulty = CpuDifficulty.Brutal), store.current)
        }

    @Test
    fun `who moves first persists to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setHumanPlaysFirst(false) }

            assertEquals(HomeUiState(humanPlaysFirst = false), state)
            assertEquals(Settings(cpuHumanPlaysFirst = false), store.current)
        }

    @Test
    fun `wildcard rules persist to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setUsesWildcardRules(true) }

            assertEquals(HomeUiState(usesWildcardRules = true), state)
            assertEquals(Settings(wildcardRulesEnabled = true), store.current)
        }

    @Test
    fun `the board setup persists to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setBoardSetup(BoardSetup.Blendy) }

            assertEquals(HomeUiState(boardSetup = BoardSetup.Blendy), state)
            assertEquals(Settings(boardSetup = BoardSetup.Blendy), store.current)
        }

    @Test
    fun `the board edges persist to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setBoardWrap(BoardWrap.All) }

            assertEquals(HomeUiState(boardWrap = BoardWrap.All), state)
            assertEquals(Settings(boardWrap = BoardWrap.All), store.current)
        }

    @Test
    fun `blender battles persist to a new view model`() =
        runTest {
            val store = InMemorySettingsStore()
            val state = change(store) { setBlenderBattles(true) }

            assertEquals(HomeUiState(blenderBattles = true), state)
            assertEquals(Settings(blenderBattles = true), store.current)
        }

    @Test
    fun `all six settings persist together`() =
        runTest {
            val store = InMemorySettingsStore()
            val state =
                change(store) {
                    setDifficulty(CpuDifficulty.Easy)
                    setHumanPlaysFirst(false)
                    setUsesWildcardRules(true)
                    setBoardSetup(BoardSetup.Random)
                    setBoardWrap(BoardWrap.Sides)
                    setBlenderBattles(true)
                }

            assertEquals(
                HomeUiState(
                    difficulty = CpuDifficulty.Easy,
                    humanPlaysFirst = false,
                    usesWildcardRules = true,
                    boardSetup = BoardSetup.Random,
                    boardWrap = BoardWrap.Sides,
                    blenderBattles = true,
                ),
                state,
            )
        }

    @Test
    fun `changing a game setting does not forget that the teaching overlay was seen`() =
        runTest {
            val store = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true))

            change(store) { setBlenderBattles(true) }

            assertEquals(Settings(blenderBattles = true, hasSeenTeachingOverlay = true), store.current)
        }

    @Test
    fun `a choice made before the stored settings arrive is kept and stored`() =
        runTest {
            val store = InMemorySettingsStore(Settings(boardSetup = BoardSetup.Blendy, blenderBattles = true))
            val viewModel = HomeViewModel(store, CoroutineScope(StandardTestDispatcher(testScheduler)))

            viewModel.setBoardSetup(BoardSetup.Random)
            testScheduler.advanceUntilIdle()

            assertEquals(BoardSetup.Random, viewModel.state.value.boardSetup)
            assertEquals(Settings(boardSetup = BoardSetup.Random, blenderBattles = true), store.current)
        }

    @Test
    fun `options reflect the settings`() =
        runTest {
            val viewModel = launched(InMemorySettingsStore())
            assertEquals(MatchOptions(), viewModel.options)
            assertEquals(
                MatchOptions(RuleSet.Standard, BoardSetup.Standard, BoardWrap.Flat, blenderBattles = false),
                viewModel.options,
            )

            viewModel.setUsesWildcardRules(true)
            viewModel.setBoardSetup(BoardSetup.Blendy)
            viewModel.setBoardWrap(BoardWrap.All)
            viewModel.setBlenderBattles(true)

            assertEquals(
                MatchOptions(RuleSet.Wildcard, BoardSetup.Blendy, BoardWrap.All, blenderBattles = true),
                viewModel.options,
            )
        }

    @Test
    fun `a local match is dealt from the options with player one to move`() =
        runTest {
            val viewModel = launched(InMemorySettingsStore())
            viewModel.setUsesWildcardRules(true)
            viewModel.setBoardSetup(BoardSetup.Blendy)
            viewModel.setBoardWrap(BoardWrap.Sides)
            viewModel.setBlenderBattles(true)

            val start = viewModel.startLocal()

            assertEquals(MatchMode.LocalVersus, start.mode)
            assertEquals(
                GameSetup.newGame(
                    startingPlayer = PlayerId.One,
                    ruleSet = RuleSet.Wildcard,
                    boardSetup = BoardSetup.Blendy,
                    boardWrap = BoardWrap.Sides,
                    blenderBattles = true,
                ),
                start.state,
            )
            assertEquals(PlayerId.One, start.state.activePlayer)
        }

    @Test
    fun `a default local match is the standard game`() =
        runTest {
            assertEquals(GameSetup.newGame(), launched(InMemorySettingsStore()).startLocal().state)
        }

    @Test
    fun `a computer match seats the human as player one when they move first`() =
        runTest {
            val viewModel = launched(InMemorySettingsStore())
            viewModel.setDifficulty(CpuDifficulty.Hard)

            val start = viewModel.startComputer()

            assertEquals(MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.One), start.mode)
            assertEquals(PlayerId.One, start.state.activePlayer)
        }

    @Test
    fun `a computer match seats the human as player two when the CPU moves first`() =
        runTest {
            val viewModel = launched(InMemorySettingsStore())
            viewModel.setHumanPlaysFirst(false)

            val start = viewModel.startComputer()

            assertEquals(MatchMode.Computer(CpuDifficulty.Medium, humanPlays = PlayerId.Two), start.mode)
            assertEquals(PlayerId.One, start.state.activePlayer)
        }
}
