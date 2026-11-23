package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SquareDescriptionTest {
    @Test
    fun `an empty square gives its row and column counted from one`() =
        runTest {
            val ui = matchViewModel().ui

            assertEquals("Empty square, row 4, column 3", ui.squareDescription(square(3, 2)))
            assertEquals("Empty square, row 6, column 1", ui.squareDescription(square(5, 0)))
        }

    @Test
    fun `a blender is described by its owner`() =
        runTest {
            val ui = matchViewModel().ui

            assertEquals("Player One blender, row 6, column 2", ui.squareDescription(square(5, 1)))
            assertEquals("Player Two blender, row 1, column 5", ui.squareDescription(square(0, 4)))
        }

    @Test
    fun `a fruit is described by its owner, kind and value`() =
        runTest {
            val ui = matchViewModel().ui

            assertEquals("Player One banana, row 5, column 1, value 1", ui.squareDescription(square(4, 0)))
            assertEquals("Player One strawberry, row 5, column 2, value 2", ui.squareDescription(square(4, 1)))
            assertEquals("Player Two pineapple, row 2, column 1, value 3", ui.squareDescription(square(1, 0)))
        }

    @Test
    fun `names follow the match mode`() =
        runTest {
            val ui = matchViewModel(mode = MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.One)).ui

            assertEquals("You banana, row 5, column 1, value 1", ui.squareDescription(square(4, 0)))
            assertEquals("Hard CPU blender, row 1, column 2", ui.squareDescription(square(0, 1)))
        }

    @Test
    fun `a lone pineapple under wildcard rules is golden`() =
        runTest {
            val pieces =
                listOf(
                    fruit(PlayerId.One, PieceKind.Pineapple, row = 4, column = 3),
                    fruit(PlayerId.One, PieceKind.Banana, row = 4, column = 0),
                    fruit(PlayerId.Two, PieceKind.Pineapple, row = 1, column = 2, index = 0),
                    fruit(PlayerId.Two, PieceKind.Pineapple, row = 1, column = 0, index = 1),
                )

            val wildcard = matchViewModel(GameState(pieces = pieces, ruleSet = RuleSet.Wildcard)).ui
            val standard = matchViewModel(GameState(pieces = pieces)).ui

            assertEquals(
                "Player One golden pineapple, row 5, column 4, value 3",
                wildcard.squareDescription(square(4, 3)),
            )
            assertEquals("Player Two pineapple, row 2, column 3, value 3", wildcard.squareDescription(square(1, 2)))
            assertEquals("Player One pineapple, row 5, column 4, value 3", standard.squareDescription(square(4, 3)))
        }

    @Test
    fun `an empty spawn is a blender home, and says when a blender can be bought back onto it`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        spareFruit +
                            listOf(
                                blender(PlayerId.One, 0, at = null),
                                blender(PlayerId.One, 1, at = square(4, 4)),
                                blender(PlayerId.Two, 0, at = square(2, 2)),
                            ),
                )
            val ui = matchViewModel(state).ui

            assertEquals(
                "Empty square, row 6, column 2, blender home, a destroyed blender can come back here, " +
                    "tap to buy it back for 3 points",
                ui.squareDescription(square(5, 1)),
            )
            assertEquals(
                "Empty square, row 6, column 5, blender home, a destroyed blender can come back here, " +
                    "tap to buy it back for 3 points",
                ui.squareDescription(square(5, 4)),
            )
            // Player two's spawn: nothing of theirs is lost, and it is not their turn.
            assertEquals("Empty square, row 1, column 2, blender home", ui.squareDescription(square(0, 1)))
        }

    @Test
    fun `no buy-back is offered while the board is locked`() =
        runTest {
            val state =
                GameState(
                    pieces = spareFruit + listOf(blender(PlayerId.One, 0, at = null)),
                )
            val ui = matchViewModel(state, mode = MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.Two)).ui

            assertEquals("Empty square, row 6, column 2, blender home", ui.squareDescription(square(5, 1)))
        }

    @Test
    fun `a blender a smoothie blew up says when it is due back`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        spareFruit +
                            listOf(
                                blender(PlayerId.Two, 0, at = null, respawnTurn = 5),
                                blender(PlayerId.Two, 1, at = null, respawnTurn = 4),
                            ),
                    turnNumber = 3,
                    ruleSet = RuleSet.Wildcard,
                )
            val ui = matchViewModel(state).ui

            assertEquals(
                "Empty square, row 1, column 2, blender home, Player Two blender blown up by a smoothie, back in 2 turns",
                ui.squareDescription(square(0, 1)),
            )
            assertEquals(
                "Empty square, row 1, column 5, blender home, Player Two blender blown up by a smoothie, back in 1 turn",
                ui.squareDescription(square(0, 4)),
            )
            assertEquals(2, ui.blendersAwaitingRespawn.size)
        }

    @Test
    fun `an empty legal destination says so`() =
        runTest {
            val viewModel = matchViewModel()
            viewModel.tap(square(4, 0))

            assertEquals("Empty square, row 4, column 1, legal move", viewModel.ui.squareDescription(square(3, 0)))
            assertEquals("Empty square, row 4, column 2", viewModel.ui.squareDescription(square(3, 1)))
        }

    @Test
    fun `a legal move onto your own fruit says what it costs`() =
        runTest {
            val viewModel = matchViewModel()
            viewModel.tap(square(5, 1))

            assertEquals(
                "Player One banana, row 5, column 1, value 1, legal move, blends your own banana, minus 1",
                viewModel.ui.squareDescription(square(4, 0)),
            )
        }

    @Test
    fun `a legal move onto the other side's fruit says what it wins`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        spareFruit +
                            listOf(
                                blender(PlayerId.One, 0, at = square(3, 3)),
                                fruit(PlayerId.Two, PieceKind.Strawberry, row = 2, column = 2),
                                blender(PlayerId.Two, 0, at = square(2, 4)),
                            ),
                )
            val viewModel = matchViewModel(state)
            viewModel.tap(square(3, 3))

            assertEquals(
                "Player Two strawberry, row 3, column 3, value 2, legal move, blends for plus 2",
                viewModel.ui.squareDescription(square(2, 2)),
            )
            assertEquals(
                "Player Two blender, row 3, column 5, legal move, knocks both blenders out",
                viewModel.ui.squareDescription(square(2, 4)),
            )
        }

    @Test
    fun `delivery edges belong to the seat whose fruit scores there`() =
        runTest {
            val local = matchViewModel().ui
            val asTwo = matchViewModel(mode = MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.Two)).ui

            assertEquals(PlayerSeat.Bottom, local.deliveryEdge(square(0, 2)))
            assertEquals(PlayerSeat.Top, local.deliveryEdge(square(5, 2)))
            assertNull(local.deliveryEdge(square(3, 2)))
            assertEquals(PlayerSeat.Top, asTwo.deliveryEdge(square(0, 2)))
            assertEquals(PlayerSeat.Bottom, asTwo.deliveryEdge(square(5, 2)))
            assertEquals(setOf(square(5, 1), square(5, 4), square(0, 1), square(0, 4)), local.blenderHomes)
            assertEquals(16, local.piecesOnBoard.size)
        }
}
