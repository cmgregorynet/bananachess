package com.charlesbarton.bananachess.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeachingOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var finishedCount by mutableIntStateOf(0)
    private var tapsUnderneath by mutableIntStateOf(0)

    private fun showOverlay() {
        composeRule.setContent {
            PaperTheme {
                Box(modifier = Modifier.fillMaxSize().testTag(UNDERNEATH).clickable { tapsUnderneath += 1 })
                TeachingOverlay(onFinished = { finishedCount += 1 })
            }
        }
    }

    @Test
    fun `the pages are the four lessons, in order, each with its piece`() {
        assertEquals(
            listOf(
                "Move one piece" to PieceKind.Banana,
                "Reach the far edge" to PieceKind.Strawberry,
                "Blending cuts both ways" to PieceKind.Blender,
                "Blenders knock out" to PieceKind.Pineapple,
            ),
            TeachingPages.map { it.title to it.kind },
        )
    }

    @Test
    fun `the last page agrees with the rules about destroyed blenders`() {
        val body = TeachingPages.last().body

        assertFalse("two turns" in body)
        assertFalse("explode" in body)
        assertTrue("destroys that one outright" in body)
        assertTrue("never comes back on its own" in body)
        assertTrue("a turn and three points" in body)
    }

    @Test
    fun `no page teaches blending your own fruit on purpose`() {
        for (page in TeachingPages) {
            assertFalse("your own" in page.body, "\"${page.title}\" gives the self-blend tactic away")
        }
    }

    @Test
    fun `the state advances through four pages and then stops`() {
        val state = TeachingOverlayState()

        val labels = mutableListOf(state.actionLabel)
        while (state.advance()) labels += state.actionLabel

        assertEquals(listOf("Next", "Next", "Next", "Play"), labels)
        assertEquals(3, state.pageIndex)
        assertTrue(state.isLastPage)
        assertFalse(state.advance())
        assertEquals(3, state.pageIndex)
    }

    @Test
    fun `next advances through four pages and play calls back once`() {
        showOverlay()

        for ((index, page) in TeachingPages.withIndex()) {
            composeRule.onNodeWithText(page.title).assertIsDisplayed()
            composeRule.onNodeWithText(page.body).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Page ${index + 1} of 4").assertExists()
            assertEquals(0, finishedCount)
            if (index < TeachingPages.lastIndex) {
                composeRule.onNodeWithText("Play").assertDoesNotExist()
                composeRule.onNodeWithText("Next").performClick()
                composeRule.onNodeWithText(page.title).assertDoesNotExist()
            }
        }

        composeRule.onNodeWithText("Next").assertDoesNotExist()
        composeRule.onNodeWithText("two turns", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Play").performClick()

        assertEquals(1, finishedCount)
    }

    @Test
    fun `a tap on the scrim does not reach what is underneath`() {
        showOverlay()

        composeRule.onNodeWithTag(UNDERNEATH).performTouchInput { click(topLeft) }

        assertEquals(0, tapsUnderneath)
    }

    @Test
    fun `at the largest font size on a small screen the button is still on screen`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PaperTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 400.dp)) {
                        TeachingOverlay(onFinished = { finishedCount += 1 })
                    }
                }
            }
        }

        repeat(TeachingPages.lastIndex) { composeRule.onNodeWithText("Next").assertIsDisplayed().performClick() }
        composeRule.onNodeWithText("Play").assertIsDisplayed().performClick()

        assertEquals(1, finishedCount)
    }

    @Test
    fun `a first-time player sees the introduction, and play records that they have`() {
        val store = InMemorySettingsStore()
        composeRule.setContent { PaperTheme { FirstMatchTeachingOverlay(settings = store) } }

        composeRule.onNodeWithText("Move one piece").assertIsDisplayed()
        assertFalse(store.current.hasSeenTeachingOverlay)

        repeat(TeachingPages.lastIndex) { composeRule.onNodeWithText("Next").performClick() }
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitForIdle()

        assertTrue(store.current.hasSeenTeachingOverlay)
        composeRule.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun `a player who has seen the introduction is not shown it again`() {
        val store = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true))
        composeRule.setContent {
            PaperTheme {
                Text(text = "board")
                FirstMatchTeachingOverlay(settings = store)
            }
        }

        composeRule.onNodeWithText("board").assertIsDisplayed()
        composeRule.onNodeWithText("Move one piece").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
    }

    private companion object {
        const val UNDERNEATH = "underneath"
    }
}
