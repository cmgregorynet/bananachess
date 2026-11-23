package com.charlesbarton.bananachess.art

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.engine.model.PieceKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PieceArtworkTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // region Shapes

    @Test
    fun `there are eight shapes`() {
        assertEquals(8, PieceShapes.all.size)
        assertEquals(
            8,
            PieceShapes.all
                .map { it.first }
                .distinct()
                .size,
        )
    }

    @Test
    fun `every shape is non-empty and stays inside the unit square`() {
        for ((name, build) in PieceShapes.all) {
            val path = build(PieceShapes.UnitBounds)
            assertFalse(path.isEmpty, "$name is empty")
            val bounds = path.getBounds()
            assertTrue(bounds.width > 0.1f && bounds.height > 0.05f, "$name is degenerate: $bounds")
            assertInside(PieceShapes.UnitBounds, bounds, name)
        }
    }

    @Test
    fun `every shape scales into the bounds it is given`() {
        val target = Rect(10f, 20f, 110f, 220f)
        for ((name, build) in PieceShapes.all) {
            val unit = build(PieceShapes.UnitBounds).getBounds()
            val scaled = build(target).getBounds()
            assertInside(target, scaled, name)
            assertEquals(10f + unit.left * 100f, scaled.left, 0.01f, "$name left")
            assertEquals(20f + unit.top * 200f, scaled.top, 0.01f, "$name top")
            assertEquals(unit.width * 100f, scaled.width, 0.01f, "$name width")
            assertEquals(unit.height * 200f, scaled.height, 0.01f, "$name height")
        }
    }

    @Test
    fun `the silhouettes sit where the iOS drawings put them`() {
        // Each closed curve starts and ends on its first point, so these extremes are exact.
        assertEquals(0.93f, PieceShapes.strawberry().getBounds().bottom, 1e-4f)
        assertEquals(0.95f, PieceShapes.pineapple().getBounds().bottom, 1e-4f)
        assertEquals(0.03f, PieceShapes.pineappleCrown().getBounds().top, 1e-4f)
        assertEquals(0.06f, PieceShapes.strawberryCalyx().getBounds().top, 1e-4f)
        assertEquals(0.11f, PieceShapes.banana().getBounds().left, 1e-4f)
        assertEquals(0.19f, PieceShapes.blenderLid().getBounds().left, 1e-4f)
        assertEquals(0.81f, PieceShapes.blenderLid().getBounds().right, 1e-4f)
        // The jar stands on the base.
        assertTrue(PieceShapes.blenderJar().getBounds().bottom > PieceShapes.blenderBase().getBounds().top)
    }

    // endregion

    // region Palette and catalog

    @Test
    fun `each kind has its wash`() {
        assertEquals(Palette.bananaWash, Palette.wash(PieceKind.Banana))
        assertEquals(Palette.strawberryWash, Palette.wash(PieceKind.Strawberry))
        assertEquals(Palette.pineappleWash, Palette.wash(PieceKind.Pineapple))
        assertEquals(Palette.blenderGlass, Palette.wash(PieceKind.Blender))
    }

    @Test
    fun `sprite names follow the iOS asset names`() {
        assertEquals("piece_banana_p1", ArtCatalog.spriteName(PieceKind.Banana, PlayerSeat.Bottom))
        assertEquals("piece_strawberry_p2", ArtCatalog.spriteName(PieceKind.Strawberry, PlayerSeat.Top))
        assertEquals("piece_pineapple_p1", ArtCatalog.spriteName(PieceKind.Pineapple, PlayerSeat.Bottom))
        assertEquals("piece_blender_p2", ArtCatalog.spriteName(PieceKind.Blender, PlayerSeat.Top))
    }

    @Test
    fun `the catalog falls back to vectors while no sprite drawable exists`() {
        for (kind in PieceKind.entries) {
            for (seat in PlayerSeat.entries) {
                assertNull(ArtCatalog.spriteId(context, kind, seat), ArtCatalog.spriteName(kind, seat))
            }
        }

        var sprites = 0
        composeRule.setContent {
            for (kind in PieceKind.entries) {
                for (seat in PlayerSeat.entries) {
                    if (ArtCatalog.sprite(kind, seat) != null) sprites += 1
                }
            }
        }
        composeRule.runOnIdle { assertEquals(0, sprites) }
    }

    @Test
    fun `the catalog finds a drawable that does exist`() {
        assertNotNull(ArtCatalog.drawableId(context, "ic_launcher_foreground"))
        assertNull(ArtCatalog.drawableId(context, "piece_nothing_p3"))
    }

    // endregion

    // region Tray and halo descriptions

    @Test
    fun `only the top seat's tray carries the inner ring`() {
        val bottom = TrayLayers.of(PlayerSeat.Bottom)
        val top = TrayLayers.of(PlayerSeat.Top)

        assertNull(bottom.innerRing)
        assertEquals(TrayRing(color = Palette.cream.copy(alpha = 0.5f), width = 0.018f, inset = 0.075f), top.innerRing)

        val border = TrayRing(color = Palette.ink.copy(alpha = 0.75f), width = 0.030f)
        assertEquals(border, bottom.border)
        assertEquals(border, top.border)
    }

    @Test
    fun `trays are warm clay below and dusty indigo above, under the same grain`() {
        val bottom = TrayLayers.of(PlayerSeat.Bottom)
        val top = TrayLayers.of(PlayerSeat.Top)

        assertEquals(Palette.clayLight to Palette.clayDeep, bottom.light to bottom.shade)
        assertEquals(Palette.indigoLight to Palette.indigoDeep, top.light to top.shade)
        assertEquals(0.3f, bottom.grainOpacity)
        assertEquals(0.3f, top.grainOpacity)
        assertEquals(0.9f, TrayLayers.GRADIENT_RADIUS)
    }

    @Test
    fun `the drawing is padded more on a tray than off one`() {
        assertEquals(0.13f, PieceArtworkDefaults.vectorPadding(showsTray = true))
        assertEquals(0.02f, PieceArtworkDefaults.vectorPadding(showsTray = false))
        assertEquals(0.10f, PieceArtworkDefaults.spritePadding(showsTray = true))
        assertEquals(0f, PieceArtworkDefaults.spritePadding(showsTray = false))
    }

    @Test
    fun `the halo breathes between its two ends over 1,8 seconds`() {
        assertEquals(1800, GoldenHaloSpec.PERIOD_MILLIS)
        assertEquals(0.97f, GoldenHaloSpec.scale(0f), 1e-6f)
        assertEquals(1.06f, GoldenHaloSpec.scale(1f), 1e-6f)
        assertEquals(0.72f, GoldenHaloSpec.alpha(0f), 1e-6f)
        assertEquals(1f, GoldenHaloSpec.alpha(1f), 1e-6f)
        assertEquals(
            listOf(
                HaloGlow(opacity = 0.75f, blur = 0.16f, scale = 1.26f),
                HaloGlow(opacity = 0.4f, blur = 0.05f, scale = 1.08f),
            ),
            GoldenHaloSpec.glows,
        )
    }

    @Test
    fun `a blurred disc is solid inside, half gone on its edge and gone outside`() {
        assertEquals(1f, GoldenHaloSpec.coverage(distance = 0f, radius = 100f, sigma = 10f), 1e-3f)
        assertEquals(0.5f, GoldenHaloSpec.coverage(distance = 100f, radius = 100f, sigma = 10f), 1e-3f)
        // One sigma either side of the edge, from the normal distribution.
        assertEquals(0.8413f, GoldenHaloSpec.coverage(distance = 90f, radius = 100f, sigma = 10f), 1e-3f)
        assertEquals(0.1587f, GoldenHaloSpec.coverage(distance = 110f, radius = 100f, sigma = 10f), 1e-3f)
        assertEquals(0f, GoldenHaloSpec.coverage(distance = 140f, radius = 100f, sigma = 10f), 1e-3f)
    }

    @Test
    fun `reduced motion follows the system animator scale`() {
        val resolver = context.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(isReducedMotion(context))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(isReducedMotion(context))
    }

    // endregion

    // region Rendering

    @Test
    fun `the preview renders every piece`() {
        composeRule.setContent {
            Box(modifier = Modifier.testTag("preview")) { PieceArtworkPreview() }
        }

        val bitmap = capture("preview")
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `the preview renders with animations turned off`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        composeRule.setContent {
            Box(modifier = Modifier.testTag("preview")) { PieceArtworkPreview() }
        }

        val bitmap = capture("preview")
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `a piece adds no semantics of its own`() {
        composeRule.setContent {
            PaperTheme {
                Row(modifier = Modifier.testTag("square")) {
                    PieceArtwork(
                        kind = PieceKind.Pineapple,
                        seat = PlayerSeat.Top,
                        modifier = Modifier.size(64.dp),
                        isGolden = true,
                    )
                    PieceArtwork(kind = PieceKind.Blender, seat = PlayerSeat.Bottom, modifier = Modifier.size(64.dp))
                    PieceArtwork(
                        kind = PieceKind.Banana,
                        seat = PlayerSeat.Bottom,
                        modifier = Modifier.size(18.dp),
                        showsTray = false,
                    )
                }
            }
        }

        val square = composeRule.onNodeWithTag("square", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(square.children.isEmpty(), "the piece added semantics: ${square.children.map { it.config }}")
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(square.id), root.children.map { it.id })
    }

    @Test
    fun `each kind paints its wash, on a tray that is a circle in its seat's colour`() {
        composeRule.setContent {
            Column {
                for (kind in PieceKind.entries) {
                    Row {
                        for (seat in PlayerSeat.entries) {
                            Box(modifier = Modifier.testTag(tag(kind, seat)).background(Color.White)) {
                                PieceArtwork(kind = kind, seat = seat, modifier = Modifier.size(96.dp))
                            }
                        }
                    }
                }
            }
        }

        for (kind in PieceKind.entries) {
            for (seat in PlayerSeat.entries) {
                val tag = tag(kind, seat)
                val bitmap = capture(tag)
                val side = bitmap.width

                assertNear(Color.White, bitmap.getPixel(1, 1), 0.03f, "$tag: the tray is not clipped to a circle")
                // Just inside the border at nine o'clock, clear of the inner ring and every drawing.
                val trayPixel = bitmap.getPixel((side * 0.06f).toInt(), side / 2)
                val tray = TrayLayers.of(seat)
                assertTrue(
                    distance(tray.light, trayPixel) < 0.2f || distance(tray.shade, trayPixel) < 0.2f,
                    "$tag: tray pixel ${Integer.toHexString(trayPixel)}",
                )
                val (x, y) = WashProbe.getValue(kind)
                assertNear(Palette.wash(kind), bitmap.getPixel(probe(side, x), probe(side, y)), 0.2f, "$tag: wash")
            }
        }
    }

    @Test
    fun `a piece without a tray leaves the paper showing around it`() {
        composeRule.setContent {
            Box(modifier = Modifier.testTag("tally").background(Color.White)) {
                PieceArtwork(
                    kind = PieceKind.Strawberry,
                    seat = PlayerSeat.Top,
                    modifier = Modifier.size(96.dp),
                    showsTray = false,
                )
            }
        }
        val bitmap = capture("tally")
        val side = bitmap.width

        assertNear(Color.White, bitmap.getPixel((side * 0.06f).toInt(), side / 2), 0.03f, "beside the fruit")
        assertNear(Palette.strawberryWash, bitmap.getPixel(side / 2, (side * 0.36f).toInt()), 0.2f, "the fruit")
    }

    @Test
    fun `a piece is the largest centred square of the space it is given`() {
        composeRule.setContent {
            Box(modifier = Modifier.testTag("wide").background(Color.White)) {
                PieceArtwork(
                    kind = PieceKind.Banana,
                    seat = PlayerSeat.Bottom,
                    modifier = Modifier.size(width = 120.dp, height = 60.dp),
                )
            }
        }
        val bitmap = capture("wide")
        assertEquals(bitmap.height * 2, bitmap.width)

        // The tray is a circle as tall as the strip, in the middle of it; the ends stay empty.
        val middle = bitmap.height / 2
        assertNear(Color.White, bitmap.getPixel(bitmap.width / 8, middle), 0.03f, "left end")
        assertNear(Color.White, bitmap.getPixel(bitmap.width * 7 / 8, middle), 0.03f, "right end")
        val trayPixel = bitmap.getPixel(bitmap.width / 2 - (bitmap.height * 0.44f).toInt(), middle)
        assertNear(Palette.clayLight, trayPixel, 0.2f, "tray")
    }

    @Test
    fun `a piece with no room to draw in does not fail`() {
        composeRule.setContent {
            Row(modifier = Modifier.testTag("empty").background(Color.White)) {
                for (kind in PieceKind.entries) {
                    PieceArtwork(kind = kind, seat = PlayerSeat.Top, modifier = Modifier.size(0.dp), isGolden = true)
                    PieceArtwork(
                        kind = kind,
                        seat = PlayerSeat.Bottom,
                        modifier = Modifier.size(width = 40.dp, height = 0.dp),
                    )
                }
                Box(modifier = Modifier.size(8.dp))
            }
        }

        assertTrue(capture("empty").width > 0)
    }

    @Test
    fun `the golden pineapple is amber and glows past its tray`() {
        composeRule.setContent {
            Box(
                modifier = Modifier.testTag("board").size(160.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                PieceArtwork(
                    kind = PieceKind.Pineapple,
                    seat = PlayerSeat.Bottom,
                    modifier = Modifier.size(80.dp),
                    isGolden = true,
                )
            }
        }
        val bitmap = capture("board")
        val centre = bitmap.width / 2
        val pieceSide = bitmap.width / 2
        val origin = centre - pieceSide / 2

        // 0.56 of the piece's side to the left of its centre: outside the tray, inside the glow.
        val glow = Color(bitmap.getPixel(centre - (pieceSide * 0.56f).toInt(), centre))
        assertTrue(glow.blue < 0.9f && glow.red > glow.blue + 0.1f, "no glow beside the tray: $glow")
        assertNear(Color.White, bitmap.getPixel(2, 2), 0.03f, "the glow reaches the corner of the board")
        val (x, y) = WashProbe.getValue(PieceKind.Pineapple)
        val body = bitmap.getPixel(origin + probe(pieceSide, x), origin + probe(pieceSide, y))
        assertNear(Palette.amber, body, 0.2f, "body")
    }

    // endregion

    private fun capture(tag: String): Bitmap =
        composeRule
            .onNodeWithTag(tag)
            .captureToImage()
            .asAndroidBitmap()

    // Where the drawing sits inside a piece with a tray: unit coordinates inset by the padding.
    private fun probe(
        side: Int,
        unit: Float,
    ): Int {
        val padding = PieceArtworkDefaults.vectorPadding(showsTray = true)
        return (side * (padding + unit * (1f - 2f * padding))).toInt()
    }

    private fun tag(
        kind: PieceKind,
        seat: PlayerSeat,
    ): String = "${kind.rawValue}_${seat.name}"

    private fun assertNear(
        expected: Color,
        actual: Int,
        tolerance: Float,
        what: String,
    ) {
        val off = distance(expected, actual)
        assertTrue(off <= tolerance, "$what: pixel ${Integer.toHexString(actual)} is $off away from $expected")
    }

    private fun assertInside(
        outer: Rect,
        inner: Rect,
        name: String,
    ) {
        assertTrue(
            inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right &&
                inner.bottom <= outer.bottom,
            "$name leaves its bounds: $inner",
        )
    }

    private fun distance(
        expected: Color,
        actual: Int,
    ): Float {
        val pixel = Color(actual)
        return maxOf(
            abs(expected.red - pixel.red),
            abs(expected.green - pixel.green),
            abs(expected.blue - pixel.blue),
        )
    }

    private companion object {
        // A point of each drawing, in unit coordinates, that is plain wash: clear of seeds,
        // blades and outlines, and midway between the pineapple's hatching lines.
        val WashProbe =
            mapOf(
                PieceKind.Banana to (0.45f to 0.35f),
                PieceKind.Strawberry to (0.47f to 0.36f),
                PieceKind.Pineapple to (0.54f to 0.66f),
                PieceKind.Blender to (0.50f to 0.45f),
            )
    }
}
