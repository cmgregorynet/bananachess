package com.charlesbarton.bananachess.match

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PlayerSeat

/**
 * One square: two weights of the same warm paper, a marked blender home that stays visible when
 * vacated, and the amber legal-move dot.
 *
 * It only draws. The board gives it its tap, its tag and its spoken description.
 */
@Composable
fun SquareView(
    isDark: Boolean,
    isBlenderHome: Boolean,
    deliveryEdge: PlayerSeat?,
    isLegalDestination: Boolean,
    isSelectedOrigin: Boolean,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier.drawBehind {
                drawRect(if (isDark) Palette.squareDark else Palette.squareLight)
                if (deliveryEdge != null) drawDeliveryHatch(deliveryEdge)
                drawSquareBorder()
                if (isBlenderHome) drawBlenderHome()
                if (isSelectedOrigin) drawRect(Palette.amber.copy(alpha = 0.16f))
                if (isLegalDestination) drawLegalMoveDot()
            },
    )
}

/**
 * Soft pencil hatching along a scoring edge, warm for the bottom seat and cool for the top one,
 * so the two delivery rows read differently without relying on colour.
 */
private fun DrawScope.drawDeliveryHatch(seat: PlayerSeat) {
    val color = (if (seat == PlayerSeat.Bottom) Palette.sand else Palette.fogWater).copy(alpha = 0.75f)
    val width = (if (seat == PlayerSeat.Bottom) 1.2.dp else 0.8.dp).toPx()
    val step = 7.dp.toPx()
    clipRect {
        var offset = -size.height
        while (offset < size.width) {
            drawLine(
                color = color,
                start = Offset(offset, size.height),
                end = Offset(offset + size.height, 0f),
                strokeWidth = width,
            )
            offset += step
        }
    }
}

private fun DrawScope.drawSquareBorder() {
    val width = 0.7.dp.toPx()
    drawRect(
        color = Palette.ink.copy(alpha = 0.18f),
        topLeft = Offset(width / 2f, width / 2f),
        size = Size(size.width - width, size.height - width),
        style = Stroke(width = width),
    )
}

private fun DrawScope.drawBlenderHome() {
    val width = 1.4.dp.toPx()
    val dash = 3.dp.toPx()
    drawCircle(
        color = Palette.ink.copy(alpha = 0.45f),
        radius = size.minDimension / 2f - 6.dp.toPx() - width / 2f,
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

private fun DrawScope.drawLegalMoveDot() {
    val radius = 6.dp.toPx()
    val ring = 1.dp.toPx()
    drawCircle(color = Palette.amber.copy(alpha = 0.85f), radius = radius)
    drawCircle(color = Palette.ink.copy(alpha = 0.4f), radius = radius - ring / 2f, style = Stroke(width = ring))
}

@Preview
@Composable
private fun SquareViewPreview() {
    Row {
        SquareView(
            isDark = true,
            isBlenderHome = true,
            deliveryEdge = PlayerSeat.Bottom,
            isLegalDestination = false,
            isSelectedOrigin = false,
            modifier = Modifier.size(56.dp),
        )
        SquareView(
            isDark = false,
            isBlenderHome = false,
            deliveryEdge = PlayerSeat.Top,
            isLegalDestination = true,
            isSelectedOrigin = false,
            modifier = Modifier.size(56.dp),
        )
        SquareView(
            isDark = true,
            isBlenderHome = false,
            deliveryEdge = null,
            isLegalDestination = false,
            isSelectedOrigin = true,
            modifier = Modifier.size(56.dp),
        )
    }
}
