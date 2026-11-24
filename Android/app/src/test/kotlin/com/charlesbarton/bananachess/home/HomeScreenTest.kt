package com.charlesbarton.bananachess.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val store = InMemorySettingsStore()
    private val started = mutableListOf<MatchSource>()
    private var invites by mutableIntStateOf(0)
    private var rulesOpened by mutableIntStateOf(0)

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun showHome(settings: Settings? = null) {
        val source = settings?.let(::InMemorySettingsStore) ?: store
        // Unconfined, so the load and every write have happened by the time a call returns.
        val viewModel = HomeViewModel(source, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            PaperTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onStartMatch = { started += it },
                    onInvite = { invites += 1 },
                    onShowRules = { rulesOpened += 1 },
                )
            }
        }
    }

    @Test
    fun `the title and tagline are shown`() {
        showHome()

        composeRule.onNodeWithText("Banana Chess").assertIsDisplayed()
        composeRule.onNodeWithText("Race the fruit. Mind the blenders.").assertIsDisplayed()
    }

    @Test
    fun `each menu button is a button that reads its title then its detail`() {
        showHome()

        val menu =
            listOf(
                "Local Two Player" to "Share one phone and take turns.",
                "Play Computer" to "Medium · Takes the point in front of it.",
                "Invite by Link" to "Send a friend a link and play.",
            )
        for ((title, detail) in menu) {
            composeRule
                .onNodeWithText(title)
                .assert(hasText(detail))
                .assertContentDescriptionEquals("$title. $detail")
                .assert(role(Role.Button))
                .assertHasClickAction()
        }
    }

    @Test
    fun `there is no online play on Android`() {
        showHome()

        composeRule.onNodeWithText("Play Online", substring = true, ignoreCase = true).assertDoesNotExist()
        composeRule.onNodeWithText("Game Center", substring = true, ignoreCase = true).assertDoesNotExist()
        composeRule.onNodeWithText("iCloud", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun `local two player starts a local match on the chosen board`() {
        showHome(Settings(boardSetup = BoardSetup.Blendy, wildcardRulesEnabled = true))

        composeRule.onNodeWithText("Local Two Player").performClick()

        composeRule.runOnIdle {
            val start = (started.single() as MatchSource.New).deal()
            assertEquals(MatchMode.LocalVersus, start.mode)
            assertEquals(BoardSetup.Blendy, start.state.boardSetup)
            assertEquals(RuleSet.Wildcard, start.state.ruleSet)
        }
    }

    @Test
    fun `invite by link and how to play report their taps`() {
        showHome()

        composeRule.onNodeWithText("Invite by Link").performClick()
        composeRule
            .onNodeWithText("How to play")
            .performScrollTo()
            .assert(role(Role.Button))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, invites)
            assertEquals(1, rulesOpened)
            assertEquals(emptyList(), started)
        }
    }

    @Test
    fun `the board picker is exclusive and its blurb follows the selection`() {
        showHome()

        composeRule.onNodeWithText("Standard").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Blendy").assertIsNotSelected()
        composeRule.onNodeWithText("Random").assertIsNotSelected()
        composeRule.onNodeWithText("Blenders behind, fruit in front.").assertExists()

        composeRule.onNodeWithText("Blendy").performClick()

        composeRule.onNodeWithText("Standard").assertIsNotSelected()
        composeRule.onNodeWithText("Blendy").assertIsSelected()
        composeRule.onNodeWithText("Random").assertIsNotSelected()
        composeRule.onNodeWithText("Swapped: fruit behind, blenders in front.").assertExists()
        composeRule.onNodeWithText("Blenders behind, fruit in front.").assertDoesNotExist()

        composeRule.onNodeWithText("Random").performClick()

        composeRule.onNodeWithText("Standard").assertIsNotSelected()
        composeRule.onNodeWithText("Blendy").assertIsNotSelected()
        composeRule.onNodeWithText("Random").assertIsSelected()
        composeRule.onNodeWithText("Everything scattered. A different board every time.").assertExists()
        composeRule.runOnIdle { assertEquals(BoardSetup.Random, store.current.boardSetup) }
    }

    @Test
    fun `the edges picker is exclusive and its blurb follows the selection`() {
        showHome()

        composeRule.onNodeWithText("Walls").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Edges are walls. Everything stops at them.").assertExists()

        composeRule.onNodeWithText("All").performClick()

        composeRule.onNodeWithText("Walls").assertIsNotSelected()
        composeRule.onNodeWithText("Sides").assertIsNotSelected()
        composeRule.onNodeWithText("All").assertIsSelected()
        composeRule
            .onNodeWithText("Fruit can cross any edge, top and bottom too. Blenders never can.")
            .assertExists()
        // The other picker is a separate choice and has not moved.
        composeRule.onNodeWithText("Standard").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(BoardWrap.All, store.current.boardWrap)
            assertEquals(BoardSetup.Standard, store.current.boardSetup)
        }
    }

    @Test
    fun `each picker button is a radio button that reads its name and what it means`() {
        showHome()

        val options = BoardSetup.entries + BoardWrap.entries
        for (option in options) {
            composeRule
                .onNodeWithText(option.displayName)
                .assertContentDescriptionEquals("${option.displayName}. ${option.blurb}")
                .assert(role(Role.RadioButton))
                .assertHasClickAction()
        }
    }

    @Test
    fun `the checkboxes toggle, report their state and read their detail`() {
        showHome()

        val wildcardDetail = "Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama."
        val battlesDetail =
            "Blenders fight for a square instead of just taking it. " +
                "Half your standing fruit, plus one for attacking, plus a die each."

        composeRule
            .onNodeWithText("Wildcard rules")
            .performScrollTo()
            .assert(role(Role.Checkbox))
            .assertContentDescriptionEquals("Wildcard rules. $wildcardDetail")
            .assert(hasText(wildcardDetail))
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule
            .onNodeWithText("Blender battles")
            .performScrollTo()
            .assert(role(Role.Checkbox))
            .assertContentDescriptionEquals("Blender battles. $battlesDetail")
            .assert(hasText(battlesDetail))
            .assertIsOff()
        composeRule.runOnIdle {
            assertEquals(true, store.current.wildcardRulesEnabled)
            assertEquals(false, store.current.blenderBattles)
        }

        composeRule
            .onNodeWithText("Blender battles")
            .performClick()
            .assertIsOn()
        composeRule
            .onNodeWithText("Wildcard rules")
            .performScrollTo()
            .performClick()
            .assertIsOff()
        composeRule.runOnIdle {
            assertEquals(false, store.current.wildcardRulesEnabled)
            assertEquals(true, store.current.blenderBattles)
        }
    }

    @Test
    fun `stored settings are what the screen opens on`() {
        showHome(
            Settings(
                cpuDifficulty = CpuDifficulty.Brutal,
                boardSetup = BoardSetup.Random,
                boardWrap = BoardWrap.Sides,
                wildcardRulesEnabled = true,
            ),
        )

        composeRule
            .onNodeWithContentDescription(
                "Play Computer. Brutal · Answers your plan before you finish it. Takes its time.",
            ).assertExists()
        composeRule.onNodeWithText("Random").assertIsSelected()
        composeRule.onNodeWithText("Sides").assertIsSelected()
        composeRule.onNodeWithText("Wildcard rules").assertIsOn()
        composeRule.onNodeWithText("Blender battles").assertIsOff()
    }

    @Test
    fun `play computer opens the options and starts the match that was chosen`() {
        showHome()

        composeRule.onNodeWithText("Start match").assertDoesNotExist()
        composeRule.onNodeWithText("Play Computer").performClick()

        for (difficulty in CpuDifficulty.entries) {
            composeRule
                .onNodeWithText(difficulty.displayName)
                .assertContentDescriptionEquals("${difficulty.displayName}. ${difficulty.blurb}")
                .assert(role(Role.RadioButton))
        }
        composeRule.onNodeWithText("Medium").assertIsSelected()

        composeRule.onNodeWithText("Hard").performClick()

        composeRule.onNodeWithText("Hard").assertIsSelected()
        composeRule.onNodeWithText("Medium").assertIsNotSelected()
        composeRule.onNodeWithText("Looks several moves ahead, and will trade.").assertExists()

        composeRule
            .onNodeWithText("You move first")
            .assert(role(Role.Switch))
            .assertIsOn()
            .performClick()
            .assertIsOff()

        composeRule
            .onNodeWithText("Start match")
            .assert(role(Role.Button))
            .performClick()

        composeRule.onNodeWithText("Start match").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(
                MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.Two),
                (started.single() as MatchSource.New).mode,
            )
            assertEquals(CpuDifficulty.Hard, store.current.cpuDifficulty)
            assertEquals(false, store.current.cpuHumanPlaysFirst)
        }
        composeRule
            .onNodeWithContentDescription("Play Computer. Hard · Looks several moves ahead, and will trade.")
            .assertExists()
    }
}
