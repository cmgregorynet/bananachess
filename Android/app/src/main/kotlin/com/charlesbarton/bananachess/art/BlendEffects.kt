package com.charlesbarton.bananachess.art

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.random.SplitMix64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// region Droplets

/**
 * One thrown blob of pulp. Laid out once from a seed, so a burst keeps the same shape for the
 * whole of its own animation instead of reshuffling itself every frame.
 *
 * @property angle radians, measured the usual screen way: y grows downward.
 * @property distance how far it travels, as a fraction of the view it is thrown across.
 * @property size blob diameter, in the same units as [distance].
 */
data class Droplet(
    val id: Int,
    val angle: Double,
    val distance: Double,
    val size: Double,
    val spin: Double,
    val shapeSeed: ULong,
) {
    companion object {
        const val BURST_COUNT = 11
        const val SPLATTER_COUNT = 34

        /** The tight spray at the square where a fruit was blended. */
        fun burst(
            seed: ULong,
            count: Int = BURST_COUNT,
        ): List<Droplet> {
            val generator = SplitMix64(seed)
            return List(count) { index ->
                // Spread evenly around the circle, then jitter, so no burst ends up with a bald
                // patch on one side.
                val base = index.toDouble() / count * 2 * PI
                val angle = base + generator.nextDouble(-0.30, 0.30)
                val distance = generator.nextDouble(0.55, 1.15)
                val size = generator.nextDouble(0.13, 0.30)
                val spin = generator.nextDouble(-1.2, 1.2)
                Droplet(index, angle, distance, size, spin, shapeSeed = generator.next())
            }
        }

        /** The wider spray that occasionally reaches the screen. */
        fun splatter(
            seed: ULong,
            count: Int = SPLATTER_COUNT,
        ): List<Droplet> {
            val generator = SplitMix64(seed)
            return List(count) { index ->
                val base = index.toDouble() / count * 2 * PI
                val angle = base + generator.nextDouble(-0.45, 0.45)
                // Skewed so most of the pulp stays near the impact and a few blobs carry all the
                // way out: even spacing reads as a printed pattern.
                val distance = generator.nextDouble(0.05, 1.0).pow(1.7) * 0.95
                val size = generator.nextDouble(0.015, 0.085)
                val spin = generator.nextDouble(-2.0, 2.0)
                Droplet(index, angle, distance, size, spin, shapeSeed = generator.next())
            }
        }
    }
}

/**
 * A uniform value in `from..to`, both ends included: Swift's `Double.random(in:using:)` draw for
 * draw, so a seed throws the same pulp on both platforms.
 */
internal fun SplitMix64.nextDouble(
    from: Double,
    to: Double,
): Double {
    val maxSignificand = 1uL shl SIGNIFICAND_BITS
    val draw = nextBelow(maxSignificand + 1uL)
    if (draw == maxSignificand) return to
    return (to - from) * (draw.toDouble() * UNIT_STEP) + from
}

private const val SIGNIFICAND_BITS = 53
private val UNIT_STEP = 1.0 / (1uL shl SIGNIFICAND_BITS).toDouble()

/**
 * A closed blob with a wobbling edge, a splash of watercolour rather than a circle, filling
 * [bounds]. [lobes] points at a seeded fraction of the radius, joined by curves through their
 * midpoints with each point as the control: the edge stays smooth but never lands on a true arc.
 */
internal fun dropletPath(
    seed: ULong,
    bounds: Rect,
    lobes: Int = DROPLET_LOBES,
): Path {
    val generator = SplitMix64(seed)
    val centre = bounds.center
    val radius = bounds.minDimension / 2.0
    val points =
        List(lobes) { index ->
            val angle = index.toDouble() / lobes * 2 * PI
            val wobble = generator.nextDouble(0.58, 1.0)
            Offset(
                (centre.x + cos(angle) * radius * wobble).toFloat(),
                (centre.y + sin(angle) * radius * wobble).toFloat(),
            )
        }
    val path = Path()
    if (points.size <= 2) return path

    fun midpoint(
        a: Offset,
        b: Offset,
    ) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val start = midpoint(points.last(), points.first())
    path.moveTo(start.x, start.y)
    for (index in points.indices) {
        val current = points[index]
        val end = midpoint(current, points[(index + 1) % points.size])
        path.quadraticTo(current.x, current.y, end.x, end.y)
    }
    path.close()
    return path
}

internal const val DROPLET_LOBES = 9

/** Every droplet's blob, one unit across and centred on the origin, ready to be scaled into place. */
private fun unitBlobs(droplets: List<Droplet>): List<Path> =
    droplets.map { dropletPath(it.shapeSeed, Rect(-0.5f, -0.5f, 0.5f, 0.5f)) }

// endregion

// region Curves

/** A spring from 0 to 1 starting at rest, as SwiftUI's `Spring(duration:bounce:)` moves. */
private fun springProgress(
    seconds: Float,
    response: Double,
    bounce: Double,
): Float {
    if (seconds <= 0f) return 0f
    val damping = 1 - bounce
    val omega = 2 * PI / response
    val decay = damping * omega
    val damped = omega * sqrt(1 - damping * damping)
    val t = seconds.toDouble()
    return (1 - exp(-decay * t) * (cos(damped * t) + decay / damped * sin(damped * t))).toFloat()
}

/** Linear from [from] at [start] to [to] at [end], holding either end outside that span. */
private fun ramp(
    seconds: Float,
    start: Float,
    end: Float,
    from: Float,
    to: Float,
): Float {
    val fraction = ((seconds - start) / (end - start)).coerceIn(0f, 1f)
    return from + (to - from) * fraction
}

/**
 * The burst's three tracks, by seconds since it appeared.
 *
 * `spread` is SwiftUI's `.bouncy` spring (response 0.5 s, bounce 0.3) for 0.55 s; `pulp` rises
 * in 0.06 s, holds 0.16 s and fades over 0.36 s; `ring` eases to 1 over 0.5 s. Under reduced
 * motion the pulp stays where the fruit was and simply fades.
 */
internal object BlendBurstCurves {
    const val DURATION_SECONDS = 0.58f

    private const val SPREAD_SECONDS = 0.55f
    private const val RING_SECONDS = 0.5f

    fun spread(
        seconds: Float,
        reduceMotion: Boolean,
    ): Float =
        when {
            reduceMotion -> 0f
            seconds >= SPREAD_SECONDS -> 1f
            else -> springProgress(seconds, response = 0.5, bounce = 0.3)
        }

    fun pulp(seconds: Float): Float =
        if (seconds < 0.06f) ramp(seconds, 0f, 0.06f, 0f, 1f) else ramp(seconds, 0.22f, 0.58f, 1f, 0f)

    fun ring(seconds: Float): Float {
        val t = (seconds / RING_SECONDS).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The splatter's two tracks, by seconds since it appeared.
 *
 * `reach` is SwiftUI's `.snappy` spring (response 0.5 s, bounce 0.15) cut off at 0.26 s, then
 * carried on to 1 over 1.2 s; under reduced motion there is no throw and the stain is simply
 * already there. `ink` rises to 0.5 in 0.14 s (0.3 s reduced), holds 0.42 s and fades over 0.9 s.
 */
internal object SplatterCurves {
    private const val THROW_SECONDS = 0.26f
    private const val SETTLE_SECONDS = 1.2f
    private const val INK = 0.5f

    fun reach(
        seconds: Float,
        reduceMotion: Boolean,
    ): Float {
        if (reduceMotion) return 1f
        if (seconds < THROW_SECONDS) return springProgress(seconds, response = 0.5, bounce = 0.15)
        val thrown = springProgress(THROW_SECONDS, response = 0.5, bounce = 0.15)
        return ramp(seconds, THROW_SECONDS, THROW_SECONDS + SETTLE_SECONDS, thrown, 1f)
    }

    fun ink(
        seconds: Float,
        reduceMotion: Boolean,
    ): Float {
        val rise = if (reduceMotion) 0.30f else 0.14f
        val hold = rise + 0.42f
        return if (seconds < rise) ramp(seconds, 0f, rise, 0f, INK) else ramp(seconds, hold, hold + 0.9f, INK, 0f)
    }

    fun duration(reduceMotion: Boolean): Float = (if (reduceMotion) 0.30f else 0.14f) + 0.42f + 0.9f
}

/**
 * Seconds since this first composed, ticking once a frame until [limit]. Counted off the frame
 * clock rather than an animation, so turning animations off in the system settings does not
 * finish it at once: the reduced-motion fade still has to be seen.
 */
@Composable
private fun rememberSecondsSinceAppearing(limit: Float): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (seconds.floatValue < limit) {
            withFrameNanos { now -> seconds.floatValue = ((now - start) / 1e9f).coerceAtMost(limit) }
        }
    }
    return seconds
}

// endregion

// region The burst at the square

/**
 * What a fruit does when a blender gets it: a ring pushing outward and eleven blobs thrown clear
 * of the square.
 *
 * Give it the square's bounds; the pulp is drawn well past them, so nothing around it may clip.
 * Purely decorative: it takes no touches and has no semantics. It draws once through its
 * animation and then nothing; whoever placed it removes it.
 */
@Composable
fun BlendBurstView(
    kind: PieceKind,
    seed: ULong,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val seconds = rememberSecondsSinceAppearing(BlendBurstCurves.DURATION_SECONDS)
    val droplets = remember(seed) { Droplet.burst(seed) }
    val blobs = remember(droplets) { unitBlobs(droplets) }
    val tint = Palette.wash(kind)
    Spacer(
        modifier =
            modifier.drawWithCache {
                val side = size.minDimension
                val centre = size.center
                val glow = blurredDisc(tint.copy(alpha = 0.4f), radius = side / 2f, sigma = side * 0.09f)
                onDrawBehind {
                    // Read here, in the draw phase, so each frame repaints the burst and nothing else.
                    val t = seconds.value
                    val ring = BlendBurstCurves.ring(t)
                    val spread = BlendBurstCurves.spread(t, reduceMotion)
                    val pulp = BlendBurstCurves.pulp(t)

                    val ringScale = 0.2f + ring * 1.35f
                    drawCircle(
                        color = tint.copy(alpha = 0.5f),
                        radius = side / 2f * ringScale,
                        center = centre,
                        alpha = (1f - ring) * 0.85f,
                        style = Stroke(width = side * 0.09f * ringScale),
                    )
                    scale(0.3f + ring * 0.8f, pivot = centre) {
                        drawCircle(brush = glow.first, radius = glow.second, center = centre, alpha = 1f - ring)
                    }
                    if (pulp > 0f) {
                        // SwiftUI scales each blob after offsetting it, so the shrink pulls the
                        // throw in as well.
                        val shrink = 1f - 0.5f * spread
                        droplets.forEachIndexed { index, droplet ->
                            val travel = side * droplet.distance.toFloat() * spread * shrink
                            drawBlob(
                                blob = blobs[index],
                                at =
                                    centre +
                                        Offset(
                                            cos(droplet.angle).toFloat() * travel,
                                            sin(droplet.angle).toFloat() * travel,
                                        ),
                                diameter = side * droplet.size.toFloat() * shrink,
                                radians = droplet.spin.toFloat() * spread,
                                color = tint,
                                alpha = pulp,
                            )
                        }
                    }
                }
            },
    )
}

private fun DrawScope.drawBlob(
    blob: Path,
    at: Offset,
    diameter: Float,
    radians: Float,
    color: Color,
    alpha: Float = 1f,
) {
    withTransform({
        translate(at.x, at.y)
        rotate(degrees = radians * DEGREES_PER_RADIAN, pivot = Offset.Zero)
        scale(diameter, diameter, pivot = Offset.Zero)
    }) {
        drawPath(blob, color = color, alpha = alpha)
    }
}

private const val DEGREES_PER_RADIAN = (180.0 / PI).toFloat()
private const val DISC_STOPS = 24
private const val DISC_REACH_SIGMAS = 3f

// A blurred disc as a radial gradient rather than a blur filter, as the golden halo does: the
// same look in one draw, whatever the device does with mask filters.
private fun blurredDisc(
    color: Color,
    radius: Float,
    sigma: Float,
): Pair<Brush, Float> {
    val reach = radius + sigma * DISC_REACH_SIGMAS
    val stops =
        Array(DISC_STOPS + 1) { index ->
            val fraction = index / DISC_STOPS.toFloat()
            val coverage = if (index == DISC_STOPS) 0f else GoldenHaloSpec.coverage(reach * fraction, radius, sigma)
            fraction to color.copy(alpha = color.alpha * coverage)
        }
    return Brush.radialGradient(colorStops = stops, radius = reach) to reach
}

// endregion

// region The screen splatter

/**
 * The occasional case where the pulp clears the board entirely: thirty-four blobs thrown across
 * whatever this fills, pigment multiplied into the paper rather than a sticker on glass.
 *
 * Purely decorative: it takes no touches and has no semantics, and nothing here reaches the
 * rules or a link, so two devices watching one match may splatter differently.
 *
 * @param origin where the fruit was, in this view's own coordinates, or null while that is not
 *   known yet; the middle stands in until it is. Read in the draw phase and kept once known,
 *   because the burst that fixed it is gone long before the splatter dries.
 */
@Composable
fun SplatterView(
    kind: PieceKind,
    seed: ULong,
    reduceMotion: Boolean,
    origin: () -> Offset?,
    modifier: Modifier = Modifier,
) {
    val seconds = rememberSecondsSinceAppearing(SplatterCurves.duration(reduceMotion))
    val droplets = remember(seed) { Droplet.splatter(seed) }
    val blobs = remember(droplets) { unitBlobs(droplets) }
    val lockedOrigin = remember { arrayOfNulls<Offset>(1) }
    val tint = Palette.wash(kind)
    Spacer(
        modifier =
            modifier.drawWithCache {
                val layer = Paint().apply { blendMode = BlendMode.Multiply }
                onDrawBehind {
                    val t = seconds.value
                    val ink = SplatterCurves.ink(t, reduceMotion)
                    if (ink <= 0f) return@onDrawBehind
                    val reach = SplatterCurves.reach(t, reduceMotion)
                    val anchor = lockedOrigin[0] ?: origin()?.also { lockedOrigin[0] = it } ?: size.center
                    val width = size.width
                    layer.alpha = ink
                    drawContext.canvas.saveLayer(size.toRect(), layer)
                    droplets.forEachIndexed { index, droplet ->
                        // Both axes are measured against the width, as on iOS.
                        val travel = droplet.distance.toFloat() * width * reach
                        drawBlob(
                            blob = blobs[index],
                            at =
                                anchor +
                                    Offset(
                                        cos(droplet.angle).toFloat() * travel,
                                        sin(droplet.angle).toFloat() * travel,
                                    ),
                            diameter = width * droplet.size.toFloat(),
                            radians = droplet.spin.toFloat(),
                            color = tint,
                        )
                    }
                    drawContext.canvas.restore()
                }
            },
    )
}

// endregion

// region The piece leaving the board

/**
 * How a piece comes and goes. A blended fruit does not simply vanish: it is wrung out and spun
 * off, shrinking to 0.22 and turning 150° as it fades. A piece arriving grows in from 0.4. Under
 * reduced motion both are calm: a fade with a slight scale from 0.7, and no spin.
 */
object Pulped {
    private const val LEAVING_SCALE = 0.22f
    private const val LEAVING_DEGREES = 150f
    private const val ARRIVING_SCALE = 0.4f
    private const val CALM_SCALE = 0.7f

    // iOS removes fruit on an ease-out of 0.28 s and blenders on one of 0.22 s, brings pieces
    // back on a spring of response 0.5 s and damping 0.62, and does everything in 0.12 s under
    // Reduce Motion.
    private const val FRUIT_EXIT_MILLIS = 280
    private const val BLENDER_EXIT_MILLIS = 220
    private const val CALM_MILLIS = 120
    private const val ENTER_RESPONSE_SECONDS = 0.5
    private const val ENTER_DAMPING = 0.62f

    fun exitSpec(
        kind: PieceKind,
        reduceMotion: Boolean,
    ): AnimationSpec<Float> =
        when {
            reduceMotion -> tween(CALM_MILLIS, easing = EaseInOut)
            kind == PieceKind.Blender -> tween(BLENDER_EXIT_MILLIS, easing = EaseOut)
            else -> tween(FRUIT_EXIT_MILLIS, easing = EaseOut)
        }

    fun enterSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) {
            tween(CALM_MILLIS, easing = EaseInOut)
        } else {
            spring(
                dampingRatio = ENTER_DAMPING,
                stiffness = (2 * PI / ENTER_RESPONSE_SECONDS).let { it * it }.toFloat(),
                visibilityThreshold = 0.002f,
            )
        }

    /** Scale, rotation in degrees and alpha at [presence], from 0 (gone) to 1 (fully there). */
    fun look(
        presence: Float,
        isLeaving: Boolean,
        reduceMotion: Boolean,
    ): Triple<Float, Float, Float> {
        val alpha = presence.coerceIn(0f, 1f)
        return when {
            reduceMotion -> {
                Triple(CALM_SCALE + (1f - CALM_SCALE) * presence, 0f, alpha)
            }

            isLeaving -> {
                val progress = 1f - presence
                Triple(1f - (1f - LEAVING_SCALE) * progress, LEAVING_DEGREES * progress, alpha)
            }

            else -> {
                Triple(ARRIVING_SCALE + (1f - ARRIVING_SCALE) * presence, 0f, alpha)
            }
        }
    }
}

/**
 * Draws this element as [Pulped] says for [presence], read in the draw phase so a transition
 * repaints rather than recomposes. Drawn rather than given a graphics layer: the fade must not
 * clip a golden halo to the piece's bounds, and nothing here may add a semantics node.
 */
fun Modifier.pulped(
    presence: () -> Float,
    isLeaving: Boolean,
    reduceMotion: Boolean,
): Modifier =
    drawWithContent {
        val now = presence()
        if (now >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (now <= 0f) return@drawWithContent
        val (scale, degrees, alpha) = Pulped.look(now, isLeaving, reduceMotion)
        // Room for the halo, which reaches past the piece.
        val bounds = size.toRect().inflate(size.minDimension)
        withTransform({
            scale(scale, scale, pivot = size.center)
            rotate(degrees, pivot = size.center)
        }) {
            drawContext.canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
            this@drawWithContent.drawContent()
            drawContext.canvas.restore()
        }
    }

// endregion

@Preview(widthDp = 320, heightDp = 320)
@Composable
private fun BlendEffectsPreview() {
    PaperTheme {
        PaperBackground {
            SplatterView(
                kind = PieceKind.Strawberry,
                seed = 42uL,
                reduceMotion = false,
                origin = { null },
                modifier = Modifier.fillMaxSize(),
            )
            BlendBurstView(
                kind = PieceKind.Banana,
                seed = 7uL,
                reduceMotion = false,
                modifier = Modifier.padding(128.dp).size(64.dp),
            )
        }
    }
}
