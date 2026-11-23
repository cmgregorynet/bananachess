package com.charlesbarton.bananachess

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    @Test
    fun `the app opens on the start screen`() {
        composeRule.onNodeWithText("Banana Chess").assertIsDisplayed()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
        composeRule.onNodeWithTag(PlaceholderTags.MATCH).assertDoesNotExist()
    }

    @Test
    fun `local two player opens the match route with a local match, and back returns`() {
        composeRule.onNodeWithText("Local Two Player").performClick()

        composeRule.onNodeWithTag(PlaceholderTags.MATCH).assertIsDisplayed()
        composeRule.onNodeWithText("Player One to move").assertIsDisplayed()
        composeRule.onNodeWithText("Local Two Player").assertDoesNotExist()

        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithTag(PlaceholderTags.MATCH).assertDoesNotExist()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
    }

    @Test
    fun `play computer opens the match route with a computer match`() {
        composeRule.onNodeWithText("Play Computer").performClick()
        composeRule.onNodeWithText("Start match").performClick()

        composeRule.onNodeWithTag(PlaceholderTags.MATCH).assertIsDisplayed()
        composeRule.onNodeWithText("You to move").assertIsDisplayed()
    }

    @Test
    fun `how to play opens the rules route`() {
        composeRule.onNodeWithText("How to play").performScrollTo().performClick()

        composeRule.onNodeWithTag(PlaceholderTags.RULES).assertIsDisplayed()
    }

    @Test
    fun `invite by link opens its placeholder`() {
        composeRule.onNodeWithText("Invite by Link").performClick()

        composeRule.onNodeWithTag(PlaceholderTags.INVITE).assertIsDisplayed()
    }
}
