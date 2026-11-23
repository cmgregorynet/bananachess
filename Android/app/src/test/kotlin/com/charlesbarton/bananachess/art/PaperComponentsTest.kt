package com.charlesbarton.bananachess.art

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun `a paper button shows its label, has the button role and clicks`() {
        var clicks by mutableIntStateOf(0)
        composeRule.setContent {
            PaperTheme {
                Column {
                    PaperButton(text = "Play again", onClick = { clicks += 1 }, isPrimary = true)
                    PaperButton(text = "Done", onClick = { clicks += 10 })
                }
            }
        }

        composeRule
            .onNodeWithText("Play again")
            .assertIsDisplayed()
            .assert(isButton)
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }

        composeRule
            .onNodeWithText("Done")
            .assert(isButton)
            .performClick()
        composeRule.runOnIdle { assertEquals(11, clicks) }
    }

    @Test
    fun `a disabled paper button does not click`() {
        var clicks by mutableIntStateOf(0)
        composeRule.setContent {
            PaperTheme {
                PaperButton(text = "Send link", onClick = { clicks += 1 }, enabled = false)
            }
        }

        composeRule
            .onNodeWithText("Send link")
            .assert(isButton)
            .assertIsNotEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun `a washi panel hosts its content on grained cream paper`() {
        composeRule.setContent {
            PaperTheme {
                PaperBackground {
                    WashiPanel(modifier = Modifier.testTag("panel").size(120.dp)) {
                        Text(text = "Player One")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Player One").assertIsDisplayed()
        val bitmap =
            composeRule
                .onNodeWithTag("panel")
                .captureToImage()
                .asAndroidBitmap()
        // Lower middle of the panel, clear of the text: cream, darkened a little by the grain at most.
        val row = bitmap.height * 3 / 4
        val pixels = (bitmap.width / 4 until bitmap.width * 3 / 4).map { bitmap.getPixel(it, row) }
        pixels.forEach { assertNear(Palette.cream, it, tolerance = 0.1f) }
        assertTrue(pixels.distinct().size > 1, "the panel carries no grain")
    }

    @Test
    fun `a watercolour wash paints its tint inside the shape only`() {
        composeRule.setContent {
            PaperTheme {
                WatercolorWash(
                    shape = CircleShape,
                    tint = Palette.strawberryWash,
                    modifier = Modifier.testTag("wash").size(80.dp),
                )
            }
        }

        val bitmap =
            composeRule
                .onNodeWithTag("wash")
                .captureToImage()
                .asAndroidBitmap()
        val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val corner = bitmap.getPixel(1, 1)
        assertNear(Palette.strawberryWash, centre, tolerance = 0.12f)
        assertTrue(distance(Palette.strawberryWash, corner) > 0.12f, "the wash spilled outside its shape")
    }

    private fun assertNear(
        expected: Color,
        actual: Int,
        tolerance: Float,
    ) {
        val off = distance(expected, actual)
        assertTrue(off <= tolerance, "pixel ${Integer.toHexString(actual)} is $off away from $expected")
    }

    private fun distance(
        expected: Color,
        actual: Int,
    ): Float {
        val pixel =
            androidx.compose.ui.graphics
                .Color(actual)
        return maxOf(
            abs(expected.red - pixel.red),
            abs(expected.green - pixel.green),
            abs(expected.blue - pixel.blue),
        )
    }
}
