package com.charlesbarton.bananachess.art

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path

/**
 * Hand-authored Bézier silhouettes in a unit square, the same numbers as the iOS drawings.
 * Deliberately a little off-round: brush and pencil imperfection, not perfect geometry.
 *
 * Every builder lays its path out inside [Rect] `bounds`; the default is the unit square itself.
 */
object PieceShapes {
    val UnitBounds = Rect(0f, 0f, 1f, 1f)

    /**
     * A crescent, not an oval: tips low-left and high-right with the belly sagging away from the
     * curve. Drawn as two arcs rather than a closed blob, or it reads as a lemon.
     */
    fun banana(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.11f, 0.70f)
            // Outer edge, sweeping up and over to the far tip.
            curve(0.87f, 0.26f, 0.19f, 0.24f, 0.57f, 0.13f)
            // The little stem.
            curve(0.92f, 0.20f, 0.90f, 0.23f, 0.92f, 0.21f)
            curve(0.86f, 0.34f, 0.90f, 0.28f, 0.88f, 0.32f)
            // Inner edge, bowing the same way as the outer one but less: mirror it and the
            // shape closes into a leaf instead of a crescent.
            curve(0.11f, 0.70f, 0.62f, 0.34f, 0.30f, 0.55f)
            close()
        }

    fun strawberry(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.50f, 0.93f)
            curve(0.12f, 0.42f, 0.25f, 0.82f, 0.10f, 0.64f)
            curve(0.50f, 0.24f, 0.14f, 0.24f, 0.29f, 0.21f)
            curve(0.88f, 0.42f, 0.71f, 0.21f, 0.86f, 0.24f)
            curve(0.50f, 0.93f, 0.90f, 0.64f, 0.75f, 0.82f)
            close()
        }

    fun pineapple(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.50f, 0.95f)
            curve(0.17f, 0.60f, 0.27f, 0.93f, 0.15f, 0.80f)
            curve(0.50f, 0.34f, 0.19f, 0.42f, 0.31f, 0.34f)
            curve(0.83f, 0.60f, 0.69f, 0.34f, 0.81f, 0.42f)
            curve(0.50f, 0.95f, 0.85f, 0.80f, 0.73f, 0.93f)
            close()
        }

    /** Three leaves, the middle one tallest, all leaning slightly the same way. */
    fun pineappleCrown(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.44f, 0.36f)
            curve(0.28f, 0.10f, 0.38f, 0.26f, 0.30f, 0.17f)
            curve(0.46f, 0.32f, 0.36f, 0.22f, 0.44f, 0.26f)
            close()

            move(0.48f, 0.36f)
            curve(0.53f, 0.03f, 0.47f, 0.22f, 0.50f, 0.10f)
            curve(0.58f, 0.33f, 0.57f, 0.12f, 0.59f, 0.22f)
            close()

            move(0.58f, 0.35f)
            curve(0.74f, 0.12f, 0.62f, 0.27f, 0.70f, 0.18f)
            curve(0.60f, 0.37f, 0.70f, 0.24f, 0.64f, 0.30f)
            close()
        }

    fun strawberryCalyx(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.50f, 0.30f)
            curve(0.20f, 0.16f, 0.38f, 0.24f, 0.27f, 0.16f)
            curve(0.50f, 0.30f, 0.32f, 0.24f, 0.42f, 0.29f)
            close()

            move(0.50f, 0.30f)
            curve(0.80f, 0.16f, 0.62f, 0.24f, 0.73f, 0.16f)
            curve(0.50f, 0.30f, 0.68f, 0.24f, 0.58f, 0.29f)
            close()

            move(0.50f, 0.30f)
            curve(0.52f, 0.06f, 0.46f, 0.20f, 0.48f, 0.10f)
            curve(0.50f, 0.30f, 0.58f, 0.12f, 0.56f, 0.22f)
            close()
        }

    fun blenderJar(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.22f, 0.26f)
            curve(0.31f, 0.76f, 0.23f, 0.50f, 0.29f, 0.68f)
            curve(0.69f, 0.76f, 0.36f, 0.80f, 0.64f, 0.80f)
            curve(0.78f, 0.26f, 0.71f, 0.68f, 0.77f, 0.50f)
            // The pouring lip, leaning left the way a hand-drawn one would.
            curve(0.22f, 0.26f, 0.60f, 0.22f, 0.38f, 0.22f)
            close()
        }

    fun blenderBase(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.28f, 0.76f)
            curve(0.72f, 0.76f, 0.42f, 0.74f, 0.58f, 0.74f)
            curve(0.76f, 0.92f, 0.75f, 0.82f, 0.77f, 0.88f)
            curve(0.24f, 0.92f, 0.62f, 0.96f, 0.38f, 0.96f)
            curve(0.28f, 0.76f, 0.23f, 0.88f, 0.25f, 0.82f)
            close()
        }

    fun blenderLid(bounds: Rect = UnitBounds): Path =
        unitPath(bounds) {
            move(0.19f, 0.24f)
            curve(0.81f, 0.24f, 0.36f, 0.19f, 0.64f, 0.19f)
            curve(0.19f, 0.24f, 0.66f, 0.30f, 0.34f, 0.30f)
            close()
        }

    /** All eight builders by name, for tests and previews that walk every shape. */
    internal val all: List<Pair<String, (Rect) -> Path>> =
        listOf(
            "banana" to ::banana,
            "strawberry" to ::strawberry,
            "pineapple" to ::pineapple,
            "pineappleCrown" to ::pineappleCrown,
            "strawberryCalyx" to ::strawberryCalyx,
            "blenderJar" to ::blenderJar,
            "blenderBase" to ::blenderBase,
            "blenderLid" to ::blenderLid,
        )
}

private inline fun unitPath(
    bounds: Rect,
    build: UnitPathScope.() -> Unit,
): Path = Path().also { UnitPathScope(it, bounds).build() }

/** Unit coordinates in the argument order of the iOS source: the end point first, then the two controls. */
private class UnitPathScope(
    private val path: Path,
    private val bounds: Rect,
) {
    fun move(
        x: Float,
        y: Float,
    ) = path.moveTo(px(x), py(y))

    fun curve(
        x: Float,
        y: Float,
        c1x: Float,
        c1y: Float,
        c2x: Float,
        c2y: Float,
    ) = path.cubicTo(px(c1x), py(c1y), px(c2x), py(c2y), px(x), py(y))

    fun close() = path.close()

    private fun px(x: Float): Float = bounds.left + x * bounds.width

    private fun py(y: Float): Float = bounds.top + y * bounds.height
}
