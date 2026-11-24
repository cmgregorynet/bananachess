package com.charlesbarton.bananachess.match

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.charlesbarton.bananachess.AppContainer
import com.charlesbarton.bananachess.BananaChessApp
import com.charlesbarton.bananachess.Route
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.SmoothieProgress
import com.charlesbarton.bananachess.engine.model.WildcardStanding
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = MatchScreenTest.PHONE)
class MatchScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = TestScope()
    private val introductionSeen = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true))
    private var exits by mutableIntStateOf(0)
    private var rulesOpened by mutableIntStateOf(0)

    // region Showing a match

    private fun show(
        viewModel: MatchViewModel,
        settings: SettingsStore = introductionSeen,
        showsDebugControls: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PaperTheme {
                    MatchScreen(
                        viewModel = viewModel,
                        settings = settings,
                        onExit = { exits += 1 },
                        onShowRules = { rulesOpened += 1 },
                        showsDebugControls = showsDebugControls,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** A local match on screen, as "Local Two Player" starts it. */
    private fun startLocalMatch(
        ruleSet: RuleSet = RuleSet.Standard,
        boardSetup: BoardSetup = BoardSetup.Standard,
    ): MatchViewModel {
        val viewModel = scope.matchViewModel(GameSetup.newGame(ruleSet = ruleSet, boardSetup = boardSetup))
        show(viewModel)
        return viewModel
    }

    private fun finished(outcome: Outcome): GameState =
        GameState(pieces = GameSetup.newGame().pieces, status = GameStatus.Ended(outcome))

    private fun node(
        row: Int,
        column: Int,
    ) = composeRule.onNodeWithTag("square_${row}_$column")

    private fun tap(
        row: Int,
        column: Int,
    ) {
        node(row, column).performClick()
    }

    private fun tap(position: Position) = tap(position.row, position.column)

    private fun label(
        row: Int,
        column: Int,
    ): String =
        node(row, column)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    /** Lets a move play out on the board. */
    private fun settle() {
        scope.settle()
        composeRule.waitForIdle()
    }

    private fun turnIndicator() = composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR)

    private fun expectTurnIndicator(text: String) = turnIndicator().assert(hasText(text, substring = true))

    private fun inDialog(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun MatchViewModel.makeAnIllegalMove() {
        val strawberry = checkNotNull(ui.authoritativeState.pieceAt(square(4, 1)))
        perform(Move(strawberry.id, square(4, 1), square(0, 0)))
        composeRule.waitForIdle()
    }

    // endregion

    // region The smoke path

    @Test
    fun `select, deselect and move a fruit`() {
        startLocalMatch()
        // Row 4 is player one's opening fruit row; row 3 in front of it is empty.
        assertTrue("banana" in label(4, 0), "unexpected opening layout: ${label(4, 0)}")

        tap(4, 0)
        assertTrue("legal move" in label(3, 0), "selecting a fruit should mark the square in front of it")

        tap(4, 0)
        assertFalse("legal move" in label(3, 0), "tapping a selected piece should deselect it")

        tap(4, 0)
        tap(3, 0)
        settle()

        expectTurnIndicator("Player Two")
        assertTrue("banana" in label(3, 0), "the fruit should now be on its new square")
        assertTrue("Empty" in label(4, 0), "and gone from the old one")
    }

    @Test
    fun `a blender moves diagonally, and landing on its own fruit is offered with its price`() {
        startLocalMatch()
        // Player one's blender spawns are row 5, columns 1 and 4.
        assertTrue("blender" in label(5, 1), "unexpected opening layout: ${label(5, 1)}")

        tap(5, 1)
        // Both diagonals hold this player's own fruit: legal now, but priced.
        assertTrue("legal move" in label(4, 2), "a blender may land on its owner's fruit: ${label(4, 2)}")
        assertTrue("minus" in label(4, 2), "and TalkBack should say what that costs: ${label(4, 2)}")
        tap(5, 1)

        // Step the fruit out of the way, then move the blender onto the empty square.
        tap(4, 2)
        tap(3, 2)
        settle()
        expectTurnIndicator("Player Two")

        tap(1, 0)
        tap(2, 0)
        settle()
        expectTurnIndicator("Player One")

        tap(5, 1)
        tap(4, 2)
        settle()

        expectTurnIndicator("Player Two")
        assertTrue("blender" in label(4, 2))
        assertTrue("blender home" in label(5, 1), "a vacated spawn stays marked")
    }

    @Test
    fun `blending your own fruit asks first and then costs the points`() {
        startLocalMatch()

        tap(5, 1)
        tap(4, 0)

        inDialog("Blend your own banana?").assertIsDisplayed()
        inDialog("It leaves the board and costs you 1 point.").assertIsDisplayed()
        inDialog("Pick something else").assertIsDisplayed()
        inDialog("Blend it").performClick()
        settle()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        expectTurnIndicator("Player Two")
        composeRule.onNode(hasContentDescription("score -1", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Player One, score -1, 5 fruit left").assertIsDisplayed()
        assertTrue("blender" in label(4, 0), "the blender stays on the square")
    }

    @Test
    fun `backing out of a self-blend leaves the board alone`() {
        val viewModel = startLocalMatch()

        tap(5, 1)
        tap(4, 0)
        inDialog("Pick something else").performClick()
        settle()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        expectTurnIndicator("Player One")
        assertTrue("banana" in label(4, 0))
        assertTrue(
            viewModel.ui.authoritativeState.history
                .isEmpty(),
        )
    }

    @Test
    fun `the side that is waiting cannot select`() {
        startLocalMatch()
        tap(4, 0)
        tap(3, 0)
        settle()
        expectTurnIndicator("Player Two")

        tap(4, 1)

        assertFalse("legal move" in label(3, 1), "the waiting player's pieces must not respond")
    }

    @Test
    fun `a standard match shows no wildcard trackers`() {
        startLocalMatch()

        composeRule.onAllNodes(hasContentDescription("Banana Split", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasContentDescription("Smoothie", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a wildcard match shows a tracker for each player`() {
        startLocalMatch(ruleSet = RuleSet.Wildcard)

        // Both players' progress has to be visible, per the design brief.
        val trackers =
            composeRule.onAllNodes(
                hasContentDescription("Banana Split", substring = true) and
                    hasContentDescription("Smoothie", substring = true),
            )
        trackers.assertCountEquals(2)
        trackers[0].assert(hasContentDescription("Banana Split 0 of 3", substring = true))
        trackers[0].assert(hasContentDescription("Smoothie 0 of 3, Banana Split 0 of 3"))

        // Blenderama joins the tracker of the player who has started one.
        tap(5, 1)
        tap(4, 0)
        inDialog("Blend it").performClick()
        settle()

        trackers.assertCountEquals(2)
        composeRule.onAllNodes(hasContentDescription("Blenderama 1 of 6", substring = true)).assertCountEquals(1)
    }

    @Test
    fun `the self-blend dialog places the move against Blenderama when wildcards are on`() {
        startLocalMatch(ruleSet = RuleSet.Wildcard)

        tap(5, 1)
        tap(4, 0)

        inDialog("Blend it").assertIsDisplayed()
        composeRule
            .onNode(hasText("Blenderama", substring = true) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun `the standard self-blend dialog mentions only the cost`() {
        startLocalMatch()

        tap(5, 1)
        tap(4, 0)

        inDialog("Blend it").assertIsDisplayed()
        composeRule.onNode(hasText("Blenderama", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `blendy deals fruit on the back row and blenders in front`() {
        startLocalMatch(boardSetup = BoardSetup.Blendy)

        assertTrue("banana" in label(5, 0), "expected fruit on the back row, got: ${label(5, 0)}")
        assertTrue("blender" in label(4, 1), "expected a blender one row in, got: ${label(4, 1)}")
    }

    // endregion

    // region A whole match

    // The app as a process holds it. Bumping [process] throws all of it away, as Android does when
    // it reclaims a process in the background: the compositions, the view models and the
    // container go, and only the saved back stack (and what is stored on disk) survives.
    private var process by mutableIntStateOf(0)
    private var savedBackStack: Bundle? = null
    private var appNavController: NavHostController? = null

    private fun showApp(
        settings: SettingsStore,
        onMatchCreated: (MatchViewModel) -> Unit = {},
    ) {
        composeRule.setContent {
            key(process) {
                val owner =
                    remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore = ViewModelStore()
                        }
                    }
                val container = remember { AppContainer(settings) }
                DisposableEffect(owner, container) {
                    onDispose {
                        owner.viewModelStore.clear()
                        container.close()
                    }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    val fresh = rememberNavController()
                    // Restored before the graph is set, as the activity restores it after a restart.
                    val navController =
                        remember(fresh) {
                            fresh.restoreState(savedBackStack)
                            fresh.also { appNavController = it }
                        }
                    PaperTheme {
                        BananaChessApp(
                            container = container,
                            navController = navController,
                            // The app's own match, on the test's clock.
                            createMatch = { start, _ ->
                                scope.matchViewModel(start.state, start.mode).also(onMatchCreated)
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val navController: NavHostController get() = checkNotNull(appNavController)

    /** Kills the app's process under it and starts a new one on the back stack it saved. */
    private fun restartTheProcess() {
        composeRule.runOnUiThread {
            savedBackStack = navController.saveState()?.let(::throughAParcel)
            process += 1
        }
        composeRule.waitForIdle()
    }

    // The saved state really is written out and read back, as it is across a process restart.
    private fun throughAParcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    /** Takes the first legal action through the board, the way a player would. */
    private fun playFirstLegalAction(viewModel: MatchViewModel) {
        val before = viewModel.ui.authoritativeState
        when (val action = RulesEngine.legalActions(before).firstOrNull()) {
            null -> {
                fail("nothing to play on turn ${before.turnNumber}, and the match is not over")
            }

            is Move -> {
                tap(action.origin)
                tap(action.destination)
                if (viewModel.ui.pendingSelfBlend != null) inDialog("Blend it").performClick()
            }

            is RestoreBlender -> {
                tap(action.at)
            }
        }
        settle()
        assertEquals(
            before.history.size + 1,
            viewModel.ui.authoritativeState.history.size,
            "the board did not take the move",
        )
    }

    @Test
    fun `a local match is started from home, played to a result and played again`() {
        var created: MatchViewModel? = null
        showApp(introductionSeen, onMatchCreated = { created = it })

        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()
        val viewModel = assertNotNull(created, "the Match route did not make a match")
        assertEquals(MatchMode.LocalVersus, viewModel.ui.mode)
        expectTurnIndicator("Player One to move")
        composeRule.onNodeWithTag(MatchTags.RESULT).assertDoesNotExist()

        // The opening moves go through the board, the rest straight to the view model:
        // waiting for Compose to idle after every one of a whole match's moves made this
        // test take minutes, and time out, on a loaded machine.
        var moves = 0
        while (!viewModel.ui.authoritativeState.status.isOver) {
            if (moves < MOVES_THROUGH_THE_BOARD) {
                playFirstLegalAction(viewModel)
            } else {
                val action = RulesEngine.legalActions(viewModel.ui.authoritativeState).first()
                viewModel.perform(action)
                scope.settle()
            }
            moves += 1
            assertTrue(moves < MOVE_LIMIT, "the match did not end within $MOVE_LIMIT moves")
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MatchTags.RESULT).assertIsDisplayed()
        composeRule.onNodeWithText(checkNotNull(viewModel.ui.resultText)).assertIsDisplayed()
        expectTurnIndicator("Match over")

        composeRule.onNodeWithText("Play again").performClick()
        settle()

        composeRule.onNodeWithTag(MatchTags.RESULT).assertDoesNotExist()
        expectTurnIndicator("Player One to move")
        assertTrue("banana" in label(4, 0))
        assertTrue("Empty" in label(3, 0))
        composeRule.onNodeWithContentDescription("Player One, score 0, 6 fruit left, to move").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Player Two, score 0, 6 fruit left").assertIsDisplayed()
        assertTrue(
            viewModel.ui.authoritativeState.history
                .isEmpty(),
        )

        // The fresh board plays.
        tap(4, 0)
        tap(3, 0)
        settle()
        expectTurnIndicator("Player Two")
    }

    @Test
    fun `home starts the match on the board and rules it was asked for`() {
        val settings =
            InMemorySettingsStore(
                Settings(hasSeenTeachingOverlay = true, wildcardRulesEnabled = true, boardSetup = BoardSetup.Blendy),
            )
        showApp(settings)

        composeRule.onNodeWithText("Local Two Player").performClick()

        assertTrue("banana" in label(5, 0))
        composeRule.onAllNodes(hasContentDescription("Banana Split", substring = true)).assertCountEquals(2)
    }

    @Test
    fun `after the process is restarted under a match, the same match is dealt again`() {
        val settings =
            InMemorySettingsStore(
                Settings(
                    hasSeenTeachingOverlay = true,
                    wildcardRulesEnabled = true,
                    boardSetup = BoardSetup.Random,
                    boardWrap = BoardWrap.Sides,
                    blenderBattles = true,
                ),
            )
        val created = mutableListOf<MatchViewModel>()
        showApp(settings, onMatchCreated = { created += it })
        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()
        val before = created.single()
        val boardBefore = (0 until 6).flatMap { row -> (0 until 6).map { column -> label(row, column) } }

        restartTheProcess()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Local Two Player").assertDoesNotExist()
        assertTrue(navController.currentDestination?.hasRoute<Route.Match>() == true)
        val after = created.last()
        assertEquals(2, created.size, "the restarted process should deal the match once")
        assertNotSame(before, after)
        assertFalse(before.viewModelScope.isActive, "the old process's match should be gone")
        assertEquals(MatchMode.LocalVersus, after.ui.mode)
        // The same Random board, rules and edges: the seed travelled with the route.
        assertEquals(before.ui.authoritativeState, after.ui.authoritativeState)
        assertEquals(BoardSetup.Random, after.ui.authoritativeState.boardSetup)
        assertEquals(RuleSet.Wildcard, after.ui.authoritativeState.ruleSet)
        assertEquals(BoardWrap.Sides, after.ui.authoritativeState.boardWrap)
        assertTrue(after.ui.authoritativeState.blenderBattles)
        assertEquals(boardBefore, (0 until 6).flatMap { row -> (0 until 6).map { column -> label(row, column) } })

        // The back stack came back too.
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `after the process is restarted under a computer match, it comes back against the same CPU`() {
        val settings =
            InMemorySettingsStore(
                Settings(hasSeenTeachingOverlay = true, cpuDifficulty = CpuDifficulty.Hard, cpuHumanPlaysFirst = false),
            )
        val created = mutableListOf<MatchViewModel>()
        showApp(settings, onMatchCreated = { created += it })
        composeRule.onNodeWithText("Play Computer").performClick()
        composeRule.onNodeWithText("Start match").performClick()
        composeRule.waitForIdle()
        val before = created.single()

        restartTheProcess()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        val after = created.last()
        assertNotSame(before, after)
        assertEquals(MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.Two), after.ui.mode)
        assertEquals(before.ui.authoritativeState, after.ui.authoritativeState)
    }

    @Test
    fun `a saved match with nothing saved returns home`() {
        var created: MatchViewModel? = null
        showApp(introductionSeen, onMatchCreated = { created = it })

        composeRule.runOnUiThread { navController.navigate(Route.Match(MatchSource.Saved)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()
        assertNull(created)
        assertEquals(true, navController.currentDestination?.hasRoute<Route.Home>())
    }

    // endregion

    // region Result

    @Test
    fun `the result overlay says how the match ended, shows both scores and offers another`() {
        val viewModel = scope.matchViewModel(finished(Outcome.Win(PlayerId.One, WinReason.BananaSplit)))
        show(viewModel)

        composeRule.onNodeWithTag(MatchTags.RESULT).assertIsDisplayed()
        composeRule
            .onNodeWithText(checkNotNull(viewModel.ui.resultText))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("Every banana across. The score did not matter.").assertIsDisplayed()
        composeRule.onNode(hasText("Player One") and hasText("0")).assertIsDisplayed()
        composeRule.onNode(hasText("Player Two") and hasText("0")).assertIsDisplayed()
        composeRule.onNodeWithText("Play again").assertIsDisplayed()

        composeRule.onNodeWithText("Done").performClick()

        assertEquals(1, exits)
    }

    @Test
    fun `the board and the controls under the result take no taps`() {
        val viewModel = scope.matchViewModel(finished(Outcome.Draw))
        show(viewModel)
        composeRule.onNodeWithText("A draw — nothing left to blend.").assertIsDisplayed()

        composeRule.onNodeWithText("Restart").performClick()

        assertFalse(viewModel.ui.isShowingRestartConfirmation)
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun `a link match cannot be played again from the result`() {
        val mode = MatchMode.ByLink(matchId = "match", localPlayer = PlayerId.One)
        show(scope.matchViewModel(finished(Outcome.Win(PlayerId.Two)), mode = mode))

        composeRule.onNodeWithTag(MatchTags.RESULT).assertIsDisplayed()
        composeRule.onNodeWithText("Play again").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    // endregion

    // region Errors

    @Test
    fun `an error is shown under the turn indicator, leaves the board playable, and can be dismissed`() {
        val viewModel = startLocalMatch()
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertDoesNotExist()

        viewModel.makeAnIllegalMove()

        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText("That square is not a legal destination.").assertIsDisplayed()
        val banner = composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).fetchSemanticsNode().boundsInRoot
        assertTrue(banner.top >= turnIndicator().fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(banner.bottom <= node(0, 0).fetchSemanticsNode().boundsInRoot.top, "the banner covers the board")

        // It never blocks the board.
        tap(4, 0)
        tap(3, 0)
        settle()
        expectTurnIndicator("Player Two")
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertIsDisplayed()

        composeRule.onNodeWithText("Dismiss").performClick()

        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertDoesNotExist()
        assertNull(viewModel.ui.errorMessage)
    }

    @Test
    fun `an error is shown and can be dismissed while a move is resolving`() {
        val viewModel = startLocalMatch()
        tap(4, 0)
        tap(3, 0)
        scope.testScheduler.runCurrent()
        assertTrue(viewModel.ui.isResolving)

        viewModel.makeAnIllegalMove()

        assertTrue(viewModel.ui.isResolving)
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertIsDisplayed()

        composeRule.onNodeWithText("Dismiss").performClick()

        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertDoesNotExist()
        assertTrue(viewModel.ui.isResolving)
    }

    @Test
    fun `an error is shown and can be dismissed while the result is up`() {
        val viewModel = scope.matchViewModel(finished(Outcome.Draw))
        show(viewModel)

        viewModel.makeAnIllegalMove()

        composeRule.onNodeWithTag(MatchTags.RESULT).assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText("The match has already finished.").assertIsDisplayed()
        composeRule.onAllNodes(hasText("The match has already finished.")).assertCountEquals(1)

        composeRule.onNodeWithText("Dismiss").performClick()

        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertDoesNotExist()
        composeRule.onNodeWithTag(MatchTags.RESULT).assertIsDisplayed()
    }

    @Test
    fun `an error survives play again, because nobody has read it yet`() {
        val viewModel = scope.matchViewModel(finished(Outcome.Draw))
        show(viewModel)
        viewModel.makeAnIllegalMove()

        composeRule.onNodeWithText("Play again").performClick()
        settle()

        composeRule.onNodeWithTag(MatchTags.RESULT).assertDoesNotExist()
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertIsDisplayed()
    }

    // endregion

    // region Lifecycle

    @Test
    fun `leaving the screen holds a move part-way, and coming back plays the rest`() {
        val viewModel = startLocalMatch()
        tap(4, 0)
        tap(3, 0)
        // The fruit has moved; the turn has not yet passed.
        scope.testScheduler.runCurrent()
        composeRule.waitForIdle()
        assertTrue("banana" in label(3, 0))

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        settle()

        assertTrue(viewModel.ui.isResolving, "the move finished while nobody was looking")
        assertEquals(PlayerId.One, viewModel.ui.displayState.activePlayer)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()

        assertFalse(viewModel.ui.isResolving)
        expectTurnIndicator("Player Two")
    }

    // endregion

    // region The first-match introduction

    @Test
    fun `the first match opens under the introduction, which hides the match from TalkBack`() {
        val settings = InMemorySettingsStore()
        show(scope.matchViewModel(), settings = settings)

        composeRule.onNodeWithText("Move one piece").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
        node(4, 0).assertDoesNotExist()
        turnIndicator().assertDoesNotExist()
        composeRule.onNodeWithText("Restart").assertDoesNotExist()
        composeRule.onNodeWithText("Rules").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Player One", substring = true)).assertDoesNotExist()

        repeat(3) { composeRule.onNodeWithText("Next").performClick() }
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Play").assertDoesNotExist()
        assertTrue(settings.current.hasSeenTeachingOverlay)
        node(4, 0).assertIsDisplayed()
        turnIndicator().assertIsDisplayed()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
    }

    @Test
    fun `back closes the introduction, records it as seen, and stays in the match`() {
        val settings = InMemorySettingsStore()
        show(scope.matchViewModel(), settings = settings)
        composeRule.onNodeWithText("Move one piece").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("Move one piece").assertDoesNotExist()
        assertTrue(settings.current.hasSeenTeachingOverlay)
        assertEquals(0, exits)
        assertFalse(composeRule.activity.isFinishing)
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        node(4, 0).assertIsDisplayed()

        // And the board under it plays.
        tap(4, 0)
        tap(3, 0)
        settle()
        expectTurnIndicator("Player Two")
    }

    @Test
    fun `a returning player goes straight to the board`() {
        startLocalMatch()

        composeRule.onNodeWithText("Move one piece").assertDoesNotExist()
        node(4, 0).assertIsDisplayed()
    }

    // endregion

    // region Chrome

    @Test
    fun `the turn indicator is announced politely and the scoreboards read as sentences`() {
        startLocalMatch()

        turnIndicator()
            .assertTextEquals("Player One to move")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithContentDescription("Player One, score 0, 6 fruit left, to move").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Player Two, score 0, 6 fruit left").assertIsDisplayed()
        // The sentence replaces the pieces of the panel; it is not read alongside them.
        composeRule.onNodeWithText("· 12 pts on the board").assertDoesNotExist()
    }

    @Test
    fun `back leaves the match and rules opens the rules`() {
        startLocalMatch()

        composeRule.onNodeWithText("Rules").performClick()
        assertEquals(1, rulesOpened)
        assertEquals(0, exits)

        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `restart asks first, keeps the match on keep playing and clears it on restart`() {
        val viewModel = startLocalMatch()
        tap(4, 0)
        tap(3, 0)
        settle()

        composeRule.onNodeWithText("Restart").performClick()

        inDialog("Restart this match?").assertIsDisplayed()
        inDialog("The current board and both scores will be cleared.").assertIsDisplayed()
        inDialog("Keep playing").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertTrue("banana" in label(3, 0))

        composeRule.onNodeWithText("Restart").performClick()
        inDialog("Restart").performClick()
        settle()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        expectTurnIndicator("Player One to move")
        assertTrue("banana" in label(4, 0))
        assertTrue(
            viewModel.ui.authoritativeState.history
                .isEmpty(),
        )
    }

    @Test
    fun `a release build has no undo and no turn count`() {
        startLocalMatch()

        composeRule.onNodeWithText("Undo").assertDoesNotExist()
        composeRule.onNodeWithText("t1").assertDoesNotExist()
    }

    @Test
    fun `a debug build can undo a move and shows the turn count`() {
        show(scope.matchViewModel(), showsDebugControls = true)

        composeRule.onNodeWithText("Undo").assertIsNotEnabled()
        composeRule.onNodeWithText("t1").assertIsDisplayed()

        tap(4, 0)
        tap(3, 0)
        settle()
        composeRule.onNodeWithText("t2").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsEnabled().performClick()
        settle()

        assertTrue("banana" in label(4, 0))
        expectTurnIndicator("Player One")
        composeRule.onNodeWithText("t1").assertIsDisplayed()
    }

    // endregion

    // region Fitting the screen

    private fun bounds(interaction: SemanticsNodeInteraction): Rect = interaction.fetchSemanticsNode().boundsInRoot

    private fun boardBounds(): Rect {
        val first = bounds(node(0, 0))
        val last = bounds(node(5, 5))
        return Rect(first.left, first.top, last.right, last.bottom)
    }

    private fun dp(value: Int): Float = value * composeRule.density.density

    /** Top scoreboard, turn indicator, board, bottom scoreboard: in that order, apart, and on screen. */
    private fun assertTheBoardAndBothScoreboardsFit() {
        val screen = bounds(composeRule.onRoot())
        val top = bounds(composeRule.onNode(hasContentDescription("Player Two, score", substring = true)))
        val indicator = bounds(turnIndicator())
        val board = boardBounds()
        val bottom = bounds(composeRule.onNode(hasContentDescription("Player One, score", substring = true)))

        assertTrue(top.top >= bounds(composeRule.onNodeWithText("Rules")).bottom, "top scoreboard under the top bar")
        assertTrue(indicator.top >= top.bottom, "turn indicator under the top scoreboard")
        // The printed border is 6 dp outside the board, so each neighbour keeps at least 8 dp away.
        assertTrue(board.top - indicator.bottom >= dp(8), "board too close to the turn indicator")
        assertTrue(bottom.top - board.bottom >= dp(8), "board too close to the bottom scoreboard")
        assertTrue(board.left >= dp(8) && screen.right - board.right >= dp(8), "board too close to the screen edge")
        assertTrue(bottom.bottom <= screen.bottom, "bottom scoreboard runs off the screen")
        assertEquals(board.width, board.height, 1f)
        assertTrue(board.width >= dp(SMALLEST_BOARD_DP) - 1f, "board is ${board.width} px wide")
        for (row in 0..5) node(row, 0).assertIsDisplayed()
    }

    private fun assertTheControlsFit() {
        val restart = bounds(composeRule.onNodeWithText("Restart"))
        assertTrue(restart.bottom <= bounds(composeRule.onRoot()).bottom, "the controls run off the screen")
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
    }

    @Test
    fun `on a tall phone the whole match fits, with or without trackers`() {
        startLocalMatch(ruleSet = RuleSet.Wildcard)

        assertTheBoardAndBothScoreboardsFit()
        assertTheControlsFit()
        // As wide as the screen allows: 16 dp of padding and 10 dp of inset on each side.
        assertEquals(dp(411 - 52), boardBounds().width, dp(1))
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun `on a small phone a standard match fits without scrolling`() {
        startLocalMatch()

        assertTheBoardAndBothScoreboardsFit()
        assertTheControlsFit()
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun `on a small phone with trackers the board and scoreboards fit and the controls scroll into view`() {
        startLocalMatch(ruleSet = RuleSet.Wildcard)

        assertTheBoardAndBothScoreboardsFit()
        composeRule.onNodeWithText("Restart").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun `at a large font size the board keeps its size and everything else can be scrolled to`() {
        show(scope.matchViewModel(GameSetup.newGame(ruleSet = RuleSet.Wildcard)), fontScale = 2f)

        val board = boardBounds()
        assertEquals(board.width, board.height, 1f)
        assertTrue(board.width >= dp(SMALLEST_BOARD_DP) - 1f, "board is ${board.width} px wide")
        composeRule
            .onNode(
                hasContentDescription("Player Two, score", substring = true),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNode(
                hasContentDescription("Player One, score", substring = true),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Restart").performScrollTo().assertIsDisplayed()
        node(5, 5).performScrollTo().assertIsDisplayed()
    }

    // endregion

    // region What the tracker says

    private fun standing(
        smoothie: SmoothieProgress = SmoothieProgress(),
        hasFiredSmoothie: Boolean = false,
        bananasDelivered: Int = 0,
        ownFruitBlended: Int = 0,
        isBlenderamaCountered: Boolean = false,
        isBananaSplitBlocked: Boolean = false,
        isBlenderamaBlocked: Boolean = false,
    ) = WildcardStanding(
        smoothie = smoothie,
        hasFiredSmoothie = hasFiredSmoothie,
        bananasDelivered = bananasDelivered,
        ownFruitBlended = ownFruitBlended,
        isBlenderamaCountered = isBlenderamaCountered,
        isBananaSplitBlocked = isBananaSplitBlocked,
        isBlenderamaBlocked = isBlenderamaBlocked,
    )

    @Test
    fun `the tracker reads as one sentence, and blenderama joins it once started`() {
        assertEquals("Smoothie 0 of 3, Banana Split 0 of 3", standing().trackerDescription())
        assertEquals(
            "Smoothie 2 of 3, Banana Split 2 of 3, Blenderama 3 of 6",
            standing(
                smoothie = SmoothieProgress(banana = true, pineapple = true),
                bananasDelivered = 2,
                ownFruitBlended = 3,
            ).trackerDescription(),
        )
    }

    @Test
    fun `the tracker says what is spent, dead or countered`() {
        assertEquals(
            "Smoothie 3 of 3, already poured, Banana Split 1 of 3, no longer possible",
            standing(
                smoothie = SmoothieProgress(banana = true, strawberry = true, pineapple = true),
                hasFiredSmoothie = true,
                bananasDelivered = 1,
                isBananaSplitBlocked = true,
            ).trackerDescription(),
        )
        assertEquals(
            "Smoothie 0 of 3, Banana Split 0 of 3, Blenderama 2 of 6, countered",
            standing(
                ownFruitBlended = 2,
                isBlenderamaCountered = true,
                isBlenderamaBlocked = true,
            ).trackerDescription(),
        )
        assertEquals(
            "Smoothie 0 of 3, Banana Split 0 of 3, Blenderama 2 of 6, no longer possible",
            standing(ownFruitBlended = 2, isBlenderamaBlocked = true).trackerDescription(),
        )
        // Countered before it was ever started: nothing to show, so nothing to say.
        assertEquals(
            "Smoothie 0 of 3, Banana Split 0 of 3",
            standing(isBlenderamaCountered = true, isBlenderamaBlocked = true).trackerDescription(),
        )
    }

    // endregion

    companion object {
        // The phone BoardViewTest uses, and the smallest phone the layout is checked on.
        const val PHONE = "w411dp-h891dp-xhdpi"
        const val SMALL_PHONE = "w360dp-h640dp-xhdpi"
        private const val SMALLEST_BOARD_DP = 288
        private const val MOVE_LIMIT = 2_000
        private const val MOVES_THROUGH_THE_BOARD = 12
    }
}
