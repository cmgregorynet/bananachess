package com.charlesbarton.bananachess.home

import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.charlesbarton.bananachess.AppContainer
import com.charlesbarton.bananachess.BananaChessApp
import com.charlesbarton.bananachess.Route
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.match.BlendEffectPolicy
import com.charlesbarton.bananachess.match.CpuOpponent
import com.charlesbarton.bananachess.match.FileMatchSnapshotStore
import com.charlesbarton.bananachess.match.InMemoryMatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchSnapshot
import com.charlesbarton.bananachess.match.MatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.match.MatchTags
import com.charlesbarton.bananachess.match.MatchViewModel
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val introductionSeen = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true))
    private val scope = TestScope()

    private val passingCpu: (CpuDifficulty) -> CpuOpponent = { CpuOpponent { _, _ -> null } }

    /** A match on [testScheduler]'s clock that saves to [snapshots]. */
    private fun TestScope.match(
        state: GameState,
        mode: MatchMode,
        snapshots: MatchSnapshotStore,
        cpuOpponent: (CpuDifficulty) -> CpuOpponent = passingCpu,
    ): MatchViewModel =
        MatchViewModel(
            mode = mode,
            initialState = state,
            settings = introductionSeen,
            blendEffects = BlendEffectPolicy.Never,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            cpuDispatcher = StandardTestDispatcher(testScheduler),
            cpuOpponent = cpuOpponent,
            snapshots = snapshots,
        ).also { testScheduler.runCurrent() }

    // On the test's clock, outside backgroundScope, whose work advanceUntilIdle leaves alone.
    private fun TestScope.fileStore(directory: File) =
        FileMatchSnapshotStore(directory, CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun TestScope.playFirstLegalAction(viewModel: MatchViewModel) {
        val state = viewModel.state.value.authoritativeState
        viewModel.perform(RulesEngine.legalActions(state).first())
        testScheduler.advanceUntilIdle()
    }

    private val MatchViewModel.position: GameState get() = state.value.authoritativeState

    // region Saving from the match

    @Test
    fun `a match interrupted after three moves resumes with the same pieces, scores, turn and player to move`() =
        runTest {
            val directory = folder.newFolder()
            val mode = MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.One)
            val before = match(GameSetup.newGame(), mode, fileStore(directory))
            repeat(3) { playFirstLegalAction(before) }
            val left = before.position
            assertEquals(3, left.history.size)

            // The app is closed, and opened again on a new store over the same files.
            val saved = assertNotNull(fileStore(directory).load())
            val after = match(saved.state, saved.mode, InMemoryMatchSnapshotStore())

            assertEquals(mode, after.state.value.mode)
            assertEquals(left.pieces, after.position.pieces)
            assertEquals(left.scores, after.position.scores)
            assertEquals(left.turnNumber, after.position.turnNumber)
            assertEquals(left.activePlayer, after.position.activePlayer)
            assertEquals(left, after.position)
            assertEquals(left, after.state.value.displayState)
        }

    @Test
    fun `opening a match replaces whatever was saved`() =
        runTest {
            val snapshots = InMemoryMatchSnapshotStore()
            val first = match(GameSetup.newGame(), MatchMode.LocalVersus, snapshots)
            playFirstLegalAction(first)

            val fresh = GameSetup.newGame()
            match(fresh, MatchMode.Computer(CpuDifficulty.Easy, PlayerId.One), snapshots)

            assertEquals(MatchSnapshot(MatchMode.Computer(CpuDifficulty.Easy, PlayerId.One), fresh), snapshots.load())
        }

    @Test
    fun `every move is saved as soon as it is decided, before it has played out`() =
        runTest {
            val snapshots = InMemoryMatchSnapshotStore()
            val viewModel = match(GameSetup.newGame(), MatchMode.LocalVersus, snapshots)
            val state = viewModel.position

            viewModel.perform(RulesEngine.legalActions(state).first())

            assertTrue(viewModel.state.value.isResolving)
            assertEquals(viewModel.position, snapshots.load()?.state)
        }

    @Test
    fun `restart and undo save the position they go back to`() =
        runTest {
            val snapshots = InMemoryMatchSnapshotStore()
            val viewModel = match(GameSetup.newGame(), MatchMode.LocalVersus, snapshots)
            playFirstLegalAction(viewModel)
            val afterOne = viewModel.position
            playFirstLegalAction(viewModel)

            viewModel.undo()
            assertEquals(afterOne, snapshots.load()?.state)

            viewModel.restart()
            assertEquals(
                0,
                snapshots
                    .load()
                    ?.state
                    ?.history
                    ?.size,
            )
            assertEquals(viewModel.position, snapshots.load()?.state)
        }

    @Test
    fun `a finished match leaves nothing to resume`() =
        runTest {
            val directory = folder.newFolder()
            val snapshots = fileStore(directory)
            val viewModel = match(GameSetup.newGame(), MatchMode.LocalVersus, snapshots)
            var moves = 0
            while (!viewModel.position.status.isOver) {
                playFirstLegalAction(viewModel)
                moves += 1
                assertTrue(moves < 2_000, "the match did not end")
            }

            assertNull(snapshots.load())
            assertFalse(File(directory, FileMatchSnapshotStore.FILE_NAME).exists())
        }

    @Test
    fun `a resumed computer match whose turn it is starts the computer thinking`() =
        runTest {
            val opening = GameSetup.newGame()
            val cpuToMove = RulesEngine.apply(RulesEngine.legalActions(opening).first(), opening).state
            assertEquals(PlayerId.Two, cpuToMove.activePlayer)
            val snapshots =
                InMemoryMatchSnapshotStore(
                    MatchSnapshot(MatchMode.Computer(CpuDifficulty.Easy, PlayerId.One), cpuToMove),
                )
            val saved = assertNotNull(snapshots.load())
            val firstLegal: (
                CpuDifficulty,
            ) -> CpuOpponent = { CpuOpponent { state, _ -> RulesEngine.legalActions(state).first() } }

            val resumed = match(saved.state, saved.mode, snapshots, cpuOpponent = firstLegal)
            assertTrue(resumed.state.value.isOpponentThinking)

            testScheduler.advanceUntilIdle()
            assertEquals(PlayerId.One, resumed.position.activePlayer)
            assertEquals(
                2,
                snapshots
                    .load()
                    ?.state
                    ?.history
                    ?.size,
            )
        }

    // endregion

    // region Home

    private fun TestScope.home(snapshots: MatchSnapshotStore): HomeViewModel {
        val viewModel =
            HomeViewModel(
                introductionSeen,
                CoroutineScope(StandardTestDispatcher(testScheduler)),
                snapshots = snapshots,
            )
        viewModel.refreshResume()
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `home offers a resume only when there is a match saved, and says who it is against`() =
        runTest {
            val state = GameSetup.newGame()
            assertNull(home(InMemoryMatchSnapshotStore()).state.value.resume)
            assertNull(home(MatchSnapshotStore.None).state.value.resume)

            val details =
                mapOf(
                    MatchMode.LocalVersus to "Local two player",
                    MatchMode.Computer(CpuDifficulty.Brutal, PlayerId.Two) to "Brutal CPU",
                    MatchMode.ByLink("abc", PlayerId.One) to "By link",
                )
            for ((mode, detail) in details) {
                assertEquals(
                    detail,
                    home(InMemoryMatchSnapshotStore(MatchSnapshot(mode, state)))
                        .state.value.resume
                        ?.detail,
                )
            }
        }

    @Test
    fun `home looks again each time it is shown`() =
        runTest {
            val snapshots = InMemoryMatchSnapshotStore()
            val viewModel = home(snapshots)
            assertNull(viewModel.state.value.resume)

            snapshots.save(MatchSnapshot(MatchMode.LocalVersus, GameSetup.newGame()))
            viewModel.refreshResume()
            testScheduler.advanceUntilIdle()
            assertEquals(
                "Local two player",
                viewModel.state.value.resume
                    ?.detail,
            )

            snapshots.clear()
            viewModel.refreshResume()
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.state.value.resume)
        }

    @Test
    fun `a corrupt file or an unwritable directory only means no resume`() =
        runTest {
            val corruptDirectory = folder.newFolder()
            File(corruptDirectory, FileMatchSnapshotStore.FILE_NAME).writeText("{\"rulesVersion\":")
            assertNull(home(fileStore(corruptDirectory)).state.value.resume)
            assertFalse(File(corruptDirectory, FileMatchSnapshotStore.FILE_NAME).exists())

            val inTheWay = folder.newFile()
            val unwritable = fileStore(inTheWay)
            match(GameSetup.newGame(), MatchMode.LocalVersus, unwritable).also { playFirstLegalAction(it) }
            assertNull(home(unwritable).state.value.resume)
        }

    // endregion

    // region The app

    // As in MatchScreenTest: bumping [process] throws away everything but the saved back stack
    // and [snapshots], which stands for the file on disk.
    private var process by mutableIntStateOf(0)
    private var savedBackStack: Bundle? = null
    private var appNavController: NavHostController? = null
    private val snapshots = InMemoryMatchSnapshotStore()
    private val created = mutableListOf<MatchViewModel>()

    private val navController: NavHostController get() = checkNotNull(appNavController)

    private fun showApp() {
        composeRule.setContent {
            key(process) {
                val owner =
                    remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore = ViewModelStore()
                        }
                    }
                val container = remember { AppContainer(introductionSeen, snapshots = snapshots) }
                DisposableEffect(owner, container) {
                    onDispose {
                        owner.viewModelStore.clear()
                        container.close()
                    }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    val fresh = rememberNavController()
                    val navController =
                        remember(fresh) {
                            fresh.restoreState(savedBackStack)
                            fresh.also { appNavController = it }
                        }
                    PaperTheme {
                        BananaChessApp(
                            container = container,
                            navController = navController,
                            createMatch = { start, _ ->
                                scope.match(start.state, start.mode, container.snapshots).also { created += it }
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun restartTheProcess() {
        composeRule.runOnUiThread {
            savedBackStack =
                navController.saveState()?.let { bundle ->
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeBundle(bundle)
                        parcel.setDataPosition(0)
                        checkNotNull(parcel.readBundle(javaClass.classLoader))
                    } finally {
                        parcel.recycle()
                    }
                }
            process += 1
        }
        composeRule.waitForIdle()
    }

    private fun board(): List<String> =
        (0 until 6).flatMap { row ->
            (0 until 6).map { column ->
                composeRule
                    .onNodeWithTag("square_${row}_$column")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
            }
        }

    private fun turnIndicator(): String =
        composeRule
            .onNodeWithTag(MatchTags.TURN_INDICATOR)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()

    private fun playThreeMoves(viewModel: MatchViewModel) {
        repeat(3) { scope.playFirstLegalAction(viewModel) }
        composeRule.waitForIdle()
    }

    @Test
    fun `home shows resume only once a match is under way, and it opens the match where it was left`() {
        showApp()
        composeRule.onNodeWithText("Resume match").assertDoesNotExist()

        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()
        val left = created.single()
        playThreeMoves(left)
        val boardLeft = board()
        val turnLeft = turnIndicator()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Resume match").assertIsDisplayed()
        composeRule.onNodeWithText("Local two player").assertIsDisplayed()
        composeRule.onNodeWithText("Resume match").performClick()
        composeRule.waitForIdle()

        val resumed = created.last()
        assertEquals(2, created.size)
        assertEquals(left.position, resumed.position)
        assertEquals(boardLeft, board())
        assertEquals(turnLeft, turnIndicator())
        assertTrue(navController.currentDestination?.hasRoute<Route.Match>() == true)
    }

    @Test
    fun `a finished match takes the resume away`() {
        showApp()
        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()
        val viewModel = created.single()
        var moves = 0
        while (!viewModel.position.status.isOver) {
            scope.playFirstLegalAction(viewModel)
            moves += 1
            assertTrue(moves < 2_000, "the match did not end")
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
        composeRule.onNodeWithText("Resume match").assertDoesNotExist()
    }

    @Test
    fun `after the process is restarted under a match that was played on, it comes back where it was`() {
        showApp()
        composeRule.onNodeWithText("Play Computer").performClick()
        composeRule.onNodeWithText("Start match").performClick()
        composeRule.waitForIdle()
        val before = created.single()
        // The computer passes, so play both sides from here.
        playThreeMoves(before)
        val boardBefore = board()

        restartTheProcess()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        val after = created.last()
        assertEquals(2, created.size)
        assertEquals(before.state.value.mode, after.state.value.mode)
        assertEquals(before.position, after.position)
        assertEquals(3, after.position.history.size)
        assertEquals(boardBefore, board())
    }

    @Test
    fun `after the process is restarted under a resumed match, it is resumed again`() {
        snapshots.save(MatchSnapshot(MatchMode.LocalVersus, GameSetup.newGame()))
        showApp()
        composeRule.onNodeWithText("Resume match").performClick()
        composeRule.waitForIdle()
        playThreeMoves(created.single())

        restartTheProcess()

        assertEquals(
            3,
            created
                .last()
                .position.history.size,
        )
    }

    @Test
    fun `starting a new match from home replaces the one saved`() {
        val old = RulesEngine.apply(RulesEngine.legalActions(GameSetup.newGame()).first(), GameSetup.newGame()).state
        snapshots.save(MatchSnapshot(MatchMode.LocalVersus, old))
        showApp()
        composeRule.onNodeWithText("Resume match").assertIsDisplayed()

        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()

        assertEquals(
            0,
            created
                .single()
                .position.history.size,
        )
        assertEquals(created.single().position, snapshots.snapshot?.state)
    }

    // endregion
}
