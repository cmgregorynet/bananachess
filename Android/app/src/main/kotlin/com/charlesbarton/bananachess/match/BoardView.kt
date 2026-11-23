package com.charlesbarton.bananachess.match

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.art.paperGrain
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** The board's fixed measurements. */
internal object BoardViewSpec {
    val cornerRadius = 6.dp

    // The printed border from the shin-hanga half of the bible.
    val borderCornerRadius = 10.dp
    val borderWidth = 2.dp
    val borderOutset = 6.dp
    const val BORDER_ALPHA = 0.7f

    const val GROUND_GRAIN = 0.4f

    val seamWidth = 2.4.dp
    val seamDash = 5.dp
    val seamInset = 1.2.dp
    const val SEAM_ALPHA = 0.55f

    const val SELECTED_SCALE = 1.08f
    const val SELECTION_RING_DIAMETER = 0.94f
    val selectionRingWidth = 3.dp

    const val MARKER_FADE_MILLIS = 200

    // iOS moves a piece on a spring of response 0.34 s and damping 0.78; for unit mass the
    // stiffness is (2π / response)².
    private const val MOVE_RESPONSE_SECONDS = 0.34
    const val MOVE_DAMPING = 0.78f
    val moveStiffness: Float = (2 * PI / MOVE_RESPONSE_SECONDS).let { it * it }.toFloat()

    fun squareTag(position: Position): String = "square_${position.row}_${position.column}"
}

/**
 * Where a square is drawn, or which square is drawn at a place: a half turn when the local player
 * sits at the bottom as player two, so each device sees its own side nearest to it. The turn is
 * its own inverse, so one function answers both questions.
 */
internal fun Position.displayed(isRotated: Boolean): Position =
    if (isRotated) Position(Board.SIZE - 1 - row, Board.SIZE - 1 - column) else this

/**
 * The painted 6×6 board. Squares below, pieces floating above them so a piece can travel between
 * squares rather than blinking from one to the next.
 *
 * It is always square. The printed border is drawn [BoardViewSpec.borderOutset] outside its
 * bounds and a flash can reach past its edge, so leave room around it and do not clip it.
 *
 * @param onSquareTap called with the square's engine position, whichever way up the board is.
 */
@Composable
fun BoardView(
    state: MatchUiState,
    onSquareTap: (Position) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A chess board does not mirror for right-to-left languages: column 0 is always on the same
    // side for both players.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier.aspectRatio(1f).boardBorder()) {
            Box(modifier = Modifier.fillMaxSize().clipToBoard()) {
                BoardGround(modifier = Modifier.fillMaxSize())
                Squares(state = state, onSquareTap = onSquareTap, modifier = Modifier.fillMaxSize())
                JoinedEdges(wrap = state.displayState.boardWrap, modifier = Modifier.fillMaxSize())
                KnockedOutBlenders(state = state)
                Pieces(state = state)
            }
            // Outside the clip, so a label on an edge square is not sliced off by the border.
            // Anything else that must spill past the board (pulp from a blend) belongs here too,
            // placed on its square with Modifier.boardCell.
            FlashPill(flash = state.flash, isRotated = state.isRotated, modifier = Modifier.fillMaxSize())
        }
    }
}

// region Frame

private fun Modifier.boardBorder(): Modifier =
    drawBehind {
        val width = BoardViewSpec.borderWidth.toPx()
        // The stroke sits wholly inside a rectangle outset from the board.
        val outset = BoardViewSpec.borderOutset.toPx() - width / 2f
        drawRoundRect(
            color = Palette.ink.copy(alpha = BoardViewSpec.BORDER_ALPHA),
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + outset * 2f, size.height + outset * 2f),
            cornerRadius = CornerRadius(BoardViewSpec.borderCornerRadius.toPx() - width / 2f),
            style = Stroke(width = width),
        )
    }

// Clipped in the draw phase rather than with Modifier.clip, which also publishes the shape as a
// semantics node; the thirty-six squares are the only nodes the board has.
private fun Modifier.clipToBoard(): Modifier =
    drawWithCache {
        val outline =
            Path().apply {
                addRoundRect(RoundRect(size.toRect(), CornerRadius(BoardViewSpec.cornerRadius.toPx())))
            }
        onDrawWithContent { clipPath(outline) { this@onDrawWithContent.drawContent() } }
    }

@Composable
private fun BoardGround(modifier: Modifier = Modifier) {
    Spacer(
        modifier =
            modifier
                .paperGrain(BoardViewSpec.GROUND_GRAIN)
                .drawBehind { drawRect(Palette.squareLight) },
    )
}

/**
 * Marks the edges that join up.
 *
 * Without this a legal-move dot appears on the far side of the board with nothing to explain it.
 * A wall is drawn solid; a joined edge is drawn as a soft dashed seam, the visual convention for
 * "this continues elsewhere". Drawn over the squares, which would otherwise cover it.
 */
@Composable
private fun JoinedEdges(
    wrap: BoardWrap,
    modifier: Modifier = Modifier,
) {
    if (wrap == BoardWrap.Flat) return
    Spacer(
        modifier =
            modifier.drawBehind {
                val dash = BoardViewSpec.seamDash.toPx()
                val inset = BoardViewSpec.seamInset.toPx()
                val color = Palette.amber.copy(alpha = BoardViewSpec.SEAM_ALPHA)
                val stroke =
                    Stroke(
                        width = BoardViewSpec.seamWidth.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                    )
                val seams =
                    Path().apply {
                        if (wrap.wrapsColumns) {
                            for (x in listOf(inset, size.width - inset)) {
                                moveTo(x, 0f)
                                lineTo(x, size.height)
                            }
                        }
                        if (wrap.wrapsRows) {
                            for (y in listOf(inset, size.height - inset)) {
                                moveTo(0f, y)
                                lineTo(size.width, y)
                            }
                        }
                    }
                drawPath(path = seams, color = color, style = stroke)
            },
    )
}

// endregion

// region Squares

/**
 * Real layout rather than offsets, so each square's accessibility frame is where the square is.
 * Pieces are positioned by offset, since they need to travel, but nothing tappable is.
 */
@Composable
private fun Squares(
    state: MatchUiState,
    onSquareTap: (Position) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Worked out once per state, not once per recomposition: each one asks the rules what is legal.
    val descriptions = remember(state) { Board.allPositions.associateWith(state::squareDescription) }
    Column(modifier = modifier) {
        for (displayRow in 0 until Board.SIZE) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (displayColumn in 0 until Board.SIZE) {
                    val position = Position(displayRow, displayColumn).displayed(state.isRotated)
                    SquareView(
                        isDark = position.isDarkSquare,
                        isBlenderHome = position in state.blenderHomes,
                        deliveryEdge = state.deliveryEdge(position),
                        isLegalDestination = position in state.legalDestinations,
                        isSelectedOrigin = state.selectedSquare == position,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(BoardViewSpec.squareTag(position))
                                // No ripple: the board is paper, and selection is shown in amber.
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onSquareTap(position) },
                                ).semantics { contentDescription = descriptions.getValue(position) },
                    )
                }
            }
        }
    }
}

// endregion

// region Things standing on squares

/**
 * Sizes this element to one square of the board that contains it and places it at [cell], given
 * in squares from the top left as it is displayed (x is the column, y the row). [cell] is read
 * during placement, so an animated value moves the element without recomposing it.
 *
 * This is real placement, not a drawing offset: the element's bounds on screen are its square's.
 */
internal fun Modifier.boardCell(cell: () -> Offset): Modifier =
    layout { measurable, constraints ->
        val side = min(constraints.maxWidth, constraints.maxHeight) / Board.SIZE.toFloat()
        val placeable = measurable.measure(Constraints.fixed(ceil(side).toInt(), ceil(side).toInt()))
        layout(placeable.width, placeable.height) {
            val at = cell()
            placeable.place((at.x * side).roundToInt(), (at.y * side).roundToInt())
        }
    }

/** Places this element on the square where [position] is displayed. */
internal fun Modifier.boardCell(
    position: Position,
    isRotated: Boolean,
): Modifier = boardCell { position.displayed(isRotated).cellOffset }

private val Position.cellOffset: Offset get() = Offset(column.toFloat(), row.toFloat())

/**
 * What a missing blender leaves behind. A smoothie victim shows a countdown on its own spawn; a
 * combat loss shows a price on every spawn its owner could buy it back onto, since either one
 * will do.
 */
@Composable
private fun KnockedOutBlenders(state: MatchUiState) {
    for (blender in state.blendersAwaitingRespawn) {
        val home = blender.home
        if (home != null) {
            key("respawn", blender.id) {
                FadingIn(modifier = Modifier.boardCell(home, state.isRotated)) {
                    DestroyedBlenderMarker(
                        seat = state.seat(blender.owner),
                        isPurchasable = false,
                        modifier = Modifier.fillMaxSize(),
                        turnsUntilReturn = state.displayState.turnsUntilRespawn(blender),
                    )
                }
            }
        }
    }
    // Two lost blenders are each offered on both spawns; one marker per spawn is enough.
    val purchasableSpawns = remember(state) { state.purchasableSpawns.distinct() }
    for (spawn in purchasableSpawns) {
        key("buy-back", spawn) {
            FadingIn(modifier = Modifier.boardCell(spawn, state.isRotated)) {
                DestroyedBlenderMarker(
                    seat = state.seat(state.displayState.activePlayer),
                    isPurchasable = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FadingIn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(durationMillis = BoardViewSpec.MARKER_FADE_MILLIS)) }
    Box(
        modifier =
            modifier.drawWithContent {
                val visible = alpha.value
                if (visible >= 1f) {
                    drawContent()
                } else if (visible > 0f) {
                    drawContext.canvas.saveLayer(size.toRect(), fadePaint(visible))
                    drawContent()
                    drawContext.canvas.restore()
                }
            },
    ) {
        content()
    }
}

private fun fadePaint(alpha: Float) = Paint().apply { this.alpha = alpha }

@Composable
private fun Pieces(state: MatchUiState) {
    for (piece in state.piecesOnBoard) {
        val position = piece.position
        if (position != null) {
            // Keyed by the piece, not the square, so a move slides the same drawing along.
            key(piece.id) {
                BoardPiece(
                    kind = piece.kind,
                    seat = state.seat(piece.owner),
                    isGolden = state.displayState.isGolden(piece.id),
                    isSelected = state.selectedPieceId == piece.id,
                    cell = position.displayed(state.isRotated).cellOffset,
                )
            }
        }
    }
}

/**
 * One piece, sliding to [cell] whenever that changes. Not clickable and with no semantics: the
 * square underneath owns the tap and the description.
 */
@Composable
private fun BoardPiece(
    kind: PieceKind,
    seat: PlayerSeat,
    isGolden: Boolean,
    isSelected: Boolean,
    cell: Offset,
) {
    val place = remember { Animatable(cell, Offset.VectorConverter) }
    // With animations turned off in the system settings the spring finishes at once, which is
    // the reduced-motion behaviour wanted here: the piece is simply on its new square.
    LaunchedEffect(cell) {
        place.animateTo(
            targetValue = cell,
            animationSpec =
                spring(
                    dampingRatio = BoardViewSpec.MOVE_DAMPING,
                    stiffness = BoardViewSpec.moveStiffness,
                    visibilityThreshold = Offset(0.002f, 0.002f),
                ),
        )
    }
    PieceArtwork(
        kind = kind,
        seat = seat,
        modifier =
            Modifier
                .boardCell { place.value }
                .drawWithContent {
                    scale(if (isSelected) BoardViewSpec.SELECTED_SCALE else 1f) {
                        this@drawWithContent.drawContent()
                    }
                    if (isSelected) {
                        val width = BoardViewSpec.selectionRingWidth.toPx()
                        drawCircle(
                            color = Palette.amber,
                            radius = size.minDimension * BoardViewSpec.SELECTION_RING_DIAMETER / 2f - width / 2f,
                            style = Stroke(width = width),
                        )
                    }
                },
        isGolden = isGolden,
    )
}

// endregion

@Preview(widthDp = 360, heightDp = 360)
@Composable
private fun BoardViewPreview() {
    val state = GameSetup.newGame(boardWrap = BoardWrap.Sides)
    PaperTheme {
        BoardView(
            state = MatchUiState(mode = MatchMode.LocalVersus, displayState = state, authoritativeState = state),
            onSquareTap = {},
            modifier = Modifier.padding(12.dp),
        )
    }
}
