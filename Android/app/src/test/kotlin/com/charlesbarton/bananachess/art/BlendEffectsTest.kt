package com.charlesbarton.bananachess.art

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.random.SplitMix64
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.match.BlendBurst
import com.charlesbarton.bananachess.match.BlendEffectPolicy
import com.charlesbarton.bananachess.match.BoardView
import com.charlesbarton.bananachess.match.MatchScreen
import com.charlesbarton.bananachess.match.MatchViewModel
import com.charlesbarton.bananachess.match.Splatter
import com.charlesbarton.bananachess.match.advance
import com.charlesbarton.bananachess.match.matchViewModel
import com.charlesbarton.bananachess.match.square
import com.charlesbarton.bananachess.match.ui
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class BlendEffectsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = TestScope()

    // region Droplets

    @Test
    fun `a seed always throws the same droplets, and another seed throws others`() {
        assertEquals(Droplet.burst(42uL), Droplet.burst(42uL))
        assertEquals(Droplet.splatter(42uL), Droplet.splatter(42uL))
        assertNotEquals(Droplet.burst(42uL), Droplet.burst(43uL))
        assertNotEquals(Droplet.splatter(42uL), Droplet.splatter(43uL))
    }

    @Test
    fun `a burst throws eleven droplets evenly around the square, within its ranges`() {
        for (seed in 0uL until 200uL) {
            val droplets = Droplet.burst(seed)
            assertEquals(11, droplets.size)
            droplets.forEachIndexed { index, droplet ->
                assertEquals(index, droplet.id)
                val base = index / 11.0 * 2 * PI
                assertTrue(abs(droplet.angle - base) <= 0.30, "angle jitter ${droplet.angle - base}")
                assertTrue(droplet.distance in 0.55..1.15, "distance ${droplet.distance}")
                assertTrue(droplet.size in 0.13..0.30, "size ${droplet.size}")
                assertTrue(droplet.spin in -1.2..1.2, "spin ${droplet.spin}")
            }
        }
    }

    @Test
    fun `a splatter throws thirty-four droplets, mostly near where the fruit was`() {
        val all =
            (0uL until 200uL).flatMap { seed ->
                val droplets = Droplet.splatter(seed)
                assertEquals(34, droplets.size)
                droplets.forEachIndexed { index, droplet ->
                    val base = index / 34.0 * 2 * PI
                    assertTrue(abs(droplet.angle - base) <= 0.45, "angle jitter ${droplet.angle - base}")
                    assertTrue(droplet.size in 0.015..0.085, "size ${droplet.size}")
                    assertTrue(droplet.spin in -2.0..2.0, "spin ${droplet.spin}")
                }
                droplets
            }
        val lowest = Math.pow(0.05, 1.7) * 0.95
        assertTrue(all.all { it.distance in lowest..0.95 })
        // rand^1.7 puts well over half of them in the inner half of the reach.
        assertTrue(all.count { it.distance < 0.95 / 2 } > all.size * 0.6)
    }

    @Test
    fun `a closed-range draw stays in its range and spans it`() {
        val generator = SplitMix64(7uL)
        val draws = List(5_000) { generator.nextDouble(-0.3, 0.3) }
        assertTrue(draws.all { it in -0.3..0.3 })
        assertTrue(draws.min() < -0.29 && draws.max() > 0.29)
    }

    @Test
    fun `a droplet is a closed nine-lobed blob inside its bounds`() {
        val bounds = Rect(10f, 20f, 110f, 120f)
        for (seed in 0uL until 50uL) {
            val drawn = dropletPath(seed, bounds).getBounds()
            assertTrue(drawn.left >= bounds.left - 0.01f && drawn.right <= bounds.right + 0.01f, "$drawn")
            assertTrue(drawn.top >= bounds.top - 0.01f && drawn.bottom <= bounds.bottom + 0.01f, "$drawn")
            // Every lobe is at least 0.58 of the radius out, so the blob is never a sliver.
            assertTrue(drawn.width > 50f && drawn.height > 50f, "$drawn")
        }
        assertEquals(dropletPath(3uL, bounds).getBounds(), dropletPath(3uL, bounds).getBounds())
    }

    // endregion

    // region Curves

    @Test
    fun `a burst flings its pulp, holds it and fades it within its lifetime`() {
        assertEquals(0f, BlendBurstCurves.pulp(0f))
        assertEquals(1f, BlendBurstCurves.pulp(0.06f))
        assertEquals(1f, BlendBurstCurves.pulp(0.22f))
        assertEquals(0.5f, BlendBurstCurves.pulp(0.40f), 0.001f)
        assertEquals(0f, BlendBurstCurves.pulp(0.58f))
        assertEquals(0f, BlendBurstCurves.spread(0f, reduceMotion = false))
        assertTrue(BlendBurstCurves.spread(0.2f, reduceMotion = false) in 0.5f..1.2f)
        assertEquals(1f, BlendBurstCurves.spread(0.55f, reduceMotion = false))
        assertEquals(0f, BlendBurstCurves.ring(0f))
        assertEquals(0.5f, BlendBurstCurves.ring(0.25f), 0.001f)
        assertEquals(1f, BlendBurstCurves.ring(0.5f))
        assertTrue(BlendBurstCurves.DURATION_SECONDS * 1000 < BlendBurst.LIFETIME_MILLIS)
    }

    @Test
    fun `under reduced motion the pulp never travels and the splatter never springs`() {
        for (step in 0..200) {
            val seconds = step / 100f
            assertEquals(0f, BlendBurstCurves.spread(seconds, reduceMotion = true))
            assertEquals(1f, SplatterCurves.reach(seconds, reduceMotion = true))
        }
        // The fade still happens, a little slower to rise.
        assertEquals(0.5f, SplatterCurves.ink(0.30f, reduceMotion = true))
        assertEquals(0f, SplatterCurves.ink(SplatterCurves.duration(reduceMotion = true), reduceMotion = true))
    }

    @Test
    fun `a splatter is thrown out, inked to half and dried within its lifetime`() {
        assertEquals(0f, SplatterCurves.reach(0f, reduceMotion = false))
        val thrown = SplatterCurves.reach(0.26f, reduceMotion = false)
        assertTrue(thrown in 0.8f..1f, "reach at the end of the throw: $thrown")
        assertEquals(1f, SplatterCurves.reach(1.46f, reduceMotion = false))
        assertEquals(0f, SplatterCurves.ink(0f, reduceMotion = false))
        assertEquals(0.5f, SplatterCurves.ink(0.14f, reduceMotion = false))
        assertEquals(0.5f, SplatterCurves.ink(0.56f, reduceMotion = false))
        assertEquals(0f, SplatterCurves.ink(1.46f, reduceMotion = false))
        assertTrue(SplatterCurves.duration(reduceMotion = true) * 1000 < Splatter.LIFETIME_MILLIS)
    }

    @Test
    fun `a pulped piece shrinks to 0·22 spinning 150 degrees, and arrives from 0·4`() {
        assertLook(Triple(1f, 0f, 1f), Pulped.look(1f, isLeaving = true, reduceMotion = false))
        assertLook(Triple(0.22f, 150f, 0f), Pulped.look(0f, isLeaving = true, reduceMotion = false))
        assertLook(Triple(0.4f, 0f, 0f), Pulped.look(0f, isLeaving = false, reduceMotion = false))
        assertLook(Triple(0.7f, 0f, 0f), Pulped.look(0f, isLeaving = true, reduceMotion = true))
        assertLook(Triple(0.7f, 0f, 0f), Pulped.look(0f, isLeaving = false, reduceMotion = true))
    }

    private fun assertLook(
        expected: Triple<Float, Float, Float>,
        actual: Triple<Float, Float, Float>,
    ) {
        assertEquals(expected.first, actual.first, 0.0001f)
        assertEquals(expected.second, actual.second, 0.0001f)
        assertEquals(expected.third, actual.third, 0.0001f)
    }

    // endregion

    // region On screen

    @Test
    fun `bursts and splatters add nothing to the accessibility tree`() {
        composeRule.setContent {
            PaperTheme {
                Box(modifier = Modifier.testTag(FRAME).size(300.dp)) {
                    SplatterView(PieceKind.Strawberry, seed = 1uL, reduceMotion = false, origin = { null })
                    BlendBurstView(PieceKind.Banana, seed = 2uL, reduceMotion = false, modifier = Modifier.size(50.dp))
                }
            }
        }
        val frame = composeRule.onNodeWithTag(FRAME, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(emptyList(), frame.children)
    }

    @Test
    fun `blending a fruit throws a burst on its square that is gone after its lifetime`() {
        val viewModel = scope.matchViewModel()
        val placed = mutableMapOf<Long, Offset>()
        composeRule.setContent {
            val ui by viewModel.state.collectAsState()
            PaperTheme {
                Box(modifier = Modifier.padding(24.dp)) {
                    BoardView(
                        state = ui,
                        onSquareTap = viewModel::tap,
                        modifier = Modifier.size(360.dp).testTag(FRAME),
                        onBurstPlaced = { id, centre -> placed[id] = centre },
                        onBurstFinished = viewModel::retireBurst,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        selfBlend(viewModel)

        val burst = viewModel.ui.blendBursts.single()
        composeRule.mainClock.advanceTimeByFrame()
        val square =
            composeRule
                .onNodeWithTag("square_4_0")
                .fetchSemanticsNode()
                .boundsInRoot
        val centre = assertNotNull(placed[burst.id], "the burst was laid out")
        assertTrue(hypot(centre.x - square.center.x, centre.y - square.center.y) < 1f, "on its square")

        // The view, not the view model, retires it: the view model's own clock is held still.
        composeRule.mainClock.advanceTimeBy(150)
        composeRule.mainClock.advanceTimeBy(BlendBurst.LIFETIME_MILLIS - 200)
        assertEquals(listOf(burst), viewModel.ui.blendBursts)
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals(emptyList(), viewModel.ui.blendBursts)
    }

    @Test
    fun `with the splatter always on, pulp lands across the screen and then dries`() {
        val viewModel = showMatch(BlendEffectPolicy.Always)
        selfBlend(viewModel)
        val splatter = assertNotNull(viewModel.ui.splatter)

        composeRule.mainClock.advanceTimeBy(SPLATTER_MIDWAY_MILLIS)
        val wet = screen()
        composeRule.mainClock.advanceTimeBy(Splatter.LIFETIME_MILLIS)
        assertNull(viewModel.ui.splatter, "the view retired splatter ${splatter.id}")
        val dry = screen()

        assertTrue(changedAwayFromTheBoard(wet, dry) > 100, "the splatter reached past the board")
    }

    @Test
    fun `with the splatter never on, nothing lands past the board`() {
        val viewModel = showMatch(BlendEffectPolicy.Never)
        selfBlend(viewModel)
        assertNull(viewModel.ui.splatter)

        composeRule.mainClock.advanceTimeBy(SPLATTER_MIDWAY_MILLIS)
        val wet = screen()
        composeRule.mainClock.advanceTimeBy(Splatter.LIFETIME_MILLIS)
        val dry = screen()

        assertEquals(0, changedAwayFromTheBoard(wet, dry))
    }

    private fun showMatch(policy: BlendEffectPolicy): MatchViewModel {
        val viewModel = scope.matchViewModel(GameSetup.newGame(), blendEffects = policy)
        composeRule.setContent {
            PaperTheme {
                MatchScreen(
                    viewModel = viewModel,
                    onExit = {},
                    onShowRules = {},
                    showsDebugControls = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        return viewModel
    }

    /**
     * Player One's blender blends its own banana, and the view model's clock runs just far
     * enough to show it. From here only the screen's clock moves.
     */
    private fun selfBlend(viewModel: MatchViewModel) {
        viewModel.tap(square(5, 1))
        viewModel.tap(square(4, 0))
        viewModel.confirmSelfBlend()
        scope.advance(SHOWN_MILLIS)
        assertEquals(1, viewModel.ui.blendBursts.size)
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun screen(): Bitmap =
        composeRule
            .onRoot()
            .captureToImage()
            .asAndroidBitmap()

    /** Pixels that differ between [a] and [b] more than a square's width away from the board. */
    private fun changedAwayFromTheBoard(
        a: Bitmap,
        b: Bitmap,
    ): Int {
        val first = composeRule.onNodeWithTag("square_0_0").fetchSemanticsNode().boundsInRoot
        val last = composeRule.onNodeWithTag("square_5_5").fetchSemanticsNode().boundsInRoot
        val board = Rect(first.topLeft, last.bottomRight).inflate(first.width)
        var changed = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) {
                if (!board.contains(Offset(x.toFloat(), y.toFloat())) && a.getPixel(x, y) != b.getPixel(x, y)) {
                    changed += 1
                }
            }
        }
        return changed
    }

    private companion object {
        const val FRAME = "frame"

        // The blend is shown within this on the view model's clock, and retired only after it.
        const val SHOWN_MILLIS = 700L

        // Inside the splatter's half-strength hold.
        const val SPLATTER_MIDWAY_MILLIS = 400L
    }

    // endregion
}
