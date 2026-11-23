package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.Direction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.assertRefusedWith
import com.charlesbarton.bananachess.engine.support.isOffBoard
import com.charlesbarton.bananachess.engine.support.move
import com.charlesbarton.bananachess.engine.support.position
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/SetupAndMovementTests.swift`. Each
 * nested class is one Swift suite and each test keeps its Swift function name.
 */
class SetupAndMovementTests {
    @Nested
    @DisplayName("Opening setup")
    inner class SetupTests {
        @Test
        @DisplayName("A player's two blender homes sit on opposite square colours")
        fun blenderHomeColours() {
            val state = GameSetup.newGame()

            for (player in PlayerId.entries) {
                val homes = state.blenders(player).mapNotNull { it.home }
                assertEquals(2, homes.size)
                assertTrue(homes[0].isDarkSquare != homes[1].isDarkSquare)
                assertTrue(homes.all { it.row == player.backRow })
            }
        }

        @Test
        @DisplayName("Every square is reachable by one of a player's blenders")
        fun blendersCoverTheBoard() {
            val state = GameSetup.newGame()

            for (player in PlayerId.entries) {
                val homes = state.blenders(player).mapNotNull { it.home }
                for (square in Board.allPositions) {
                    // A diagonal mover only ever reaches squares of its own colour.
                    val reachable = homes.any { it.isDarkSquare == square.isDarkSquare }
                    assertTrue(reachable, "$square unreachable for $player")
                }
            }
        }
    }

    @Nested
    @DisplayName("Movement")
    inner class MovementTests {
        @Test
        @DisplayName("Fruit moves exactly one square orthogonally, in any direction")
        fun fruitMovement() {
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(3, 3))
            val state = builder.state()

            assertEquals(
                setOf(position(2, 3), position(4, 3), position(3, 2), position(3, 4)),
                destinations(banana, state),
            )
        }

        @Test
        @DisplayName("Blenders move exactly one square diagonally, in any direction")
        fun blenderMovement() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3))
            val state = builder.state()

            assertEquals(
                setOf(position(2, 2), position(2, 4), position(4, 2), position(4, 4)),
                destinations(blender, state),
            )
        }

        @ParameterizedTest
        @CsvSource("0, 0", "0, 5", "5, 0", "5, 5")
        @DisplayName("Pieces cannot leave the board")
        fun cornersAreBounded(
            row: Int,
            column: Int,
        ) {
            val corner = position(row, column)
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = corner)
            val blender =
                builder.blender(PlayerId.Two, at = if (corner == position(0, 0)) position(5, 5) else position(0, 0))
            val state = builder.state()

            assertEquals(2, RulesEngine.legalMoves(fruit, state).size)
            assertEquals(1, RulesEngine.legalMoves(blender, state).size)
            assertTrue(RulesEngine.legalMoves(fruit, state).all { Board.contains(it.destination) })
        }

        @Test
        @DisplayName("Fruit never captures — not even an opponent's fruit, not even to deliver")
        fun fruitDoesNotCapture() {
            val builder = BoardBuilder()
            val mover = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(3, 3))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 3))
            builder.blender(PlayerId.Two, at = position(3, 4))
            val state = builder.state()

            assertEquals(setOf(position(4, 3), position(3, 2)), destinations(mover, state))
        }

        @Test
        @DisplayName("A blocked delivery square cannot be entered")
        fun blockedDeliverySquare() {
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2))
            builder.blender(PlayerId.Two, at = position(0, 2), home = position(0, 1))
            val state = builder.state()

            assertFalse(position(0, 2) in destinations(banana, state))
            assertEquals(Scores(), state.scores)
        }

        @Test
        @DisplayName("A blender may move onto any fruit, its owner's included")
        fun blenderMayEnterAnyFruit() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(2, 4))
            val state = builder.state()

            val destinations = destinations(blender, state)
            assertTrue(position(2, 2) in destinations, "self-blending is now a legal move")
            assertTrue(position(2, 4) in destinations)
            assertEquals(4, destinations.size, "nothing on the board blocks a blender any more")
        }

        @Test
        @DisplayName("A destroyed blender has no moves and cannot be moved")
        fun destroyedBlenderCannotMove() {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val theirs = builder.blender(PlayerId.Two, at = position(2, 2), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 5))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            val afterCombat = RulesEngine.apply(state.move(mine, to = position(2, 2)), state).state

            assertTrue(afterCombat.isOffBoard(theirs))
            assertTrue(RulesEngine.legalMoves(theirs, afterCombat).isEmpty())
            assertRefusedWith(RulesError.PieceIsOffBoard(theirs)) {
                RulesEngine.apply(Move(theirs, origin = position(0, 4), destination = position(1, 3)), afterCombat)
            }
        }

        @Test
        @DisplayName("Only the active player's pieces can move")
        fun turnOwnership() {
            val state = GameSetup.newGame()
            val opponentPiece = state.fruit(PlayerId.Two)[0]

            assertTrue(RulesEngine.legalMoves(state).all { state.piece(it.pieceId)?.owner == PlayerId.One })

            val origin = assertNotNull(opponentPiece.position)
            val illegal = Move(opponentPiece.id, origin = origin, destination = origin.offset(1, 0))
            assertRefusedWith(RulesError.NotActivePlayersPiece(opponentPiece.id)) {
                RulesEngine.apply(illegal, state)
            }
        }

        @Test
        @DisplayName("A move whose origin no longer matches the piece is rejected")
        fun staleOriginRejected() {
            val state = GameSetup.newGame()
            val piece = state.fruit(PlayerId.One)[0]
            val stale = Move(piece.id, origin = position(0, 0), destination = position(0, 1))

            assertFailsWith<RulesError> { RulesEngine.apply(stale, state) }
        }
    }

    @Nested
    @DisplayName("Board setups")
    inner class BoardSetupTests {
        @ParameterizedTest
        @EnumSource(BoardSetup::class)
        @DisplayName("Every setup deals the same pieces, only differently placed")
        fun everySetupDealsTheSamePieces(setup: BoardSetup) {
            val state = GameSetup.newGame(boardSetup = setup, seed = 99uL)

            assertEquals(setup, state.boardSetup, "and the match says which it is")
            assertEquals(16, state.pieces.size)
            for (player in PlayerId.entries) {
                assertEquals(GameSetup.fruitCount, state.fruit(player).size)
                assertEquals(2, state.blenders(player).size)
                for (kind in listOf(PieceKind.Banana, PieceKind.Strawberry, PieceKind.Pineapple)) {
                    assertEquals(2, state.fruit(player).count { it.kind == kind })
                }
            }
            // No two pieces ever share a square.
            val squares = state.pieces.mapNotNull { it.position }
            assertEquals(squares.size, squares.toSet().size)
            // Everything starts on its own square, which is where bananas recycle to.
            assertTrue(state.pieces.all { it.position == it.home })
            assertFalse(state.status.isOver)
            assertTrue(RulesEngine.legalMoves(state).isNotEmpty())
        }

        @Test
        @DisplayName("Blendy is Standard with the two rows swapped")
        fun blendyIsStandardInverted() {
            val standard = GameSetup.newGame(boardSetup = BoardSetup.Standard)
            val blendy = GameSetup.newGame(boardSetup = BoardSetup.Blendy)

            for (player in PlayerId.entries) {
                assertTrue(standard.blenders(player).all { it.position?.row == player.backRow })
                assertTrue(standard.fruit(player).all { it.position?.row == player.fruitRow })

                assertTrue(blendy.fruit(player).all { it.position?.row == player.backRow })
                assertTrue(blendy.blenders(player).all { it.position?.row == player.fruitRow })

                // Same fruit in the same file, one row further back.
                val mine = standard.fruit(player).sortedBy { it.id }
                val theirs = blendy.fruit(player).sortedBy { it.id }
                for ((standardFruit, blendyFruit) in mine.zip(theirs)) {
                    assertEquals(standardFruit.kind, blendyFruit.kind)
                    assertEquals(standardFruit.position?.column, blendyFruit.position?.column)
                }
            }
        }

        @Test
        @DisplayName("Blendy keeps a player's two spawns on opposite colours")
        fun blendySpawnsStayOnOppositeColours() {
            // The invariant the whole buy-back rule leans on: a blender never changes
            // colour, so two spawns of the same colour would leave half the board uncovered.
            for (setup in listOf(BoardSetup.Standard, BoardSetup.Blendy)) {
                val state = GameSetup.newGame(boardSetup = setup)
                for (player in PlayerId.entries) {
                    val colours =
                        state
                            .blenders(player)
                            .mapNotNull { it.position }
                            .map { (it.row + it.column) % 2 == 0 }
                    assertEquals(2, colours.toSet().size, "$setup leaves $player covering one colour")
                }
            }
        }

        @Test
        @DisplayName("A seed deals the same board twice, and different seeds do not")
        fun randomIsReproducibleFromItsSeed() {
            assertEquals(
                GameSetup.newGame(boardSetup = BoardSetup.Random, seed = 7uL).pieces,
                GameSetup.newGame(boardSetup = BoardSetup.Random, seed = 7uL).pieces,
            )

            // Not a strict requirement of the rules, but a mode that quietly dealt the same
            // board every time would be a bug nobody noticed.
            val boards =
                (1uL..50uL)
                    .map { seed ->
                        GameSetup.newGame(boardSetup = BoardSetup.Random, seed = seed).pieces.map { it.position }
                    }.toSet()
            assertTrue(boards.size > 45, "seeds should not collide on the same arrangement")
        }
    }

    @Nested
    @DisplayName("Board edges")
    inner class BoardWrapTests {
        /** A lone fruit in a corner, so the edges are the only thing limiting it. */
        private fun corner(
            wrap: BoardWrap,
            at: Position,
        ): Pair<GameState, PieceId> {
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = at)
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(3, 3))
            return builder.state().updated(boardWrap = wrap) to fruit
        }

        @Test
        @DisplayName("With walls, a piece on the left edge cannot go further left")
        fun wallsStopAPiece() {
            val (state, fruit) = corner(BoardWrap.Flat, at = position(2, 0))
            val destinations = destinations(fruit, state)

            assertFalse(position(2, 5) in destinations)
            assertEquals(setOf(position(1, 0), position(3, 0), position(2, 1)), destinations)
        }

        @Test
        @DisplayName("With sides joined, stepping off the left edge arrives on the right")
        fun sidesWrapAround() {
            val (state, fruit) = corner(BoardWrap.Sides, at = position(2, 0))
            val destinations = destinations(fruit, state)

            assertTrue(position(2, 5) in destinations, "the same row, the far column")
            assertEquals(4, destinations.size, "a piece on the edge is no longer short of a move")
        }

        @Test
        @DisplayName("Sides join, rows do not — the run home is unchanged")
        fun sidesLeaveRowsAlone() {
            val (state, fruit) = corner(BoardWrap.Sides, at = position(0, 2))
            val destinations = destinations(fruit, state)

            assertFalse(position(5, 2) in destinations, "top and bottom are still walls")
            assertEquals(setOf(position(1, 2), position(0, 1), position(0, 3)), destinations)
        }

        @Test
        @DisplayName("With every edge joined, rows wrap too")
        fun allWrapsBothWays() {
            val (state, fruit) = corner(BoardWrap.All, at = position(0, 0))

            assertEquals(
                setOf(position(5, 0), position(1, 0), position(0, 5), position(0, 1)),
                destinations(fruit, state),
            )
        }

        @Test
        @DisplayName("Every fruit has a move in every direction once all edges join")
        fun allEdgesJoinedMeansNoFruitIsEverCornered() {
            val state = GameSetup.newGame(boardWrap = BoardWrap.All)
            for (piece in state.pieces.filter { it.isOnBoard && it.isFruit }) {
                // Four directions each, minus whatever is blocked by another piece — the
                // point is that the *edge* never removes one.
                val origin = assertNotNull(piece.position)
                val steps = RulesEngine.legalMoves(piece.id, state).size
                val blocked =
                    Direction.orthogonal
                        .mapNotNull { Board.step(origin, it.row, it.column, BoardWrap.All) }
                        .count { state.pieceAt(it) != null }
                assertEquals(4, steps + blocked, "${piece.id} had $steps moves and $blocked blocked")
            }
        }

        /**
         * The invariant the whole buy-back rule leans on: a blender never changes square
         * colour, so a player's two blenders between them cover the board. Blenders not
         * crossing edges makes this safe by construction, but the geometry holds anyway on
         * a 6×6 board, because a wrap shifts a coordinate by six and six is even. Pinned so
         * that letting blenders wrap later, or changing the board size to an odd number,
         * fails here rather than silently in play.
         */
        @ParameterizedTest
        @EnumSource(BoardWrap::class)
        @DisplayName("Diagonal steps never change square colour, wrapped or not")
        fun diagonalStepsPreserveSquareColour(wrap: BoardWrap) {
            for (origin in Board.allPositions) {
                for (step in Direction.diagonal) {
                    val destination = Board.step(origin, step.row, step.column, wrap) ?: continue
                    assertEquals(
                        origin.isDarkSquare,
                        destination.isDarkSquare,
                        "$origin → $destination changed colour under $wrap",
                    )
                }
            }
        }

        @ParameterizedTest
        @EnumSource(BoardWrap::class)
        @DisplayName("A blender never crosses an edge, on any board")
        fun blendersNeverWrap(wrap: BoardWrap) {
            val builder = BoardBuilder()
            // On the left edge, with a fruit waiting on the far side of it.
            val blender = builder.blender(PlayerId.One, at = position(2, 0), home = position(5, 1))
            val acrossTheSeam = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(1, 5))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 4))
            val state = builder.state().updated(boardWrap = wrap)

            val destinations = destinations(blender, state)
            assertEquals(setOf(position(1, 1), position(3, 1)), destinations, "only the two squares inside the wall")
            assertFalse(position(1, 5) in destinations)
            assertFalse(position(3, 5) in destinations)
            assertFailsWith<RulesError> { RulesEngine.apply(state.move(blender, to = position(1, 5)), state) }
            assertNotNull(state.piece(acrossTheSeam), "the fruit across the seam is safe")
        }

        @Test
        @DisplayName("Fruit crosses the same edge the blender cannot")
        fun fruitCrossesWhereABlenderCannot() {
            val builder = BoardBuilder()
            builder.blender(PlayerId.One, at = position(2, 0), home = position(5, 1))
            val fruit = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(3, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state().updated(boardWrap = BoardWrap.Sides)

            assertTrue(position(3, 5) in destinations(fruit, state), "the fruit may use the seam")
        }

        /**
         * Worth stating out loud: with every edge joined, the delivery row is reachable
         * *backwards*. A fruit on its own back row is one step from the far edge.
         */
        @Test
        @DisplayName("With all edges joined, a fruit can deliver by going backwards")
        fun allEdgesMakesTheBackRowAdjacentToTheGoal() {
            val builder = BoardBuilder()
            // Player one's back row is 5; their delivery row is 0. Those are now adjacent.
            val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(3, 3))
            val state = builder.state().updated(boardWrap = BoardWrap.All)

            val result = RulesEngine.apply(state.move(fruit, to = position(0, 2)), state)

            assertEquals(PieceKind.Banana.deliveryValue, result.state.scores.one)
            assertNull(result.state.piece(fruit), "delivered, not merely moved")
        }
    }

    private fun destinations(
        id: PieceId,
        state: GameState,
    ): Set<Position> = RulesEngine.legalMoves(id, state).map { it.destination }.toSet()
}
