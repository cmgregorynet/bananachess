package com.charlesbarton.bananachess

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.charlesbarton.bananachess.match.MatchTags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScaffoldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // These tests are about getting from screen to screen, so the first-match introduction is
    // marked as seen. It is stored before any match is opened, and the Match screen reads it then.
    @Before
    fun skipTheIntroduction() {
        runBlocking {
            (composeRule.activity.application as BananaChessApplication).container.settings.update {
                it.copy(hasSeenTeachingOverlay = true)
            }
        }
    }

    private fun square(
        row: Int,
        column: Int,
    ) = composeRule.onNodeWithTag("square_${row}_$column")

    @Test
    fun `the app opens on the start screen`() {
        composeRule.onNodeWithText("Banana Chess").assertIsDisplayed()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `local two player opens a local match, and back returns`() {
        composeRule.onNodeWithText("Local Two Player").performClick()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assertTextEquals("Player One to move")
        square(4, 0).assertContentDescriptionContains("Player One banana", substring = true)
        composeRule.onNodeWithText("Local Two Player").assertDoesNotExist()

        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
    }

    @Test
    fun `play computer opens a computer match`() {
        composeRule.onNodeWithText("Play Computer").performClick()
        composeRule.onNodeWithText("Start match").performClick()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assertTextEquals("You to move")
    }

    @Test
    fun `recreating the activity keeps the match, and leaving the screen ends it`() {
        composeRule.onNodeWithText("Local Two Player").performClick()
        square(4, 0).performClick()
        square(3, 0).performClick()
        square(3, 0).assertContentDescriptionContains("Player One banana", substring = true)

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        square(3, 0).assertContentDescriptionContains("Player One banana", substring = true)
        square(4, 0).assertContentDescriptionContains("Empty square", substring = true)

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Local Two Player").performClick()

        square(4, 0).assertContentDescriptionContains("Player One banana", substring = true)
        square(3, 0).assertContentDescriptionContains("Empty square", substring = true)
    }

    @Test
    fun `rules opens over a match, and done returns to the same match`() {
        composeRule.onNodeWithText("Local Two Player").performClick()
        square(4, 0).performClick()
        square(3, 0).performClick()

        composeRule.onNodeWithText("Rules").performClick()

        composeRule.onNodeWithText("The turn").assertIsDisplayed()
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()

        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        square(3, 0).assertContentDescriptionContains("Player One banana", substring = true)
    }

    @Test
    fun `how to play opens the rules, and done returns`() {
        composeRule.onNodeWithText("How to play").performScrollTo().performClick()

        composeRule.onNodeWithText("The turn").assertIsDisplayed()
        composeRule.onNodeWithText("Local Two Player").assertDoesNotExist()

        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("The turn").assertDoesNotExist()
        // Home comes back where it was left: scrolled down to the button that opened the rules.
        composeRule.onNodeWithText("Local Two Player").assertExists()
        composeRule.onNodeWithText("How to play").assertIsDisplayed()
    }
}
