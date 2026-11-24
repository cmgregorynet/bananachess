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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.BlendBurstView
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.art.Pulped
import com.charlesbarton.bananachess.art.isReducedMotion
import com.charlesbarton.bananachess.art.paperGrain
import com.charlesbarton.bananachess.art.pulped
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.coroutines.delay
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
 * @param onBurstPlaced called with a burst's id and the centre of its square in root coordinates
 *   whenever it is laid out, so a splatter knows where on screen its fruit was standing.
 * @param onBurstFinished called with a burst's id once [BlendBurst.LIFETIME_MILLIS] have passed.
 * @param describeSquare what TalkBack says for a square. Asked only when something it reads has
 *   changed; a test may count the calls.
 */
@Composable
fun BoardView(
    state: MatchUiState,
    onSquareTap: (Position) -> Unit,
    modifier: Modifier = Modifier,
    onBurstPlaced: (id: Long, centreInRoot: Offset) -> Unit = { _, _ -> },
    onBurstFinished: (id: Long) -> Unit = {},
    describeSquare: MatchUiState.(Position) -> String = MatchUiState::squareDescription,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { isReducedMotion(context) }
    // Each layer is handed only what it draws, worked out once per change to what it reads, so a
    // flash or a burst passing over the board recomposes the effects and nothing under them.
    val display = state.displayState
    val isRotated = state.isRotated
    val squares =
        remember(display, state.mode, state.selectedPieceId, state.legalDestinations) { state.squareLooks() }
    val descriptions = rememberSquareDescriptions(state, describeSquare)
    val markers = remember(display, state.mode) { state.blenderMarkers() }
    val pieces = remember(display, state.mode) { state.pieceLooks() }
    // A chess board does not mirror for right-to-left languages: column 0 is always on the same
    // side for both players.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier.aspectRatio(1f).boardBorder()) {
            Box(modifier = Modifier.fillMaxSize().clipToBoard()) {
                BoardGround(modifier = Modifier.fillMaxSize())
                Squares(
                    rows = squares,
                    descriptions = descriptions,
                    onSquareTap = onSquareTap,
                    modifier = Modifier.fillMaxSize(),
                )
                JoinedEdges(wrap = display.boardWrap, modifier = Modifier.fillMaxSize())
                KnockedOutBlenders(markers = markers, isRotated = isRotated)
                Pieces(onBoard = pieces, selectedPieceId = state.selectedPieceId, reduceMotion = reduceMotion)
            }
            // Outside the clip, so pulp and a label on an edge square are not sliced off by the
            // border.
            Bursts(
                bursts = state.blendBursts,
                isRotated = isRotated,
                reduceMotion = reduceMotion,
                onBurstPlaced = onBurstPlaced,
                onBurstFinished = onBurstFinished,
            )
            FlashPill(flash = state.flash, isRotated = isRotated, modifier = Modifier.fillMaxSize())
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

/** One square as the board draws it. */
private data class SquareLook(
    val position: Position,
    val isBlenderHome: Boolean,
    val deliveryEdge: PlayerSeat?,
    val isLegalDestination: Boolean,
    val isSelectedOrigin: Boolean,
)

/** The squares row by row as they are displayed, top left first. */
private fun MatchUiState.squareLooks(): List<List<SquareLook>> =
    (0 until Board.SIZE).map { displayRow ->
        (0 until Board.SIZE).map { displayColumn ->
            val position = Position(displayRow, displayColumn).displayed(isRotated)
            SquareLook(
                position = position,
                isBlenderHome = position in blenderHomes,
                deliveryEdge = deliveryEdge(position),
                isLegalDestination = position in legalDestinations,
                isSelectedOrigin = selectedSquare == position,
            )
        }
    }

/**
 * What TalkBack says for each square, keyed by engine position. Worked out again only when
 * something [describe] reads changes, never for a flash, a burst or a splatter: each description
 * asks the rules what is legal.
 */
@Composable
internal fun rememberSquareDescriptions(
    state: MatchUiState,
    describe: MatchUiState.(Position) -> String = MatchUiState::squareDescription,
): Map<Position, String> =
    // Everything squareDescription reads, and nothing else.
    remember(
        state.displayState,
        state.authoritativeState,
        state.mode,
        state.selectedPieceId,
        state.legalDestinations,
        state.isInputLocked,
    ) { Board.allPositions.associateWith { state.describe(it) } }

/**
 * Real layout rather than offsets, so each square's accessibility frame is where the square is.
 * Pieces are positioned by offset, since they need to travel, but nothing tappable is.
 */
@Composable
private fun Squares(
    rows: List<List<SquareLook>>,
    descriptions: Map<Position, String>,
    onSquareTap: (Position) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (row in rows) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (square in row) {
                    val position = square.position
                    SquareView(
                        isDark = position.isDarkSquare,
                        isBlenderHome = square.isBlenderHome,
                        deliveryEdge = square.deliveryEdge,
                        isLegalDestination = square.isLegalDestination,
                        isSelectedOrigin = square.isSelectedOrigin,
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
private data class BlenderMarker(
    /** Which marker this is from one board to the next, so it fades in only once. */
    val key: Any,
    val square: Position,
    val seat: PlayerSeat,
    val isPurchasable: Boolean,
    val turnsUntilReturn: Int? = null,
)

private fun MatchUiState.blenderMarkers(): List<BlenderMarker> {
    val countdowns =
        blendersAwaitingRespawn.mapNotNull { blender ->
            blender.home?.let { home ->
                BlenderMarker(
                    key = "respawn" to blender.id,
                    square = home,
                    seat = seat(blender.owner),
                    isPurchasable = false,
                    turnsUntilReturn = displayState.turnsUntilRespawn(blender),
                )
            }
        }
    // Two lost blenders are each offered on both spawns; one marker per spawn is enough.
    val prices =
        purchasableSpawns.distinct().map { spawn ->
            BlenderMarker(
                key = "buy-back" to spawn,
                square = spawn,
                seat = seat(displayState.activePlayer),
                isPurchasable = true,
            )
        }
    return countdowns + prices
}

@Composable
private fun KnockedOutBlenders(
    markers: List<BlenderMarker>,
    isRotated: Boolean,
) {
    for (marker in markers) {
        key(marker.key) {
            FadingIn(modifier = Modifier.boardCell(marker.square, isRotated)) {
                DestroyedBlenderMarker(
                    seat = marker.seat,
                    isPurchasable = marker.isPurchasable,
                    modifier = Modifier.fillMaxSize(),
                    turnsUntilReturn = marker.turnsUntilReturn,
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

/** How a piece is drawn on the board, kept so it can still be drawn while it leaves. */
private data class PieceLook(
    val kind: PieceKind,
    val seat: PlayerSeat,
    val isGolden: Boolean,
    val cell: Offset,
)

/**
 * What the board last showed and what is still on its way out. Changed only after a composition
 * is applied, so a composition that is thrown away changes nothing.
 */
private class PieceMemory {
    var shown: Map<PieceId, PieceLook>? = null
    val leaving = mutableStateMapOf<PieceId, PieceLook>()
}

private fun MatchUiState.pieceLooks(): Map<PieceId, PieceLook> =
    piecesOnBoard
        .mapNotNull { piece ->
            piece.position?.let { position ->
                piece.id to
                    PieceLook(
                        kind = piece.kind,
                        seat = seat(piece.owner),
                        isGolden = displayState.isGolden(piece.id),
                        cell = position.displayed(isRotated).cellOffset,
                    )
            }
        }.toMap()

@Composable
private fun Pieces(
    onBoard: Map<PieceId, PieceLook>,
    selectedPieceId: PieceId?,
    reduceMotion: Boolean,
) {
    val memory = remember { PieceMemory() }
    val shown = memory.shown
    val departed = shown.orEmpty().filterKeys { it !in onBoard }
    val leaving = memory.leaving.filterKeys { it !in onBoard } + departed
    SideEffect {
        memory.shown = onBoard
        for ((id, look) in departed) if (memory.leaving[id] != look) memory.leaving[id] = look
        for (id in memory.leaving.keys.filter { it in onBoard }) memory.leaving.remove(id)
    }

    for ((id, look) in onBoard + leaving) {
        // Keyed by the piece, not the square, so a move slides the same drawing along and a
        // piece that leaves is spun off by the same drawing that stood there.
        key(id) {
            BoardPiece(
                look = look,
                isSelected = selectedPieceId == id,
                // Nothing animates in the first time the board is shown.
                arrives = shown != null && id !in shown,
                isLeaving = id in leaving,
                reduceMotion = reduceMotion,
                onGone = { memory.leaving.remove(id) },
            )
        }
    }
}

/**
 * One piece, sliding to its cell whenever that changes, growing in when it [arrives] and pulped
 * off when it [isLeaving]. Not clickable and with no semantics: the square underneath owns the
 * tap and the description.
 */
@Composable
private fun BoardPiece(
    look: PieceLook,
    isSelected: Boolean,
    arrives: Boolean,
    isLeaving: Boolean,
    reduceMotion: Boolean,
    onGone: () -> Unit,
) {
    val cell = look.cell
    val presence = remember { Animatable(if (arrives) 0f else 1f) }
    LaunchedEffect(isLeaving) {
        if (isLeaving) {
            presence.animateTo(0f, Pulped.exitSpec(look.kind, reduceMotion))
            onGone()
        } else {
            presence.animateTo(1f, Pulped.enterSpec(reduceMotion))
        }
    }
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
        kind = look.kind,
        seat = look.seat,
        modifier =
            Modifier
                .boardCell { place.value }
                .pulped(presence = { presence.value }, isLeaving = isLeaving, reduceMotion = reduceMotion)
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
        isGolden = look.isGolden,
    )
}

/**
 * Pulp thrown at each square where something was just blended, drawn over the board and past
 * its edge. Each burst tells the view model when it is done; it would be retired anyway.
 */
@Composable
private fun Bursts(
    bursts: List<BlendBurst>,
    isRotated: Boolean,
    reduceMotion: Boolean,
    onBurstPlaced: (id: Long, centreInRoot: Offset) -> Unit,
    onBurstFinished: (id: Long) -> Unit,
) {
    for (burst in bursts) {
        key(burst.id) {
            val finished by rememberUpdatedState(onBurstFinished)
            LaunchedEffect(Unit) {
                delay(BlendBurst.LIFETIME_MILLIS)
                finished(burst.id)
            }
            BlendBurstView(
                kind = burst.kind,
                seed = burst.seed,
                reduceMotion = reduceMotion,
                modifier =
                    Modifier
                        .boardCell(burst.position, isRotated)
                        .onGloballyPositioned { onBurstPlaced(burst.id, it.boundsInRoot().center) },
            )
        }
    }
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
