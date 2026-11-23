package com.charlesbarton.bananachess.art

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A watercolour wash filling its own bounds: a soft gradient body, a darker pool bleeding to one
 * edge, and a pencil outline. Decorative, so it has no semantics.
 */
@Composable
fun WatercolorWash(
    shape: Shape,
    tint: Color,
    modifier: Modifier = Modifier,
    outline: Color = Palette.ink,
    lineWidth: Dp = WatercolorWashDefaults.LineWidth,
) {
    Canvas(modifier = modifier) {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@Canvas)) }
        drawWatercolorWash(path, tint, outline, lineWidth)
    }
}

/**
 * The same wash for a [path] already laid out in this scope's pixels, for drawings that stack
 * several washes in one canvas. The gradient runs across the whole scope, not the path's bounds,
 * so every part of one drawing is lit from the same corner.
 */
fun DrawScope.drawWatercolorWash(
    path: Path,
    tint: Color,
    outline: Color = Palette.ink,
    lineWidth: Dp = WatercolorWashDefaults.LineWidth,
) {
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val body =
        Brush.linearGradient(
            colors = listOf(tint.scaledAlpha(0.72f), tint, tint.scaledAlpha(0.92f)),
            start = Offset(if (isRtl) size.width else 0f, 0f),
            end = Offset(if (isRtl) 0f else size.width, size.height),
        )
    drawPath(path, body)

    // The pooled edge, where the pigment settled. A hardware canvas too old to honour mask
    // filters leaves a crisper pool rather than a missing one.
    val pool =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = tint.scaledAlpha(0.55f).toArgb()
            maskFilter = BlurMaskFilter(WatercolorWashDefaults.PoolBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
    val nativePath = path.asAndroidPath()
    clipPath(path) {
        translate(WatercolorWashDefaults.PoolOffsetX.toPx(), WatercolorWashDefaults.PoolOffsetY.toPx()) {
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(nativePath, pool) }
        }
    }

    drawPath(
        path = path,
        color = outline.scaledAlpha(0.85f),
        style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

object WatercolorWashDefaults {
    val LineWidth = 1.6.dp
    internal val PoolBlur = 2.2.dp
    internal val PoolOffsetX = 1.2.dp
    internal val PoolOffsetY = 2.dp
}

private fun Color.scaledAlpha(factor: Float): Color = copy(alpha = alpha * factor)

@Preview
@Composable
private fun WatercolorWashPreview() {
    PaperTheme {
        PaperBackground {
            Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WatercolorWash(shape = CircleShape, tint = Palette.bananaWash, modifier = Modifier.size(64.dp))
                WatercolorWash(
                    shape = RoundedCornerShape(14.dp),
                    tint = Palette.strawberryWash,
                    modifier = Modifier.size(64.dp),
                )
                WatercolorWash(shape = CircleShape, tint = Palette.blenderGlass, modifier = Modifier.size(64.dp))
            }
        }
    }
}
