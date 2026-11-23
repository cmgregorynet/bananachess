package com.charlesbarton.bananachess.art

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Warm-paper grain, generated once per pixel scale and tiled. The speckle is deterministic, so
 * the paper is the same on every launch and screenshots stay stable.
 */
object PaperGrain {
    /** Tile side in dp. */
    const val TILE_SIDE = 128

    const val DEFAULT_OPACITY = 0.5f
    const val BOARD_OPACITY = 0.4f
    const val PANEL_OPACITY = 0.35f
    const val TRAY_OPACITY = 0.3f
    const val BACKGROUND_OPACITY = 0.6f

    internal const val SPECKLE_COUNT = 3200
    internal const val MAX_SCALE = 4
    private const val SPECKLE_GREY = 0.35f

    private val tiles = arrayOfNulls<ImageBitmap>(MAX_SCALE + 1)

    /**
     * The tile as opaque ARGB pixels, row by row, `TILE_SIDE * scale` on each side. White paper
     * with grey speckles; meant to be multiplied over whatever is underneath.
     */
    fun tilePixels(scale: Int = 1): IntArray {
        require(scale in 1..MAX_SCALE) { "Grain scale must be between 1 and $MAX_SCALE." }
        val side = TILE_SIDE * scale
        val grey = FloatArray(side * side) { 1f }
        val random = GrainRandom()
        repeat(SPECKLE_COUNT) {
            val x = random.next() * TILE_SIDE
            val y = random.next() * TILE_SIDE
            val size = 0.5 + random.next() * 1.4
            val alpha = 0.02 + random.next() * 0.06
            blendSpeckle(grey, side, x * scale, y * scale, size * scale, alpha)
        }
        return IntArray(grey.size) { index ->
            val channel = (grey[index] * 255f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (channel shl 16) or (channel shl 8) or channel
        }
    }

    /** The cached tile for a screen density. */
    fun tile(density: Float): ImageBitmap {
        val scale = density.roundToInt().coerceIn(1, MAX_SCALE)
        return synchronized(tiles) {
            tiles[scale] ?: createTile(scale).also { tiles[scale] = it }
        }
    }

    private fun createTile(scale: Int): ImageBitmap {
        val side = TILE_SIDE * scale
        return Bitmap
            .createBitmap(tilePixels(scale), side, side, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    // Source-over of one anti-aliased square; speckles that cross the tile edge are cut off there.
    private fun blendSpeckle(
        grey: FloatArray,
        side: Int,
        left: Double,
        top: Double,
        size: Double,
        alpha: Double,
    ) {
        val right = left + size
        val bottom = top + size
        val lastColumn = min(side - 1, ceil(right).toInt() - 1)
        val lastRow = min(side - 1, ceil(bottom).toInt() - 1)
        for (row in max(0, floor(top).toInt())..lastRow) {
            val coveredHeight = min(bottom, row + 1.0) - max(top, row.toDouble())
            for (column in max(0, floor(left).toInt())..lastColumn) {
                val coveredWidth = min(right, column + 1.0) - max(left, column.toDouble())
                val coverage = (coveredWidth * coveredHeight * alpha).toFloat()
                if (coverage > 0f) {
                    val index = row * side + column
                    grey[index] = grey[index] * (1f - coverage) + SPECKLE_GREY * coverage
                }
            }
        }
    }
}

/** The fixed-seed generator behind the grain. Values are in 0..1. */
internal class GrainRandom(
    private var seed: Long = 0x5EED1234ABCD0001L,
) {
    fun next(): Double {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 33) and 0xFFFF).toDouble() / 65535.0
    }
}

/**
 * Multiplies the paper grain over this element and whatever is already drawn behind it. It does
 * not clip: apply it after [clip] when the element is not a rectangle.
 */
fun Modifier.paperGrain(opacity: Float = PaperGrain.DEFAULT_OPACITY): Modifier =
    drawWithCache {
        val brush = ShaderBrush(ImageShader(PaperGrain.tile(density), TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush, alpha = opacity, blendMode = BlendMode.Multiply)
        }
    }

/**
 * The full-screen ground: warm paper with a soft wash toward the edges. Draws under the system
 * bars when the window is edge to edge; pad [content] for insets.
 */
@Composable
fun PaperBackground(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawWithCache {
                    val glowRadius = 460.dp.toPx()
                    // Gradients fade to the same hue at zero alpha, never to transparent black,
                    // which would grey the paper on the way out.
                    val glow =
                        Brush.radialGradient(
                            0f to Palette.cream.copy(alpha = 0.9f),
                            40f / 460f to Palette.cream.copy(alpha = 0.9f),
                            1f to Palette.cream.copy(alpha = 0f),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = glowRadius,
                        )
                    val shade =
                        Brush.verticalGradient(
                            listOf(Palette.sand.copy(alpha = 0f), Palette.sand.copy(alpha = 0.28f)),
                        )
                    onDrawBehind {
                        drawRect(Palette.warmPaper)
                        drawRect(glow)
                        drawRect(shade)
                    }
                }.paperGrain(PaperGrain.BACKGROUND_OPACITY),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** The washi panel the whole UI is built from: warm paper, a soft pencil border, no shadow. */
@Composable
fun WashiPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    fill: Color = Palette.cream,
    isAccented: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .paperGrain(PaperGrain.PANEL_OPACITY)
                .border(
                    width = if (isAccented) 2.5.dp else 1.4.dp,
                    color = if (isAccented) Palette.amber else Palette.inkSoft,
                    shape = shape,
                ),
        content = content,
    )
}

@Preview(widthDp = 320, heightDp = 480)
@Composable
private fun PaperBackgroundPreview() {
    PaperTheme {
        PaperBackground()
    }
}

@Preview
@Composable
private fun PaperGrainPreview() {
    PaperTheme {
        Box(
            modifier =
                Modifier
                    .size(160.dp)
                    .background(Palette.squareLight)
                    .paperGrain(PaperGrain.BOARD_OPACITY),
        )
    }
}

@Preview(widthDp = 320, heightDp = 240)
@Composable
private fun WashiPanelPreview() {
    PaperTheme {
        PaperBackground(contentAlignment = Alignment.Center) {
            WashiPanel(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
                Text(text = "Player One", modifier = Modifier.padding(20.dp))
            }
            WashiPanel(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                fill = Palette.panelTint(PlayerSeat.Top),
                isAccented = true,
            ) {
                Text(text = "Player Two", modifier = Modifier.padding(20.dp))
            }
        }
    }
}
