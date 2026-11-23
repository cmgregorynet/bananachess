package com.charlesbarton.bananachess.match

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.rules.RulesEngine

/** The marker's proportions, as fractions of the square it sits in. */
internal object DestroyedBlenderMarkerSpec {
    const val DIAMETER = 0.82f
    const val GHOST_PADDING = 0.16f
    const val PURCHASABLE_GHOST_ALPHA = 0.4f
    const val WAITING_GHOST_ALPHA = 0.28f

    /** The buy-back price as it is printed on the marker. */
    val priceText: String = "−${RulesEngine.BLENDER_RESTORE_COST}"
}

/**
 * The gap a destroyed blender left, drawn on the spawn it can be bought back to. Amber when its
 * owner can buy it back this turn.
 *
 * Decorative, with no semantics: the label and the number are painted, not laid out as text, so
 * the square underneath stays the only thing a screen reader finds.
 *
 * @param turnsUntilReturn set only for a smoothie blackout, which ends on its own.
 */
@Composable
fun DestroyedBlenderMarker(
    seat: PlayerSeat,
    isPurchasable: Boolean,
    modifier: Modifier = Modifier,
    turnsUntilReturn: Int? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier =
            modifier.drawWithCache {
                val side = size.minDimension
                val radius = side * DestroyedBlenderMarkerSpec.DIAMETER / 2f
                val ringWidth = side * 0.04f
                val ring =
                    Stroke(
                        width = ringWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(side * 0.09f, side * 0.07f)),
                    )
                val ringColor = if (isPurchasable) Palette.amber else Palette.trayShade(seat).copy(alpha = 0.65f)
                val label =
                    when {
                        turnsUntilReturn != null -> {
                            textMeasurer.measure(
                                text = turnsUntilReturn.toString(),
                                style =
                                    TextStyle(
                                        color = Palette.ink.copy(alpha = 0.85f),
                                        fontSize = (side * 0.38f).toSp(),
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Serif,
                                    ),
                            )
                        }

                        isPurchasable -> {
                            textMeasurer.measure(
                                text = DestroyedBlenderMarkerSpec.priceText,
                                style =
                                    TextStyle(
                                        color = Palette.ink,
                                        fontSize = (side * 0.28f).toSp(),
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                    ),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                val showsPricePill = turnsUntilReturn == null && isPurchasable
                onDrawWithContent {
                    drawCircle(color = Palette.tray(seat).copy(alpha = 0.28f), radius = radius)
                    drawContent()
                    drawCircle(color = ringColor, radius = radius - ringWidth / 2f, style = ring)
                    if (label != null) {
                        val labelSize = Size(label.size.width.toFloat(), label.size.height.toFloat())
                        val topLeft = Offset(center.x - labelSize.width / 2f, center.y - labelSize.height / 2f)
                        if (showsPricePill) {
                            val padding = side * 0.08f
                            drawRoundRect(
                                color = Palette.cream.copy(alpha = 0.9f),
                                topLeft = Offset(topLeft.x - padding, topLeft.y),
                                size = Size(labelSize.width + padding * 2f, labelSize.height),
                                cornerRadius = CornerRadius(labelSize.height / 2f),
                            )
                        }
                        drawText(label, topLeft = topLeft)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The ghost of the blender that is missing, so a destroyed one never reads as an
        // ordinary vacated spawn.
        PieceArtwork(
            kind = PieceKind.Blender,
            seat = seat,
            modifier =
                Modifier
                    .fillMaxSize(
                        DestroyedBlenderMarkerSpec.DIAMETER - DestroyedBlenderMarkerSpec.GHOST_PADDING * 2f,
                    ).ghosted(
                        if (isPurchasable) {
                            DestroyedBlenderMarkerSpec.PURCHASABLE_GHOST_ALPHA
                        } else {
                            DestroyedBlenderMarkerSpec.WAITING_GHOST_ALPHA
                        },
                    ),
            showsTray = false,
        )
    }
}

// Greyscale and faded in the draw phase, where nothing can reach the accessibility tree.
private fun Modifier.ghosted(alpha: Float): Modifier =
    drawWithCache {
        val paint =
            Paint().apply {
                this.alpha = alpha
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(size.toRect(), paint)
                drawContent()
                canvas.restore()
            }
        }
    }

@Preview
@Composable
private fun DestroyedBlenderMarkerPreview() {
    Row {
        DestroyedBlenderMarker(seat = PlayerSeat.Bottom, isPurchasable = true, modifier = Modifier.size(64.dp))
        DestroyedBlenderMarker(
            seat = PlayerSeat.Top,
            isPurchasable = false,
            modifier = Modifier.size(64.dp),
            turnsUntilReturn = 2,
        )
        DestroyedBlenderMarker(seat = PlayerSeat.Top, isPurchasable = false, modifier = Modifier.size(64.dp))
    }
}
