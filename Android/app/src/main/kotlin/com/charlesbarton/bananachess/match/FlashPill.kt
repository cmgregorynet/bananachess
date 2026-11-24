package com.charlesbarton.bananachess.match

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.engine.model.Board

/** Where the pill sits and how it arrives. Offsets are fractions of one square. */
internal object FlashPillSpec {
    const val OFFSET_X = 0.5f
    const val OFFSET_Y = -0.15f
    const val FADE_MILLIS = 250
    val rise = 8.dp
    val horizontalPadding = 8.dp
    val verticalPadding = 4.dp
    val borderWidth = 1.5.dp

    /**
     * Where the pill's left edge rests. Beside its square by default, but a long label on the
     * right-hand columns is shifted left until it ends at the board's edge: there is no room
     * beyond it, only the edge of the screen. A label wider than the whole board starts at the
     * board's left edge.
     */
    fun restingLeft(
        displayedColumn: Int,
        cell: Float,
        pillWidth: Float,
        boardWidth: Float,
    ): Float = ((displayedColumn + OFFSET_X) * cell).coerceAtMost(boardWidth - pillWidth).coerceAtLeast(0f)

    // The iOS caption style in the platform serif.
    val textStyle =
        TextStyle(
            color = Palette.ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
}

/**
 * The short label that floats beside a square: small serif text in a cream capsule with an amber
 * border, fading in as it rises into place and fading out the same way.
 *
 * Give it the board's own bounds; it works out the square from them. It is painted, not laid out
 * as text, so it adds nothing to the accessibility tree and never takes a tap. It stays inside
 * the board's width but may draw above its top edge, so nothing above it should clip.
 */
@Composable
fun FlashPill(
    flash: BoardFlash?,
    isRotated: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // The last flash stays drawn while it fades out.
    var shown by remember { mutableStateOf(flash) }
    val visibility = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash != null) shown = flash
        visibility.animateTo(
            targetValue = if (flash != null) 1f else 0f,
            animationSpec = tween(durationMillis = FlashPillSpec.FADE_MILLIS, easing = EaseOut),
        )
    }

    Spacer(
        modifier =
            modifier.drawWithCache {
                val current = shown
                if (current == null) {
                    onDrawBehind {}
                } else {
                    val text = textMeasurer.measure(text = current.text, style = FlashPillSpec.textStyle)
                    val horizontalPadding = FlashPillSpec.horizontalPadding.toPx()
                    val verticalPadding = FlashPillSpec.verticalPadding.toPx()
                    val border = FlashPillSpec.borderWidth.toPx()
                    val rise = FlashPillSpec.rise.toPx()
                    val pill = Size(text.size.width + horizontalPadding * 2f, text.size.height + verticalPadding * 2f)
                    val cell = size.minDimension / Board.SIZE
                    val square = current.position.displayed(isRotated)
                    val restingTopLeft =
                        Offset(
                            x = FlashPillSpec.restingLeft(square.column, cell, pill.width, size.width),
                            y = (square.row + FlashPillSpec.OFFSET_Y) * cell,
                        )
                    onDrawBehind {
                        // Read here, in the draw phase, so the fade repaints the pill and nothing else.
                        val visible = visibility.value
                        if (visible > 0f) {
                            val topLeft = restingTopLeft + Offset(0f, (1f - visible) * rise)
                            drawRoundRect(
                                color = Palette.cream,
                                topLeft = topLeft,
                                size = pill,
                                cornerRadius = CornerRadius(pill.height / 2f),
                                alpha = visible,
                            )
                            drawRoundRect(
                                color = Palette.amber,
                                topLeft = topLeft + Offset(border / 2f, border / 2f),
                                size = Size(pill.width - border, pill.height - border),
                                cornerRadius = CornerRadius((pill.height - border) / 2f),
                                alpha = visible,
                                style = Stroke(width = border),
                            )
                            drawText(
                                textLayoutResult = text,
                                topLeft = topLeft + Offset(horizontalPadding, verticalPadding),
                                alpha = visible,
                            )
                        }
                    }
                }
            },
    )
}
