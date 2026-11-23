package com.charlesbarton.bananachess.art

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.engine.model.PieceKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One piece: an ownership tray, then the fruit or blender on top of it. Ownership is carried by
 * the warm or cool tray and by its single or double pencil ring, so it survives colour blindness
 * and greyscale.
 *
 * The drawing is the largest centred square that fits. It is decorative and has no semantics:
 * whatever holds the piece carries the label.
 *
 * @param isGolden the wildcard rules' Golden Pineapple, which glows so it is unmistakable.
 * @param showsTray off for the small tallies beside each score, where a tray turns the fruit into
 * a smudge and ownership is already stated by the panel it sits in.
 */
@Composable
fun PieceArtwork(
    kind: PieceKind,
    seat: PlayerSeat,
    modifier: Modifier = Modifier,
    isGolden: Boolean = false,
    showsTray: Boolean = true,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.aspectRatio(1f)) {
            if (isGolden) GoldenHalo(modifier = Modifier.matchParentSize())
            if (showsTray) Tray(seat = seat, modifier = Modifier.matchParentSize())
            val sprite = ArtCatalog.sprite(kind, seat)
            if (sprite != null) {
                SpriteArtwork(
                    sprite = sprite,
                    padding = PieceArtworkDefaults.spritePadding(showsTray),
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                VectorArtwork(
                    kind = kind,
                    seat = seat,
                    isGolden = isGolden,
                    padding = PieceArtworkDefaults.vectorPadding(showsTray),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** Padding around the drawing, as fractions of the piece's side. */
object PieceArtworkDefaults {
    fun vectorPadding(showsTray: Boolean): Float = if (showsTray) 0.13f else 0.02f

    fun spritePadding(showsTray: Boolean): Float = if (showsTray) 0.10f else 0f
}

// region Ownership tray

/** One pencil ring of a tray, drawn wholly inside the circle. Fractions are of the tray's side. */
internal data class TrayRing(
    val color: Color,
    val width: Float,
    val inset: Float = 0f,
)

/** What a tray is made of, back to front, so the ownership cues can be checked without pixels. */
internal data class TrayLayers(
    val light: Color,
    val shade: Color,
    val border: TrayRing,
    val innerRing: TrayRing?,
    val grainOpacity: Float = PaperGrain.TRAY_OPACITY,
) {
    companion object {
        /** End radius of the fill gradient, as a fraction of the side. */
        const val GRADIENT_RADIUS = 0.9f

        fun of(seat: PlayerSeat): TrayLayers =
            TrayLayers(
                light = Palette.tray(seat),
                shade = Palette.trayShade(seat),
                border = TrayRing(color = Palette.ink.copy(alpha = 0.75f), width = 0.030f),
                // Player two's tray gets a second, inset ring, drawn light so it reads against
                // the dark stone the way the dark ring reads against the board. Ownership never
                // rests on colour alone.
                innerRing =
                    when (seat) {
                        PlayerSeat.Bottom -> {
                            null
                        }

                        PlayerSeat.Top -> {
                            TrayRing(
                                color = Palette.cream.copy(alpha = 0.5f),
                                width = 0.018f,
                                inset = 0.075f,
                            )
                        }
                    },
            )
    }
}

@Composable
private fun Tray(
    seat: PlayerSeat,
    modifier: Modifier = Modifier,
) {
    val layers = TrayLayers.of(seat)
    Spacer(
        modifier =
            modifier
                // Clipped in the draw phase rather than with Modifier.clip, which also publishes
                // the shape as a semantics node; a piece stays out of the accessibility tree.
                .drawWithCache {
                    val circle = Path().apply { addOval(Rect(center = size.center, radius = size.minDimension / 2f)) }
                    onDrawWithContent { clipPath(circle) { this@onDrawWithContent.drawContent() } }
                }.paperGrain(layers.grainOpacity)
                .drawBehind {
                    val side = size.minDimension
                    val topStart = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width else 0f, 0f)
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(layers.light, layers.shade),
                                center = topStart,
                                radius = side * TrayLayers.GRADIENT_RADIUS,
                            ),
                        radius = side / 2f,
                    )
                    drawTrayRing(layers.border, side)
                    layers.innerRing?.let { drawTrayRing(it, side) }
                },
    )
}

private fun DrawScope.drawTrayRing(
    ring: TrayRing,
    side: Float,
) {
    val width = side * ring.width
    drawCircle(
        color = ring.color,
        radius = side / 2f - side * ring.inset - width / 2f,
        style = Stroke(width = width),
    )
}

// endregion

// region Golden halo

/** One blurred amber disc of the halo. [blur] is a fraction of the piece's side, before [scale]. */
internal data class HaloGlow(
    val opacity: Float,
    val blur: Float,
    val scale: Float,
)

/**
 * Lantern glow rather than a ring: the selection ring is already amber, and two amber rings on
 * one square would be a puzzle rather than a signal. It breathes slowly: enough to catch the eye
 * across the board without ever being the loudest thing on screen.
 */
internal object GoldenHaloSpec {
    const val PERIOD_MILLIS = 1800

    val glows =
        listOf(
            HaloGlow(opacity = 0.75f, blur = 0.16f, scale = 1.26f),
            HaloGlow(opacity = 0.4f, blur = 0.05f, scale = 1.08f),
        )

    /** [breath] runs 0 → 1 → 0; a still halo sits at 0. */
    fun scale(breath: Float): Float = 0.97f + (1.06f - 0.97f) * breath

    fun alpha(breath: Float): Float = 0.72f + (1f - 0.72f) * breath

    /**
     * How much of a blurred disc's colour is left at [distance] from its centre, for a disc of
     * [radius] blurred by a Gaussian of [sigma]: 1 well inside, one half on the edge, 0 well
     * outside.
     */
    fun coverage(
        distance: Float,
        radius: Float,
        sigma: Float,
    ): Float = (0.5f * (1f - erf((distance - radius) / (sigma * SQRT_2)))).coerceIn(0f, 1f)

    private val SQRT_2 = sqrt(2f)

    // Abramowitz and Stegun 7.1.26; accurate to 1.5e-7, far below one step of an 8-bit alpha.
    private fun erf(x: Float): Float {
        val t = 1f / (1f + 0.3275911f * abs(x))
        val polynomial =
            ((((1.061405429f * t - 1.453152027f) * t + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t
        val magnitude = 1f - polynomial * exp(-x * x)
        return if (x < 0f) -magnitude else magnitude
    }
}

/** True when the user has turned animations off (animator duration scale 0). */
internal fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun GoldenHalo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reducedMotion = remember(context) { isReducedMotion(context) }
    val breath: State<Float>? = if (reducedMotion) null else rememberBreath()
    Spacer(
        modifier =
            modifier.drawWithCache {
                val side = size.minDimension
                val brushes = GoldenHaloSpec.glows.map { haloBrush(it, side, size.center) }
                onDrawBehind {
                    // Read here, in the draw phase, so a breath repaints the halo and nothing else.
                    val phase = breath?.value ?: 0f
                    scale(GoldenHaloSpec.scale(phase)) {
                        brushes.forEach { (brush, radius) ->
                            drawCircle(brush = brush, radius = radius, alpha = GoldenHaloSpec.alpha(phase))
                        }
                    }
                }
            },
    )
}

@Composable
private fun rememberBreath(): State<Float> =
    rememberInfiniteTransition(label = "goldenHalo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = GoldenHaloSpec.PERIOD_MILLIS, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "breath",
    )

private const val HALO_STOPS = 24
private const val HALO_REACH_SIGMAS = 3f

// The blurred disc as a radial gradient rather than a blur filter: it looks the same, costs one
// draw, and does not depend on the device honouring mask filters or render effects.
private fun haloBrush(
    glow: HaloGlow,
    side: Float,
    center: Offset,
): Pair<Brush, Float> {
    val radius = side / 2f * glow.scale
    val sigma = side * glow.blur * glow.scale
    val reach = radius + sigma * HALO_REACH_SIGMAS
    val stops =
        Array(HALO_STOPS + 1) { index ->
            val fraction = index / HALO_STOPS.toFloat()
            val alpha =
                if (index ==
                    HALO_STOPS
                ) {
                    0f
                } else {
                    glow.opacity * GoldenHaloSpec.coverage(reach * fraction, radius, sigma)
                }
            fraction to Palette.amber.copy(alpha = alpha)
        }
    return Brush.radialGradient(colorStops = stops, center = center, radius = reach) to reach
}

// endregion

// region Painted sprite

@Composable
private fun SpriteArtwork(
    sprite: Painter,
    padding: Float,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier.drawBehind {
                inset(size.minDimension * padding) {
                    val intrinsic = sprite.intrinsicSize
                    val fitted =
                        if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                            intrinsic * min(size.width / intrinsic.width, size.height / intrinsic.height)
                        } else {
                            size
                        }
                    translate((size.width - fitted.width) / 2f, (size.height - fitted.height) / 2f) {
                        with(sprite) { draw(fitted) }
                    }
                }
            },
    )
}

// endregion

// region Vector fallback

@Composable
private fun VectorArtwork(
    kind: PieceKind,
    seat: PlayerSeat,
    isGolden: Boolean,
    padding: Float,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier.drawWithCache {
                val pad = size.minDimension * padding
                val bounds = Rect(0f, 0f, size.width - pad * 2f, size.height - pad * 2f)
                val drawing =
                    when (kind) {
                        PieceKind.Banana -> bananaDrawing(bounds)
                        PieceKind.Strawberry -> strawberryDrawing(bounds)
                        PieceKind.Pineapple -> pineappleDrawing(bounds, isGolden)
                        PieceKind.Blender -> blenderDrawing(bounds, seat)
                    }
                onDrawBehind { inset(pad) { drawing() } }
            },
    )
}

private fun Density.bananaDrawing(bounds: Rect): DrawScope.() -> Unit {
    val banana = PieceShapes.banana(bounds)
    // The bruised inner curve, where the wash pooled: the same crescent at 0.86, pinned to the
    // bottom-left corner.
    val bruise =
        PieceShapes
            .banana(
                Rect(
                    bounds.left,
                    bounds.bottom - bounds.height * 0.86f,
                    bounds.left + bounds.width * 0.86f,
                    bounds.bottom,
                ),
            ).asAndroidPath()
    val bruisePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Palette.sand.copy(alpha = 0.35f).toArgb()
            maskFilter = BlurMaskFilter(2.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
    return {
        drawWatercolorWash(banana, Palette.wash(PieceKind.Banana))
        clipPath(banana) {
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(bruise, bruisePaint) }
        }
    }
}

private val StrawberrySeeds =
    listOf(
        Offset(0.36f, 0.46f),
        Offset(0.58f, 0.44f),
        Offset(0.47f, 0.58f),
        Offset(0.30f, 0.62f),
        Offset(0.66f, 0.60f),
        Offset(0.50f, 0.75f),
    )

private fun strawberryDrawing(bounds: Rect): DrawScope.() -> Unit {
    val body = PieceShapes.strawberry(bounds)
    val calyx = PieceShapes.strawberryCalyx(bounds)
    val seeds =
        Path().apply {
            for (seed in StrawberrySeeds) {
                val left = seed.x * bounds.width - bounds.width * 0.022f
                val top = seed.y * bounds.height - bounds.height * 0.032f
                addOval(Rect(left, top, left + bounds.width * 0.044f, top + bounds.height * 0.064f))
            }
        }
    return {
        drawWatercolorWash(body, Palette.wash(PieceKind.Strawberry))
        clipPath(body) { drawPath(seeds, Palette.cream.copy(alpha = 0.85f)) }
        drawWatercolorWash(calyx, Palette.sageLight, lineWidth = 1.1.dp)
    }
}

private fun pineappleDrawing(
    bounds: Rect,
    isGolden: Boolean,
): DrawScope.() -> Unit {
    val body = PieceShapes.pineapple(bounds)
    val crown = PieceShapes.pineappleCrown(bounds)
    val tint = if (isGolden) Palette.amber else Palette.wash(PieceKind.Pineapple)
    val crosshatch =
        Path().apply {
            val step = bounds.width * 0.16f
            var offset = -bounds.width
            // A degenerate canvas has no step to advance by.
            while (step > 0f && offset < bounds.width * 2f) {
                moveTo(offset, 0f)
                lineTo(offset + bounds.height, bounds.height)
                moveTo(offset, bounds.height)
                lineTo(offset + bounds.height, 0f)
                offset += step
            }
        }
    return {
        drawWatercolorWash(body, tint)
        clipPath(body) {
            drawPath(crosshatch, Palette.ink.copy(alpha = 0.28f), style = Stroke(width = 0.8.dp.toPx()))
        }
        drawWatercolorWash(crown, Palette.sage, lineWidth = 1.1.dp)
    }
}

private fun blenderDrawing(
    bounds: Rect,
    seat: PlayerSeat,
): DrawScope.() -> Unit {
    val base = PieceShapes.blenderBase(bounds)
    val jar = PieceShapes.blenderJar(bounds)
    val lid = PieceShapes.blenderLid(bounds)
    val blade =
        Path().apply {
            moveTo(bounds.width * 0.38f, bounds.height * 0.60f)
            lineTo(bounds.width * 0.62f, bounds.height * 0.68f)
            moveTo(bounds.width * 0.62f, bounds.height * 0.60f)
            lineTo(bounds.width * 0.38f, bounds.height * 0.68f)
        }
    val baseTint = if (seat == PlayerSeat.Bottom) Palette.sand else Palette.slate
    val lidTint = if (seat == PlayerSeat.Bottom) Palette.driftwood else Palette.fogWater
    return {
        drawWatercolorWash(base, baseTint)
        drawWatercolorWash(jar, Palette.wash(PieceKind.Blender).copy(alpha = 0.9f))
        drawPath(blade, Palette.ink.copy(alpha = 0.6f), style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        drawWatercolorWash(lid, lidTint, lineWidth = 1.2.dp)
    }
}

// endregion

@Preview(widthDp = 360, heightDp = 480)
@Composable
internal fun PieceArtworkPreview() {
    PaperTheme {
        PaperBackground {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (showsTray in listOf(true, false)) {
                    for (seat in PlayerSeat.entries) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (kind in PieceKind.entries) {
                                PieceArtwork(
                                    kind = kind,
                                    seat = seat,
                                    modifier = Modifier.size(64.dp),
                                    showsTray = showsTray,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    for (seat in PlayerSeat.entries) {
                        PieceArtwork(
                            kind = PieceKind.Pineapple,
                            seat = seat,
                            modifier = Modifier.size(64.dp),
                            isGolden = true,
                        )
                    }
                }
            }
        }
    }
}
