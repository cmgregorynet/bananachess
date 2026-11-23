package com.charlesbarton.bananachess.rules

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.charlesbarton.bananachess.art.PaperTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var doneCount by mutableIntStateOf(0)

    private fun showRules(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PaperTheme {
                    RulesScreen(onDone = { doneCount += 1 })
                }
            }
        }
    }

    private fun assertTextPresent(phrase: String) {
        val matches = composeRule.onAllNodesWithText(phrase, substring = true).fetchSemanticsNodes()
        assertTrue(matches.isNotEmpty(), "Expected the rules to contain \"$phrase\"")
    }

    @Test
    fun `the rules contain every phrase the game depends on players knowing`() {
        showRules()

        for (phrase in listOf(
            "Blend your own and you lose it",
            "below zero",
            "destroys that one outright",
            "no extra move",
            "three points",
        )) {
            assertTextPresent(phrase)
        }
    }

    @Test
    fun `the title and every section are headings, in order`() {
        showRules()

        val headings =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .fetchSemanticsNodes()
                .map { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .joinToString { it.text }
                }

        assertEquals(
            listOf(
                "How to play",
                "The turn",
                "How pieces move",
                "Scoring",
                "Blending cuts both ways",
                "Blender combat",
                "Fruit is the clock",
                "Wildcard rules",
            ),
            headings,
        )
    }

    @Test
    fun `each fruit's scoring row is read as one sentence`() {
        showRules()

        composeRule.onNodeWithContentDescription("banana, blended for 1 points, delivered for 2").assertExists()
        composeRule.onNodeWithContentDescription("strawberry, blended for 2 points, delivered for 4").assertExists()
        composeRule.onNodeWithContentDescription("pineapple, blended for 3 points, delivered for 6").assertExists()
    }

    @Test
    fun `the wildcard section explains all four rules, each read with its name`() {
        showRules()

        for (name in listOf("Golden Pineapple", "Banana delivery", "Tropical Smoothie", "Blenderama")) {
            // Merged: the entry's node carries the name and the explanation together.
            val texts =
                composeRule
                    .onNodeWithText(name)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
            assertEquals(2, texts.size, "Expected \"$name\" to be read with its explanation")
        }
        assertTextPresent("Off by default. Tick the box on the start screen to play with them.")
    }

    @Test
    fun `done calls back`() {
        showRules()

        composeRule.onNodeWithText("Done").assertHasClickAction().performClick()

        assertEquals(1, doneCount)
    }

    @Test
    fun `at the largest font size the last rule can still be scrolled to`() {
        showRules(fontScale = 2f)

        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Blenderama").performScrollTo().assertIsDisplayed()
    }
}
