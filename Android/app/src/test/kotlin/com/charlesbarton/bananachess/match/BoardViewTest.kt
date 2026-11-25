package com.charlesbarton.bananachess.match

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A phone wide enough for the whole board, at a density that is not one pixel to the dp.
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class BoardViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = TestScope()
    private val playerTwoAtTheBottom = MatchMode.ByLink(matchId = "match", localPlayer = PlayerId.Two)

    // region Showing a board

    private var shown by mutableStateOf<MatchUiState?>(null)
    private var hasContent = false
    private val taps = mutableListOf<Position>()

    /** A board that follows [viewModel] and sends its taps back, as the Match screen will. */
    private fun play(viewModel: MatchViewModel) {
        composeRule.setContent {
            val ui by viewModel.state.collectAsState()
            Frame { BoardView(state = ui, onSquareTap = viewModel::tap, modifier = Modifier.size(BOARD)) }
        }
    }

    /** A board frozen on [ui], recording its taps. Showing another state updates the same board. */
    private fun show(
        ui: MatchUiState,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        shown = ui
        if (hasContent) return
        hasContent = true
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Frame {
                    shown?.let { state ->
                        BoardView(state = state, onSquareTap = { taps += it }, modifier = Modifier.size(BOARD))
                    }
                }
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        PaperTheme {
            // Room for the border and for a flash that reaches above the board's edge.
            Box(modifier = Modifier.testTag(FRAME).padding(MARGIN)) { content() }
        }
    }

    private fun node(position: Position) = composeRule.onNodeWithTag("square_${position.row}_${position.column}")

    private fun description(position: Position): String =
        node(position)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    private fun bounds(position: Position): Rect = node(position).fetchSemanticsNode().boundsInRoot

    private fun capture(): Bitmap =
        composeRule
            .onNodeWithTag(FRAME)
            .captureToImage()
            .asAndroidBitmap()

    private fun Bitmap.colorAtCentre(position: Position): Color {
        val centre = bounds(position).center
        return Color(getPixel(centre.x.roundToInt(), centre.y.roundToInt()))
    }

    private fun isBarePaper(color: Color): Boolean =
        listOf(Palette.squareLight, Palette.squareDark).any { paper ->
            abs(color.red - paper.red) + abs(color.green - paper.green) + abs(color.blue - paper.blue) < 0.03f
        }

    // endregion

    // region Accessibility structure

    @Test
    fun `a flash, a burst or a splatter over the board does not describe the squares again`() {
        var described = 0
        val viewModel = scope.matchViewModel()
        var ui by mutableStateOf(viewModel.ui)
        composeRule.setContent {
            Frame {
                BoardView(
                    state = ui,
                    onSquareTap = {},
                    modifier = Modifier.size(BOARD),
                    describeSquare = { position ->
                        described += 1
                        squareDescription(position)
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(Board.SIZE * Board.SIZE, described)

        ui = ui.copy(flash = BoardFlash(id = 1, text = "+1", position = square(3, 0)))
        composeRule.waitForIdle()
        ui =
            ui.copy(
                blendBursts = listOf(BlendBurst(id = 2, kind = PieceKind.Banana, position = square(3, 0), seed = 1uL)),
            )
        composeRule.waitForIdle()
        ui = ui.copy(splatter = Splatter(id = 3, burstId = 2, kind = PieceKind.Banana, seed = 2uL), flash = null)
        composeRule.waitForIdle()
        assertEquals(Board.SIZE * Board.SIZE, described, "only the effects changed")

        // A selection is something the descriptions say, so it does describe them again.
        viewModel.tap(square(4, 0))
        ui = viewModel.ui
        composeRule.waitForIdle()
        assertEquals(2 * Board.SIZE * Board.SIZE, described)
        assertTrue("legal move" in description(square(3, 0)))
    }

    @Test
    fun `the standard opening has thirty-six labelled buttons, one per square`() {
        val ui = scope.matchViewModel().ui
        show(ui)

        for (position in Board.allPositions) {
            node(position)
                .assertExists()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals(ui.squareDescription(position), description(position))
        }
        assertEquals("Player One banana, row 5, column 1, value 1", description(square(4, 0)))
        assertEquals("Player Two blender, row 1, column 2", description(square(0, 1)))
        assertEquals("Empty square, row 3, column 4", description(square(2, 3)))
    }

    @Test
    fun `pieces, seams, markers and the flash add nothing to the accessibility tree`() {
        // Everything the board can draw at once: a selected piece with legal dots, joined edges,
        // a countdown marker, two buy-back markers and a flash.
        val state =
            GameState(
                pieces =
                    spareFruit +
                        listOf(
                            blender(PlayerId.One, 0, at = null),
                            blender(PlayerId.One, 1, at = square(4, 4)),
                            blender(PlayerId.Two, 0, at = null, respawnTurn = 5),
                            blender(PlayerId.Two, 1, at = square(2, 2)),
                        ),
                turnNumber = 3,
                ruleSet = RuleSet.Wildcard,
                boardWrap = BoardWrap.All,
            )
        val viewModel = scope.matchViewModel(state)
        viewModel.tap(square(4, 4))
        val ui = viewModel.ui.copy(flash = BoardFlash(id = 1, text = "×2", position = square(0, 5)))
        assertTrue(ui.legalDestinations.isNotEmpty())
        assertEquals(2, ui.purchasableSpawns.size)
        assertEquals(1, ui.blendersAwaitingRespawn.size)
        show(ui)

        val frame = composeRule.onNodeWithTag(FRAME, useUnmergedTree = true).fetchSemanticsNode()
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(frame.id), root.children.map { it.id })
        assertEquals(
            Board.allPositions.map { "square_${it.row}_${it.column}" },
            frame.children.map { it.config.getOrNull(SemanticsProperties.TestTag) },
        )
        for (child in frame.children) {
            assertTrue(child.children.isEmpty(), "a square has children: ${child.children.map { it.config }}")
        }
    }

    // endregion

    // region Taps and orientation

    @Test
    fun `tapping a square calls back with its engine position`() {
        show(scope.matchViewModel().ui)

        node(square(4, 0)).performClick()
        node(square(0, 5)).performClick()

        assertEquals(listOf(square(4, 0), square(0, 5)), taps)
        // Row 0, column 0 is drawn at the top left.
        assertTrue(bounds(square(0, 0)).top < bounds(square(5, 0)).top)
        assertTrue(bounds(square(0, 0)).left < bounds(square(0, 5)).left)
    }

    @Test
    fun `a rotated board keeps engine coordinates in its tags and taps`() {
        val ui = scope.matchViewModel(mode = playerTwoAtTheBottom).ui
        assertTrue(ui.isRotated)
        show(ui)

        node(square(4, 0)).performClick()
        node(square(0, 5)).performClick()

        assertEquals(listOf(square(4, 0), square(0, 5)), taps)
        // Row 5, column 5 is now drawn at the top left, and player two's fruit row at the bottom.
        assertTrue(bounds(square(5, 5)).top < bounds(square(0, 5)).top)
        assertTrue(bounds(square(5, 5)).left < bounds(square(5, 0)).left)
        assertEquals(ui.squareDescription(square(1, 0)), description(square(1, 0)))
        assertEquals("You banana, row 2, column 6, value 1", description(square(1, 5)))
    }

    @Test
    fun `the squares tile the board exactly, six by six`() {
        show(scope.matchViewModel().ui)

        val board = BOARD.value * composeRule.density.density
        val cell = board / Board.SIZE
        val origin = bounds(square(0, 0)).topLeft
        for (position in Board.allPositions) {
            val bounds = bounds(position)
            assertEquals(origin.x + position.column * cell, bounds.left, 1f, "$position left")
            assertEquals(origin.y + position.row * cell, bounds.top, 1f, "$position top")
            assertEquals(cell, bounds.width, 1f, "$position width")
            assertEquals(cell, bounds.height, 1f, "$position height")
        }
    }

    @Test
    fun `the board does not mirror in a right-to-left layout`() {
        show(scope.matchViewModel().ui, layoutDirection = LayoutDirection.Rtl)

        assertTrue(bounds(square(0, 0)).left < bounds(square(0, 5)).left)
        val image = capture()
        assertFalse(isBarePaper(image.colorAtCentre(square(5, 1))), "the blender is not on its square")
        assertTrue(isBarePaper(image.colorAtCentre(square(2, 1))))
    }

    // endregion

    // region What the squares say

    @Test
    fun `selecting a fruit marks its destinations as legal moves`() {
        val viewModel = scope.matchViewModel()
        play(viewModel)
        assertFalse("legal move" in description(square(3, 0)))

        node(square(4, 0)).performClick()

        assertEquals(square(4, 0), viewModel.ui.selectedSquare)
        assertEquals("Empty square, row 4, column 1, legal move", description(square(3, 0)))
        assertFalse("legal move" in description(square(3, 1)))
    }

    @Test
    fun `an empty spawn says it is a blender home`() {
        val state = GameState(pieces = spareFruit + blender(PlayerId.One, 0, at = square(3, 3)))
        show(scope.matchViewModel(state).ui)

        assertEquals("Empty square, row 6, column 2, blender home", description(square(5, 1)))
    }

    @Test
    fun `on a Sides board a fruit on the left edge can step to the right edge`() {
        val viewModel = scope.matchViewModel(fruitOnTheLeftEdge(BoardWrap.Sides))
        play(viewModel)

        node(square(3, 0)).performClick()

        assertEquals(square(3, 0), viewModel.ui.selectedSquare)
        assertEquals("Empty square, row 4, column 2, legal move", description(square(3, 1)))
        assertEquals("Empty square, row 4, column 6, legal move", description(square(3, 5)))
    }

    @Test
    fun `on a Walls board the far edge is not a legal move`() {
        val viewModel = scope.matchViewModel(fruitOnTheLeftEdge(BoardWrap.Flat))
        play(viewModel)

        node(square(3, 0)).performClick()

        assertEquals(square(3, 0), viewModel.ui.selectedSquare)
        assertEquals("Empty square, row 4, column 2, legal move", description(square(3, 1)))
        assertEquals("Empty square, row 4, column 6", description(square(3, 5)))
    }

    @Test
    fun `a spawn a destroyed blender can return to says to buy it back`() {
        val state = GameState(pieces = spareFruit + blender(PlayerId.One, 0, at = null))
        show(scope.matchViewModel(state).ui)

        assertTrue("buy it back" in description(square(5, 1)), description(square(5, 1)))
        assertFalse("buy it back" in description(square(0, 1)))
    }

    private fun fruitOnTheLeftEdge(wrap: BoardWrap): GameState =
        GameState(
            pieces = spareFruit + fruit(PlayerId.One, PieceKind.Banana, row = 3, column = 0),
            boardWrap = wrap,
        )

    // endregion

    // region Drawing

    @Test
    fun `every piece is drawn on its own square, whichever way up the board is`() {
        for (mode in listOf(MatchMode.LocalVersus, playerTwoAtTheBottom)) {
            val ui = scope.matchViewModel(mode = mode).ui
            show(ui)
            val image = capture()

            for (piece in ui.piecesOnBoard) {
                val position = checkNotNull(piece.position)
                assertFalse(isBarePaper(image.colorAtCentre(position)), "$mode: no ${piece.id} at $position")
            }
            // The two middle rows are empty and unhatched.
            for (position in Board.allPositions.filter { it.row == 2 || it.row == 3 }) {
                assertTrue(isBarePaper(image.colorAtCentre(position)), "$mode: something is drawn at $position")
            }
        }
    }

    @Test
    fun `a moved piece ends up on its new square`() {
        val viewModel = scope.matchViewModel()
        play(viewModel)

        node(square(4, 0)).performClick()
        node(square(3, 0)).performClick()
        scope.settle()
        composeRule.waitForIdle()

        assertEquals("Player One banana, row 4, column 1, value 1", description(square(3, 0)))
        val image = capture()
        assertFalse(isBarePaper(image.colorAtCentre(square(3, 0))), "the banana did not arrive")
        assertTrue(isBarePaper(image.colorAtCentre(square(4, 0))), "the banana is still on its old square")
    }

    @Test
    fun `a piece slides between squares instead of jumping`() {
        val viewModel = scope.matchViewModel()
        play(viewModel)
        val from = bounds(square(4, 0))
        val to = bounds(square(3, 0))
        val x = from.center.x.roundToInt()
        // A piece's tray spans its whole square, so these two points are covered only while the
        // piece is (all but) at rest on the old square or on the new one.
        val nearTheBottomOfTheOldSquare = (from.bottom - from.height * 0.05f).roundToInt()
        val nearTheTopOfTheNewSquare = (to.top + to.height * 0.05f).roundToInt()
        val betweenTheSquares = from.top.roundToInt()

        composeRule.mainClock.autoAdvance = false
        node(square(4, 0)).performClick()
        node(square(3, 0)).performClick()
        scope.testScheduler.runCurrent()

        var sawItInTransit = false
        repeat(40) {
            composeRule.mainClock.advanceTimeByFrame()
            val image = capture()
            val leftTheOldSquare = isBarePaper(Color(image.getPixel(x, nearTheBottomOfTheOldSquare)))
            val notYetOnTheNewSquare = isBarePaper(Color(image.getPixel(x, nearTheTopOfTheNewSquare)))
            val straddlingTheLine = !isBarePaper(Color(image.getPixel(x, betweenTheSquares)))
            if (leftTheOldSquare && notYetOnTheNewSquare && straddlingTheLine) sawItInTransit = true
        }
        assertTrue(sawItInTransit, "the piece was never seen between its two squares")

        composeRule.mainClock.autoAdvance = true
        scope.settle()
        composeRule.waitForIdle()
        assertFalse(isBarePaper(capture().colorAtCentre(square(3, 0))), "the banana did not arrive")
    }

    @Test
    fun `a flash is drawn beside its square and is not cut off at the board's edge`() {
        val ui = scope.matchViewModel().ui
        show(ui.copy(flash = BoardFlash(id = 1, text = "×2", position = square(0, 2))))

        val density = composeRule.density.density
        val cell = bounds(square(0, 2))
        // Inside the capsule, above its text, and above the top edge of the board.
        val x = cell.left + cell.width * FlashPillSpec.OFFSET_X + 12 * density
        val y = cell.top + cell.height * FlashPillSpec.OFFSET_Y + 3 * density
        assertTrue(y < cell.top)
        assertEquals(Palette.cream, Color(capture().getPixel(x.roundToInt(), y.roundToInt())))
    }

    @Test
    fun `a long flash on the right-hand column is shifted left to end at the board's edge`() {
        val battle = "Battle!  12 v 11  (rolled 6 and 5)"
        // The top right square as each player sees it.
        val cases = listOf(MatchMode.LocalVersus to square(0, 5), playerTwoAtTheBottom to square(5, 0))
        for ((index, case) in cases.withIndex()) {
            val (mode, position) = case
            val ui = scope.matchViewModel(mode = mode).ui
            show(ui.copy(flash = BoardFlash(id = index + 1L, text = battle, position = position)))
            composeRule.waitForIdle()

            val density = composeRule.density.density
            val cell = bounds(position)
            val image = capture()
            // In the capsule's top padding, which is above the board, so the paper there is the pill's.
            val y = (cell.top + cell.height * FlashPillSpec.OFFSET_Y + 3 * density).roundToInt()
            assertTrue(y < cell.top)
            val justInside = Color(image.getPixel((cell.right - 14 * density).roundToInt(), y))
            val justOutside = Color(image.getPixel((cell.right + 10 * density).roundToInt(), y))
            assertEquals(Palette.cream, justInside, "the pill does not reach the board's right edge for $mode")
            assertNotEquals(Palette.cream, justOutside, "the pill runs past the board's right edge for $mode")
        }
    }

    @Test
    fun `a flash rests beside its square unless that would take it past the board`() {
        val cell = 60f
        val board = 360f

        // Room to spare: half a square in from the square's left edge, as on iOS.
        assertEquals(30f, FlashPillSpec.restingLeft(displayedColumn = 0, cell, pillWidth = 200f, boardWidth = board))
        assertEquals(150f, FlashPillSpec.restingLeft(displayedColumn = 2, cell, pillWidth = 200f, boardWidth = board))
        // No room: it ends at the board's right edge.
        assertEquals(160f, FlashPillSpec.restingLeft(displayedColumn = 3, cell, pillWidth = 200f, boardWidth = board))
        assertEquals(160f, FlashPillSpec.restingLeft(displayedColumn = 5, cell, pillWidth = 200f, boardWidth = board))
        // Wider than the board: it starts at the left edge.
        assertEquals(0f, FlashPillSpec.restingLeft(displayedColumn = 5, cell, pillWidth = 400f, boardWidth = board))
    }

    @Test
    fun `joined edges are marked with amber seams, and walls are not`() {
        fun edgePixels(wrap: BoardWrap): Pair<Boolean, Boolean> {
            val state = GameState(pieces = spareFruit, boardWrap = wrap)
            show(scope.matchViewModel(state).ui)
            val image = capture()
            val density = composeRule.density.density
            val inset = (BoardViewSpec.seamInset.value * density).roundToInt()
            val left = bounds(square(2, 0))
            val top = bounds(square(0, 2))
            // A dashed line: look along it for any amber-tinted pixel.
            val side =
                (left.top.toInt() until left.bottom.toInt()).any {
                    isAmberTinted(Color(image.getPixel(left.left.roundToInt() + inset, it)))
                }
            val end =
                (top.left.toInt() until top.right.toInt()).any {
                    isAmberTinted(Color(image.getPixel(it, top.top.roundToInt() + inset)))
                }
            return side to end
        }

        assertEquals(false to false, edgePixels(BoardWrap.Flat))
        assertEquals(true to false, edgePixels(BoardWrap.Sides))
        assertEquals(true to true, edgePixels(BoardWrap.All))
    }

    // Amber over paper is far yellower than any paper, hatch or border colour on the board.
    private fun isAmberTinted(color: Color): Boolean = color.red - color.blue > 0.25f

    // endregion

    @Test
    fun `a half turn maps every square onto another and back`() {
        for (position in Board.allPositions) {
            assertEquals(position, position.displayed(isRotated = false))
            val turned = position.displayed(isRotated = true)
            assertEquals(Position(5 - position.row, 5 - position.column), turned)
            assertEquals(position, turned.displayed(isRotated = true))
        }
    }

    private companion object {
        const val FRAME = "frame"
        val BOARD = 360.dp
        val MARGIN = 24.dp
    }
}
