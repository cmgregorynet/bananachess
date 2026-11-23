package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.rules.applying
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.assertRefusedWith
import com.charlesbarton.bananachess.engine.support.move
import com.charlesbarton.bananachess.engine.support.position
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private data class ShuffleBoard(
    val state: GameState,
    val mine: PieceId,
    val theirs: PieceId,
)

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/EndStateAndDeterminismTests.swift`.
 * Each nested class is one Swift suite and each test keeps its Swift function name.
 */
class EndStateAndDeterminismTests {
    @Nested
    @DisplayName("End state")
    inner class EndStateTests {
        @Test
        @DisplayName("Resolving the last fruit ends the match and locks the board")
        fun lastFruitEndsMatch() {
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(1, 2))
            builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 4))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(fruit, to = position(0, 2)), state)

            assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.One)), result.state.status)
            assertTrue(result.state.remainingFruit.isEmpty())
            assertTrue(RulesEngine.legalMoves(result.state).isEmpty())
            assertTrue(GameEvent.GameEnded(Outcome.Win(PlayerId.One)) in result.events)
        }

        @Test
        @DisplayName("Equal scores are a draw")
        fun drawOnEqualScores() {
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2))
            builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 4))
            val state = builder.state(scores = Scores(one = 0, two = 2))

            val result = RulesEngine.apply(state.move(fruit, to = position(0, 2)), state)

            assertEquals(GameStatus.Ended(Outcome.Draw), result.state.status)
        }

        @Test
        @DisplayName("A finished match refuses further moves")
        fun finishedMatchRefusesMoves() {
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2))
            val blender = builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 4))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(fruit, to = position(0, 2)), state)
            val afterwards = Move(blender, origin = position(3, 3), destination = position(2, 2))

            assertRefusedWith(RulesError.GameOver) { RulesEngine.apply(afterwards, result.state) }
        }

        @Test
        @DisplayName("A player with no legal move is skipped rather than deadlocking")
        fun playerWithNoMovesIsSkipped() {
            // A blender can always move somewhere now that it may land on anything, so the
            // only player who can run out of moves is one whose blenders are both destroyed,
            // and whose spawns are blocked so neither can be bought back, and whose last
            // fruit is boxed in, since fruit never captures out.
            val builder = BoardBuilder()
            val mover = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(3, 3))
            builder.destroyedBlender(PlayerId.Two, home = position(0, 1))
            builder.destroyedBlender(PlayerId.Two, home = position(0, 4))
            builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(1, 1))
            // These also sit on player two's blender spawns, so nothing can be bought back.
            for (square in listOf(position(0, 1), position(1, 0), position(1, 2), position(2, 1), position(0, 4))) {
                builder.fruit(PieceKind.Banana, PlayerId.One, at = square)
            }
            val state = builder.state()

            val result = RulesEngine.apply(state.move(mover, to = position(2, 3)), state)

            assertEquals(PlayerId.One, result.state.activePlayer)
            assertTrue(GameEvent.TurnPassed(skipped = PlayerId.Two) in result.events)
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class DeterminismTests {
        /** A blender that runs into another, with a second enemy blender and a fruit each standing by. */
        private fun collision(): Pair<GameState, Move> {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.blender(PlayerId.Two, at = position(2, 2), home = position(0, 4))
            builder.blender(PlayerId.Two, at = position(5, 1), home = position(0, 1))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(0, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(4, 4))
            val state = builder.state()
            return state to state.move(mine, to = position(2, 2))
        }

        @Test
        @DisplayName("Replaying a move from the same state produces identical state and events")
        fun replayIsIdentical() {
            val (state, move) = collision()

            val first = RulesEngine.apply(move, state)
            val second = RulesEngine.apply(move, state)

            assertEquals(first.state, second.state)
            assertEquals(first.events, second.events)
            assertEquals(first.state.positionKey, second.state.positionKey)
        }

        @Test
        @DisplayName("Legal move lists come back in a stable order")
        fun legalMovesAreOrdered() {
            val state = GameSetup.newGame()
            assertEquals(RulesEngine.legalMoves(state).sorted(), RulesEngine.legalMoves(state))
        }

        @Test
        @DisplayName("Replaying a transition's events reproduces the state the engine returned")
        fun eventReplayMatchesEngine() {
            val (state, move) = collision()

            val result = RulesEngine.apply(move, state)
            val replayed = result.events.fold(state) { current, event -> current.applying(event) }

            assertEquals(result.state.pieces, replayed.pieces)
            assertEquals(result.state.scores, replayed.scores)
            assertEquals(result.state.activePlayer, replayed.activePlayer)
            assertEquals(result.state.status, replayed.status)
        }

        @Test
        @DisplayName("The position key ignores nothing that matters")
        fun positionKeySensitivity() {
            val state = GameSetup.newGame()
            val moved = state.updated(activePlayer = PlayerId.Two)
            assertNotEquals(state.positionKey, moved.positionKey)

            val scored = state.updated(scores = state.scores.copy(one = state.scores.one + 1))
            assertNotEquals(state.positionKey, scored.positionKey)
        }
    }

    @Nested
    @DisplayName("Repetition")
    inner class RepetitionTests {
        /**
         * Two blenders far enough apart to shuffle without ever meeting, and a fruit each
         * so the match does not end for lack of one.
         */
        private fun shuffleBoard(): ShuffleBoard {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val theirs = builder.blender(PlayerId.Two, at = position(0, 0), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(0, 5))
            return ShuffleBoard(builder.state(), mine, theirs)
        }

        @Test
        @DisplayName("A shuffle is stopped on the lap that would repeat a third time")
        fun aShuffleCannotGoRoundAThirdTime() {
            val board = shuffleBoard()
            var state = board.state
            val lap =
                listOf(
                    board.mine to position(2, 2),
                    board.theirs to position(1, 1),
                    board.mine to position(3, 3),
                    board.theirs to position(0, 0),
                )

            // One full lap puts every arrangement on the board for a second time.
            for ((piece, destination) in lap) {
                state = RulesEngine.apply(state.move(piece, to = destination), state).state
            }

            // The second lap is fine right up until the move that closes it.
            for ((piece, destination) in lap.dropLast(1)) {
                state = RulesEngine.apply(state.move(piece, to = destination), state).state
            }

            val closing = state.move(board.theirs, to = position(0, 0))
            assertFalse(closing in RulesEngine.legalMoves(state), "not offered")
            assertRefusedWith(RulesError.WouldRepeatPosition) { RulesEngine.apply(closing, state) }
            // And the player is not stuck: the other three directions are still there.
            assertTrue(RulesEngine.legalMoves(state).isNotEmpty())
        }

        /**
         * The point of keying on the arrangement rather than the whole position. Every
         * unbounded loop measured in this game bled points while the board went round, so a
         * key that included the score would have seen a new position every lap.
         */
        @Test
        @DisplayName("The arrangement key ignores the scores")
        fun theKeyIgnoresScores() {
            val builder = BoardBuilder()
            builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val level = builder.state(scores = Scores(one = 0, two = 0))
            val lopsided = builder.state(scores = Scores(one = -400, two = 12))

            assertEquals(level.boardKey, lopsided.boardKey)
            assertNotEquals(level.positionKey, lopsided.positionKey, "the full position key still cares")
        }

        @Test
        @DisplayName("A fresh match already counts the arrangement it starts in")
        fun theOpeningCountsItself() {
            val state = GameSetup.newGame()
            assertEquals(listOf(state.boardKey), state.boardHistory)
            assertEquals(1, state.timesSeen(state.boardKey))
        }

        @Test
        @DisplayName("Buying a blender back is subject to the rule too")
        fun restoresAreCheckedAsWell() {
            // A restore is an action like any other, so it goes through the same gate.
            val builder = BoardBuilder()
            val lost = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.One, at = position(5, 4), home = position(5, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state()

            val restore = RestoreBlender(lost, at = position(5, 1))
            assertTrue(restore in RulesEngine.legalActions(state), "available the first time")
            assertFalse(RulesEngine.wouldRepeat(restore, state))
        }
    }
}
