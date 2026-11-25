package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesEngineTest {
    // Small boards. Player one's back row is row 5 and their spawns are r5c1 and r5c4;
    // player two's are r0c1 and r0c4. Each side keeps a fruit so the match is not over.

    private val oneFruit = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 3, 2)
    private val twoFruit = fruit("two.strawberry.0", PlayerId.Two, PieceKind.Strawberry, 2, 2)

    private val oneSpawns = listOf(Position(5, 1), Position(5, 4))
    private val twoSpawns = listOf(Position(0, 1), Position(0, 4))

    // Constants and the opening

    @Test
    fun `constants match the Swift engine`() {
        assertEquals(18, RulesEngine.RULES_VERSION)
        assertEquals(3, RulesEngine.BLENDER_RESTORE_COST)
        assertEquals(3, RulesEngine.REPETITION_LIMIT)
    }

    @Test
    fun `the opening offers every fruit a step either way and every blender its own fruit`() {
        val state = GameSetup.newGame()
        val moves = RulesEngine.legalMoves(state)

        // Six fruit forward, the four with no blender behind them back, and each blender
        // onto the two fruit standing diagonally in front of it.
        assertEquals(14, moves.size)
        assertEquals(4, moves.count { state.piece(it.pieceId)?.isBlender == true })
        assertEquals(moves, moves.sorted())
        assertEquals(moves, RulesEngine.legalActions(state))
        assertEquals(moves, RulesEngine.legalMoves(PlayerId.One, state))
        assertEquals(
            moves.filter { it.pieceId == moves.first().pieceId },
            RulesEngine.legalMoves(moves.first().pieceId, state),
        )
        assertEquals(emptyList(), RulesEngine.legalMoves(PieceId("nobody"), state))

        // The other player's moves can be asked for out of turn, as the skip check does.
        assertEquals(14, RulesEngine.legalMoves(PlayerId.Two, state).size)
    }

    // Movement

    @Test
    fun `fruit crosses a joined edge and blenders never do`() {
        val edgeFruit = fruit("one.banana.0", PlayerId.One, PieceKind.Banana, 3, 0)
        val edgeBlender = blender("one.blender.0", PlayerId.One, at = Position(2, 0), home = oneSpawns[0])
        val state = board(edgeFruit, edgeBlender, twoFruit, wrap = BoardWrap.Sides)

        val fruitDestinations = RulesEngine.legalMoves(edgeFruit.id, state).map { it.destination }
        assertTrue(Position(3, 5) in fruitDestinations)
        assertEquals(
            listOf(Position(1, 1), Position(3, 1)),
            RulesEngine.legalMoves(edgeBlender.id, state).map { it.destination },
        )

        val walled = board(edgeFruit, edgeBlender, twoFruit, wrap = BoardWrap.Flat)
        assertFalse(Position(3, 5) in RulesEngine.legalMoves(edgeFruit.id, walled).map { it.destination })
    }

    @Test
    fun `fruit never lands on an occupied square`() {
        val state = board(oneFruit, twoFruit)

        assertFalse(twoFruit.position in RulesEngine.legalMoves(oneFruit.id, state).map { it.destination })
        assertEquals(
            RulesError.IllegalDestination(Position(2, 2)),
            assertFailsWith<RulesError> { RulesEngine.apply(Move(oneFruit.id, Position(3, 2), Position(2, 2)), state) },
        )
    }

    // Blending and delivery

    @Test
    fun `blending the other player's fruit pays its base value`() {
        val attacker = blender("one.blender.0", PlayerId.One, at = Position(3, 1), home = oneSpawns[0])
        val state = board(oneFruit, attacker, twoFruit, fruit("two.banana.0", PlayerId.Two, PieceKind.Banana, 1, 0))

        val transition = RulesEngine.apply(Move(attacker.id, Position(3, 1), Position(2, 2)), state)

        assertEquals(
            listOf(
                GameEvent.Moved(attacker.id, Position(3, 1), Position(2, 2)),
                GameEvent.Blended(
                    twoFruit.id,
                    PieceKind.Strawberry,
                    fruitOwner = PlayerId.Two,
                    at = Position(2, 2),
                    awardedTo = PlayerId.One,
                    points = 2,
                ),
                GameEvent.ScoreChanged(PlayerId.One, 2),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertNull(transition.state.piece(twoFruit.id))
        assertEquals(Position(2, 2), transition.state.piece(attacker.id)?.position)
        assertEquals(Scores(one = 2, two = 0), transition.state.scores)
    }

    @Test
    fun `blending your own fruit costs its base value and may go negative`() {
        val attacker = blender("one.blender.0", PlayerId.One, at = Position(4, 1), home = oneSpawns[0])
        val spare = fruit("one.banana.0", PlayerId.One, PieceKind.Banana, 4, 5)
        val state = board(oneFruit, spare, attacker, twoFruit)

        val transition = RulesEngine.apply(Move(attacker.id, Position(4, 1), Position(3, 2)), state)

        assertEquals(Scores(one = -2, two = 0), transition.state.scores)
        assertTrue(GameEvent.ScoreChanged(PlayerId.One, -2) in transition.events)
        assertTrue(transition.events.any { it is GameEvent.Blended && it.points == -2 })
    }

    @Test
    fun `delivering the last fruit pays double and ends the match on points`() {
        val runner = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 1, 3)
        val state = board(runner, twoFruit)

        val transition = RulesEngine.apply(Move(runner.id, Position(1, 3), Position(0, 3)), state)

        assertEquals(
            listOf(
                GameEvent.Moved(runner.id, Position(1, 3), Position(0, 3)),
                GameEvent.Delivered(runner.id, PieceKind.Pineapple, PlayerId.One, Position(0, 3), 6),
                GameEvent.ScoreChanged(PlayerId.One, 6),
                GameEvent.GameEnded(Outcome.Win(PlayerId.One)),
            ),
            transition.events,
        )
        assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.One)), transition.state.status)
        assertNull(transition.state.piece(runner.id))
        // The turn does not change hands once the match is over, but it is still counted.
        assertEquals(PlayerId.One, transition.state.activePlayer)
        assertEquals(2, transition.state.turnNumber)
        assertEquals(emptyList(), RulesEngine.legalActions(transition.state))
        assertEquals(emptyList(), RulesEngine.legalMoves(transition.state))
    }

    @Test
    fun `outcome is read off the scores`() {
        assertEquals(Outcome.Win(PlayerId.One), RulesEngine.outcome(Scores(one = 1, two = 0)))
        assertEquals(Outcome.Win(PlayerId.Two), RulesEngine.outcome(Scores(one = -4, two = -3)))
        assertEquals(Outcome.Draw, RulesEngine.outcome(Scores(one = 2, two = 2)))
    }

    @Test
    fun `fruit is depleted when either player has none, and an off-board banana still counts`() {
        assertFalse(RulesEngine.isFruitDepleted(board(oneFruit, twoFruit)))
        assertTrue(RulesEngine.isFruitDepleted(board(oneFruit)))

        val inTransit = Piece(PieceId("two.banana.0"), PlayerId.Two, PieceKind.Banana, position = null)
        assertFalse(RulesEngine.isFruitDepleted(board(oneFruit, inTransit)))
    }

    // Blender combat

    @Test
    fun `a blender destroys a blender standing in the open and takes the square`() {
        val attacker = blender("one.blender.0", PlayerId.One, at = Position(3, 3), home = oneSpawns[0])
        val defender = blender("two.blender.0", PlayerId.Two, at = Position(2, 4), home = twoSpawns[0])
        val state = board(oneFruit, attacker, twoFruit, defender)

        val transition = RulesEngine.apply(Move(attacker.id, Position(3, 3), Position(2, 4)), state)

        assertEquals(
            listOf(
                GameEvent.Moved(attacker.id, Position(3, 3), Position(2, 4)),
                GameEvent.BlenderDestroyed(defender.id, PlayerId.Two, by = attacker.id, at = Position(2, 4)),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertEquals(Position(2, 4), transition.state.piece(attacker.id)?.position)
        assertTrue(transition.state.piece(defender.id)?.needsBuyingBack == true)
        assertEquals(Scores(), transition.state.scores)
    }

    @Test
    fun `a blender may destroy one of its owner's own blenders`() {
        val attacker = blender("one.blender.0", PlayerId.One, at = Position(3, 3), home = oneSpawns[0])
        val other = blender("one.blender.1", PlayerId.One, at = Position(2, 4), home = oneSpawns[1])
        val state = board(oneFruit, attacker, other, twoFruit)

        val transition = RulesEngine.apply(Move(attacker.id, Position(3, 3), Position(2, 4)), state)

        assertTrue(
            GameEvent.BlenderDestroyed(other.id, PlayerId.One, by = attacker.id, at = Position(2, 4)) in
                transition.events,
        )
    }

    @Test
    fun `a blender is refused entry to a spawn its owner's blender is standing on`() {
        // Home is safe on either of the owner's spawns, from either side.
        val atHome = blender("two.blender.0", PlayerId.Two, at = twoSpawns[0], home = twoSpawns[0])
        val onTheOtherSpawn = blender("two.blender.1", PlayerId.Two, at = twoSpawns[1], home = twoSpawns[1])
        val attacker = blender("one.blender.0", PlayerId.One, at = Position(1, 2), home = oneSpawns[0])
        val state = board(oneFruit, attacker, twoFruit, atHome, onTheOtherSpawn)

        assertEquals(
            listOf(Position(0, 3), Position(2, 1), Position(2, 3)),
            RulesEngine.legalMoves(attacker.id, state).map { it.destination },
        )
        assertEquals(
            RulesError.IllegalDestination(twoSpawns[0]),
            assertFailsWith<RulesError> { RulesEngine.apply(Move(attacker.id, Position(1, 2), twoSpawns[0]), state) },
        )

        // Standing on the owner's *other* spawn is just as safe.
        val nearOther = attacker.copy(position = Position(1, 3))
        val away = atHome.copy(position = twoSpawns[1])
        val swapped = board(oneFruit, nearOther, twoFruit, away, onTheOtherSpawn.copy(position = null))
        assertTrue(RulesEngine.isOnASpawn(away, swapped))
        assertFalse(twoSpawns[1] in RulesEngine.legalMoves(nearOther.id, swapped).map { it.destination })

        // The owner's own blender cannot take it either.
        val friend = blender("two.blender.1", PlayerId.Two, at = Position(1, 0), home = twoSpawns[1])
        val friendly = board(oneFruit, twoFruit, atHome, friend, active = PlayerId.Two)
        assertFalse(twoSpawns[0] in RulesEngine.legalMoves(friend.id, friendly).map { it.destination })

        // A spawn is only home to its owner: player one's blender on player two's spawn can be taken.
        val squatter = blender("one.blender.1", PlayerId.One, at = twoSpawns[0], home = oneSpawns[1])
        val contested = board(oneFruit, squatter, twoFruit, friend, active = PlayerId.Two)
        assertFalse(RulesEngine.isOnASpawn(squatter, contested))
        assertTrue(twoSpawns[0] in RulesEngine.legalMoves(friend.id, contested).map { it.destination })
    }

    // Buy-back

    @Test
    fun `a destroyed blender may be bought back onto either empty spawn with no affordability check`() {
        val first = blender("one.blender.0", PlayerId.One, at = null, home = oneSpawns[0])
        val second = blender("one.blender.1", PlayerId.One, at = null, home = oneSpawns[1])
        val state = board(oneFruit, first, second, twoFruit)

        assertEquals(oneSpawns, RulesEngine.spawnSquares(PlayerId.One, state))
        assertEquals(emptyList(), RulesEngine.spawnSquares(PlayerId.Two, state))
        assertEquals(
            listOf(
                RestoreBlender(first.id, oneSpawns[0]),
                RestoreBlender(first.id, oneSpawns[1]),
                RestoreBlender(second.id, oneSpawns[0]),
                RestoreBlender(second.id, oneSpawns[1]),
            ),
            RulesEngine.legalActions(state).filterIsInstance<RestoreBlender>(),
        )
        // Moves sort before restores.
        assertTrue(RulesEngine.legalActions(state).first() is Move)

        // Onto the spawn it did not start on, with nothing in the bank.
        val transition = RulesEngine.apply(RestoreBlender(first.id, oneSpawns[1]), state)

        assertEquals(
            listOf(
                GameEvent.BlenderRestored(first.id, PlayerId.One, at = oneSpawns[1], cost = 3),
                GameEvent.ScoreChanged(PlayerId.One, -3),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertEquals(Scores(one = -3, two = 0), transition.state.scores)
        assertEquals(oneSpawns[1], transition.state.piece(first.id)?.position)
        assertEquals(oneSpawns[0], transition.state.piece(first.id)?.home)
        assertEquals(PlayerId.Two, transition.state.activePlayer)
        assertEquals(listOf(RestoreBlender(first.id, oneSpawns[1])), transition.state.history)
    }

    @Test
    fun `an occupied spawn is not offered for a buy-back`() {
        val destroyed = blender("one.blender.0", PlayerId.One, at = null, home = oneSpawns[0])
        val standing = blender("one.blender.1", PlayerId.One, at = oneSpawns[1], home = oneSpawns[1])
        val state = board(oneFruit, destroyed, standing, twoFruit)

        assertEquals(
            listOf(RestoreBlender(destroyed.id, oneSpawns[0])),
            RulesEngine.legalActions(state).filterIsInstance<RestoreBlender>(),
        )
    }

    // Validation

    @Test
    fun `a move is refused with the reason it is not available`() {
        val destroyed = blender("one.blender.0", PlayerId.One, at = null, home = oneSpawns[0])
        val state = board(oneFruit, destroyed, twoFruit)
        val origin = Position(3, 2)

        assertRefused(RulesError.UnknownPiece(PieceId("ghost")), Move(PieceId("ghost"), origin, origin), state)
        assertRefused(
            RulesError.NotActivePlayersPiece(twoFruit.id),
            Move(twoFruit.id, Position(2, 2), Position(1, 2)),
            state,
        )
        assertRefused(
            RulesError.PieceIsOffBoard(destroyed.id),
            Move(destroyed.id, oneSpawns[0], Position(4, 0)),
            state,
        )
        assertRefused(
            RulesError.OriginMismatch(expected = origin, given = Position(4, 2)),
            Move(oneFruit.id, Position(4, 2), Position(3, 2)),
            state,
        )
        assertRefused(
            RulesError.IllegalDestination(Position(2, 3)),
            Move(oneFruit.id, origin, Position(2, 3)),
            state,
        )

        val over = state.updated(status = GameStatus.Ended(Outcome.Draw))
        assertRefused(RulesError.GameOver, Move(oneFruit.id, origin, Position(3, 3)), over)
        assertRefused(RulesError.GameOver, RestoreBlender(destroyed.id, oneSpawns[0]), over)
    }

    @Test
    fun `a buy-back is refused with the reason it is not available`() {
        val destroyed = blender("one.blender.0", PlayerId.One, at = null, home = oneSpawns[0])
        val standing = blender("one.blender.1", PlayerId.One, at = Position(4, 4), home = oneSpawns[1])
        val theirs = blender("two.blender.0", PlayerId.Two, at = null, home = twoSpawns[0])
        val blocker = fruit("one.banana.0", PlayerId.One, PieceKind.Banana, 5, 4)
        val state = board(oneFruit, blocker, destroyed, standing, twoFruit, theirs)

        assertRefused(RulesError.UnknownPiece(PieceId("ghost")), RestoreBlender(PieceId("ghost"), oneSpawns[0]), state)
        assertRefused(RulesError.NotActivePlayersPiece(theirs.id), RestoreBlender(theirs.id, twoSpawns[0]), state)
        assertRefused(RulesError.BlenderNotDestroyed(standing.id), RestoreBlender(standing.id, oneSpawns[0]), state)
        assertRefused(RulesError.NotASpawnSquare(Position(4, 1)), RestoreBlender(destroyed.id, Position(4, 1)), state)
        assertRefused(RulesError.NotASpawnSquare(twoSpawns[0]), RestoreBlender(destroyed.id, twoSpawns[0]), state)
        assertRefused(RulesError.SpawnOccupied(destroyed.id), RestoreBlender(destroyed.id, oneSpawns[1]), state)

        val returning = destroyed.copy(respawnTurn = 9)
        assertRefused(
            RulesError.BlenderReturningOnItsOwn(returning.id),
            RestoreBlender(returning.id, oneSpawns[0]),
            board(oneFruit, returning, twoFruit),
        )
    }

    @Test
    fun `every refusal carries the message the player reads`() {
        val id = PieceId("one.blender.0")
        val square = Position(0, 0)
        val messages =
            mapOf(
                RulesError.GameOver to "The match has already finished.",
                RulesError.UnknownPiece(id) to "That piece is no longer on the board.",
                RulesError.NotActivePlayersPiece(id) to "That piece belongs to the other player.",
                RulesError.PieceIsOffBoard(id) to "That blender is still waiting to come back.",
                RulesError.OriginMismatch(square, square) to "That piece has moved since the move was created.",
                RulesError.IllegalDestination(square) to "That square is not a legal destination.",
                RulesError.BlenderNotDestroyed(id) to "That blender is already on the board.",
                RulesError.BlenderReturningOnItsOwn(id) to "That blender is coming back on its own.",
                RulesError.NotASpawnSquare(square) to "A blender can only come back on one of your spawns.",
                RulesError.SpawnOccupied(id) to "Something is standing on that blender's spawn.",
                RulesError.WouldRepeatPosition to "That would put the board back where it has already been twice.",
            )

        for ((error, message) in messages) assertEquals(message, error.message)
    }

    // Repetition

    @Test
    fun `a move that would stand the board in the same arrangement a third time is refused`() {
        val right = Move(oneFruit.id, Position(3, 2), Position(3, 3))
        val left = Move(oneFruit.id, Position(3, 3), Position(3, 2))
        val theirRight = Move(twoFruit.id, Position(2, 2), Position(2, 3))
        val theirLeft = Move(twoFruit.id, Position(2, 3), Position(2, 2))
        val lap = listOf(right, theirRight, left, theirLeft)

        // One full lap puts the opening arrangement on the board a second time, and
        // three more moves bring it within one move of a third.
        val start = board(oneFruit, twoFruit)
        val state = (lap + lap.dropLast(1)).fold(start) { state, move -> RulesEngine.apply(move, state).state }
        assertEquals(2, state.timesSeen(start.boardKey))

        assertTrue(RulesEngine.wouldRepeat(theirLeft, state))
        assertFalse(theirLeft in RulesEngine.legalActions(state))
        assertFalse(theirLeft in RulesEngine.legalMoves(state))
        assertFalse(theirLeft in RulesEngine.legalMoves(twoFruit.id, state))
        assertEquals(
            RulesError.WouldRepeatPosition,
            assertFailsWith<RulesError> { RulesEngine.apply(theirLeft, state) },
        )

        // The move is still a well-formed one: only the repetition rule stands in its way.
        RulesEngine.validate(theirLeft, state)
        assertTrue(theirLeft in RulesEngine.legalActions(state, enforcingRepetition = false))
        assertTrue(theirLeft in RulesEngine.legalMoves(PlayerId.Two, state, enforcingRepetition = false))
        assertTrue(theirLeft in RulesEngine.legalMoves(twoFruit.id, state, enforcingRepetition = false))
        val forced = RulesEngine.apply(theirLeft, state, enforcingRepetition = false).state
        assertEquals(3, forced.timesSeen(start.boardKey))

        // Every other move is still on offer.
        assertTrue(RulesEngine.legalActions(state).isNotEmpty())
    }

    @Test
    fun `each applied action records the new arrangement once`() {
        val start = board(oneFruit, twoFruit)
        val next = RulesEngine.apply(Move(oneFruit.id, Position(3, 2), Position(3, 3)), start).state

        assertEquals(listOf(start.boardKey, next.boardKey), next.boardHistory)
        assertEquals(1, next.timesSeen(next.boardKey))
    }

    // Turn end

    @Test
    fun `a player with nothing legal to do is skipped`() {
        // Player two's only piece is walled in against the left edge once player one closes the last gap.
        val trapped = fruit("two.strawberry.0", PlayerId.Two, PieceKind.Strawberry, 2, 0)
        val above = fruit("one.banana.0", PlayerId.One, PieceKind.Banana, 1, 0)
        val below = fruit("one.banana.1", PlayerId.One, PieceKind.Banana, 3, 0)
        val closer = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 2, 2)
        val state = board(above, below, closer, trapped)

        val transition = RulesEngine.apply(Move(closer.id, Position(2, 2), Position(2, 1)), state)

        assertEquals(
            listOf(
                GameEvent.Moved(closer.id, Position(2, 2), Position(2, 1)),
                GameEvent.TurnPassed(skipped = PlayerId.Two),
                GameEvent.TurnChanged(PlayerId.One),
            ),
            transition.events,
        )
        assertEquals(PlayerId.One, transition.state.activePlayer)
        assertEquals(GameStatus.InProgress, transition.state.status)
        assertEquals(2, transition.state.turnNumber)
        assertTrue(RulesEngine.legalActions(transition.state).isNotEmpty())
    }

    // Immutability

    @Test
    fun `apply never changes the state it was given`() {
        val state = GameSetup.newGame()
        val snapshot = state.updated()
        val pieces = state.pieces.toList()
        val history = state.history.toList()
        val boardHistory = state.boardHistory.toList()
        val boardKey = state.boardKey
        val positionKey = state.positionKey

        val transition = RulesEngine.apply(RulesEngine.legalActions(state).first(), state)

        assertFalse(transition.state == state)
        assertEquals(snapshot, state)
        assertEquals(pieces, state.pieces)
        assertEquals(history, state.history)
        assertEquals(boardHistory, state.boardHistory)
        assertEquals(boardKey, state.boardKey)
        assertEquals(positionKey, state.positionKey)
        assertEquals(1, state.turnNumber)
        assertEquals(PlayerId.One, state.activePlayer)
    }

    @Test
    fun `a whole game leaves every earlier state as it was`() {
        var state = GameSetup.newGame(boardWrap = BoardWrap.All)
        val visited = mutableListOf<Pair<GameState, String>>()

        repeat(60) { step ->
            val legal = RulesEngine.legalActions(state)
            if (legal.isEmpty()) return@repeat
            visited += state to fingerprint(state)
            state = RulesEngine.apply(legal[(step * 7) % legal.size], state).state
        }

        assertTrue(visited.size > 20)
        for ((earlier, fingerprint) in visited) assertEquals(fingerprint, fingerprint(earlier))
    }

    // Helpers

    private fun fingerprint(state: GameState): String =
        listOf(
            places(state),
            state.activePlayer,
            state.scores,
            state.status,
            state.turnNumber,
            state.history,
            state.boardHistory,
        ).joinToString("|")

    private fun assertRefused(
        expected: RulesError,
        action: GameAction,
        state: GameState,
    ) {
        assertEquals(expected, assertFailsWith<RulesError> { RulesEngine.validate(action, state) })
        assertEquals(expected, assertFailsWith<RulesError> { RulesEngine.apply(action, state) })
        assertFalse(action in RulesEngine.legalActions(state))
    }

    private fun board(
        vararg pieces: Piece,
        active: PlayerId = PlayerId.One,
        wrap: BoardWrap = BoardWrap.Flat,
        scores: Scores = Scores(),
    ): GameState = GameState(pieces = pieces.toList(), activePlayer = active, boardWrap = wrap, scores = scores)

    private fun fruit(
        id: String,
        owner: PlayerId,
        kind: PieceKind,
        row: Int,
        column: Int,
    ): Piece = Piece(PieceId(id), owner, kind, Position(row, column), home = Position(row, column))

    private fun blender(
        id: String,
        owner: PlayerId,
        at: Position?,
        home: Position,
    ): Piece = Piece(PieceId(id), owner, PieceKind.Blender, position = at, home = home)
}
