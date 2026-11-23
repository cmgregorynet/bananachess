package com.charlesbarton.bananachess.engine.model

import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStateTest {
    private val pineappleA = GameSetup.pieceId(PlayerId.One, "fruit", 3)
    private val pineappleB = GameSetup.pieceId(PlayerId.One, "fruit", 5)
    private val banana = GameSetup.pieceId(PlayerId.One, "fruit", 0)
    private val blender = GameSetup.pieceId(PlayerId.One, "blender", 0)

    @Test
    fun `pieces are sorted by id and the opening arrangement is recorded`() {
        val opening = GameSetup.newGame()
        val reversed = GameState(pieces = opening.pieces.reversed())

        assertEquals(opening.pieces, reversed.pieces)
        assertEquals(opening.pieces.map { it.id }.sorted(), reversed.pieces.map { it.id })
        assertEquals(listOf(reversed.boardKey), reversed.boardHistory)
        assertEquals(opening, reversed)
    }

    @Test
    fun `an explicit board history is kept as given`() {
        val state = GameState(pieces = GameSetup.newGame().pieces, boardHistory = listOf(7uL, 9uL, 7uL))

        assertEquals(listOf(7uL, 9uL, 7uL), state.boardHistory)
        assertEquals(2, state.timesSeen(7uL))
        assertEquals(0, state.timesSeen(8uL))
    }

    @Test
    fun `a state does not share the lists it was built from`() {
        val pieces = GameSetup.newGame().pieces.toMutableList()
        val history = mutableListOf<GameAction>()
        val state = GameState(pieces = pieces, history = history)
        val snapshot = state.pieces

        pieces.clear()
        history += RestoreBlender(blender, Position(5, 1))

        assertEquals(16, state.pieces.size)
        assertEquals(snapshot, state.pieces)
        assertEquals(emptyList(), state.history)
    }

    @Test
    fun `updaters return new states and leave the original untouched`() {
        val original = GameSetup.newGame()
        val untouched = GameSetup.newGame()
        val move = Move(banana, Position(4, 0), Position(3, 0))

        val moved = original.withPosition(move.destination, banana)
        val gone = original.withPosition(null, blender).withRespawnTurn(9, blender)
        val removed = original.removing(pineappleA)
        val recorded = moved.recordingArrangement()
        val updated =
            original.updated(
                activePlayer = PlayerId.Two,
                scores = original.scores.updated(PlayerId.Two, -3),
                status = GameStatus.Ended(Outcome.Draw),
                turnNumber = 2,
                history = original.history + move,
            )

        assertEquals(untouched, original)
        assertEquals(Position(3, 0), moved.piece(banana)?.position)
        assertEquals(Position(4, 0), moved.piece(banana)?.home)
        assertEquals(moved.piece(banana), moved.pieceAt(Position(3, 0)))
        assertNull(moved.pieceAt(Position(4, 0)))
        assertNull(gone.piece(blender)?.position)
        assertEquals(9, gone.piece(blender)?.respawnTurn)
        assertEquals(15, removed.pieces.size)
        assertNull(removed.piece(pineappleA))
        assertEquals(listOf(original.boardKey, moved.boardKey), recorded.boardHistory)
        assertEquals(original.boardHistory, moved.boardHistory)
        assertEquals(PlayerId.Two, updated.activePlayer)
        assertEquals(Scores(0, -3), updated.scores)
        assertEquals(Outcome.Draw, updated.outcome)
        assertTrue(updated.status.isOver)
        assertEquals(2, updated.turnNumber)
        assertEquals(listOf<GameAction>(move), updated.history)
        assertEquals(original.pieces, updated.pieces)
        assertEquals(original, original.withPosition(Position(0, 0), PieceId("nobody")))
    }

    @Test
    fun `board key ignores scores and position key does not`() {
        val state = GameSetup.newGame()
        val scored = state.updated(scores = Scores(one = 3, two = -1))

        assertEquals(state.boardKey, scored.boardKey)
        assertNotEquals(state.positionKey, scored.positionKey)
    }

    @Test
    fun `both keys change with the arrangement, the mover and a respawn date`() {
        val state = GameSetup.newGame()
        val variants =
            listOf(
                state.withPosition(Position(3, 0), banana),
                state.withPosition(null, blender),
                state.withPosition(null, blender).withRespawnTurn(4, blender),
                state.updated(activePlayer = PlayerId.Two),
            )

        assertEquals(variants.size + 1, (variants + state).map { it.boardKey }.toSet().size)
        assertEquals(variants.size + 1, (variants + state).map { it.positionKey }.toSet().size)
    }

    @Test
    fun `keys do not depend on turn number, history or settings`() {
        val state = GameSetup.newGame()
        val other =
            state.updated(
                turnNumber = 40,
                ruleSet = RuleSet.Wildcard,
                boardWrap = BoardWrap.All,
                blenderBattles = true,
            )

        assertEquals(state.boardKey, other.boardKey)
        assertEquals(state.positionKey, other.positionKey)
    }

    @Test
    fun `fruit standing value counts only fruit on the board and battle strength halves it`() {
        val state = GameSetup.newGame()
        assertEquals(12, state.fruitStandingValue(PlayerId.One))
        assertEquals(6, state.battleStrength(PlayerId.One))

        val waiting = state.withPosition(null, banana)
        assertEquals(11, waiting.fruitStandingValue(PlayerId.One))
        assertEquals(5, waiting.battleStrength(PlayerId.One))
        assertEquals(12, waiting.fruitValueRemaining(PlayerId.One))
        assertEquals(listOf(banana), waiting.waitingBananas(PlayerId.One).map { it.id })

        val blended = state.removing(pineappleA)
        assertEquals(9, blended.fruitStandingValue(PlayerId.One))
        assertEquals(4, blended.battleStrength(PlayerId.One))
        assertEquals(12, blended.fruitStandingValue(PlayerId.Two))
    }

    @Test
    fun `a pineapple is golden only as the last one under wildcard rules`() {
        val wildcard = GameSetup.newGame(ruleSet = RuleSet.Wildcard)
        assertFalse(wildcard.isGolden(pineappleA))
        assertNull(wildcard.goldenPineapple(PlayerId.One))

        val last = wildcard.removing(pineappleA)
        assertTrue(last.isGolden(pineappleB))
        assertEquals(pineappleB, last.goldenPineapple(PlayerId.One)?.id)
        assertFalse(last.isGolden(banana))
        assertFalse(last.isGolden(pineappleA))
        assertFalse(last.isGolden(GameSetup.pieceId(PlayerId.Two, "fruit", 3)))

        val standard = GameSetup.newGame().removing(pineappleA)
        assertFalse(standard.isGolden(pineappleB))
        assertNull(standard.goldenPineapple(PlayerId.One))
    }

    @Test
    fun `blenders always see walls and fruit see the match's edges`() {
        val state = GameSetup.newGame(boardWrap = BoardWrap.All)

        assertEquals(BoardWrap.Flat, state.edgesFor(checkNotNull(state.piece(blender))))
        assertEquals(BoardWrap.All, state.edgesFor(checkNotNull(state.piece(banana))))
    }

    @Test
    fun `blender lookups separate standing from destroyed`() {
        val state = GameSetup.newGame().withPosition(null, blender)
        val respawning = state.withRespawnTurn(5, blender).updated(turnNumber = 3)

        assertEquals(2, state.blenders(PlayerId.One).size)
        assertEquals(1, state.activeBlenders(PlayerId.One).size)
        assertEquals(listOf(blender), state.destroyedBlenders(PlayerId.One).map { it.id })
        assertEquals(listOf(blender), state.destroyedBlenders.map { it.id })
        assertTrue(checkNotNull(state.piece(blender)).needsBuyingBack)
        assertNull(state.turnsUntilRespawn(checkNotNull(state.piece(blender))))
        assertTrue(checkNotNull(respawning.piece(blender)).isAwaitingRespawn)
        assertEquals(2, respawning.turnsUntilRespawn(checkNotNull(respawning.piece(blender))))
        // A spawn stays marked after its blender has left it.
        assertEquals(
            setOf(Position(5, 1), Position(5, 4), Position(0, 1), Position(0, 4)),
            state.allSpawnSquares,
        )
        assertEquals(Position(5, 1), state.blenderHomes[blender])
    }

    @Test
    fun `wildcard standing reads the progress and what is left on the board`() {
        val state = GameSetup.newGame(ruleSet = RuleSet.Wildcard)
        val fresh = state.wildcardStanding(PlayerId.One)
        assertFalse(fresh.isBananaSplitBlocked)
        assertFalse(fresh.isBlenderamaBlocked)
        assertEquals(3, fresh.bananaSplitTarget)
        assertEquals(6, fresh.blenderamaTarget)

        val noBananas = state.removing(banana).removing(GameSetup.pieceId(PlayerId.One, "fruit", 2))
        val blocked = noBananas.wildcardStanding(PlayerId.One)
        assertTrue(blocked.isBananaSplitBlocked)
        // Two fruit gone that the player did not blend themselves.
        assertTrue(blocked.isBlenderamaBlocked)

        val progress =
            WildcardProgress(
                smoothie =
                    PlayerPair(
                        SmoothieProgress(),
                    ).updated(PlayerId.One, SmoothieProgress().recording(PieceKind.Banana)),
                bananasDelivered = PlayerPair(one = 3, two = 0),
                ownFruitBlended = PlayerPair(one = 2, two = 0),
                blenderamaCountered = PlayerPair(one = false, two = true),
            )
        val running = noBananas.updated(wildcards = progress)
        val standing = running.wildcardStanding(PlayerId.One)
        assertTrue(standing.isBananaSplitComplete)
        assertFalse(standing.isBananaSplitBlocked)
        assertFalse(standing.isBlenderamaBlocked)
        assertEquals(1, standing.smoothie.collectedCount)
        assertTrue(standing.smoothie.has(PieceKind.Banana))
        assertFalse(standing.smoothie.isComplete)
        assertTrue(running.wildcardStanding(PlayerId.Two).isBlenderamaBlocked)
    }

    @Test
    fun `board geometry`() {
        assertEquals(36, Board.allPositions.size)
        assertEquals(Position(0, 0), Board.allPositions.first())
        assertEquals(Position(0, 1), Board.allPositions[1])
        assertNull(Board.step(Position(0, 0), -1, 0, BoardWrap.Flat))
        assertNull(Board.step(Position(0, 0), -1, 0, BoardWrap.Sides))
        assertEquals(Position(5, 0), Board.step(Position(0, 0), -1, 0, BoardWrap.All))
        assertEquals(Position(0, 5), Board.step(Position(0, 0), 0, -1, BoardWrap.Sides))
        assertEquals(Position(2, 3), Board.step(Position(2, 2), 0, 1, BoardWrap.Flat))
        assertEquals(1, Board.separation(0, 5, joined = true))
        assertEquals(5, Board.separation(0, 5, joined = false))
        assertEquals(4, Board.blenderSteps(Position(0, 1), Position(4, 3)))
        assertTrue(Position(0, 0).isDarkSquare)
        assertFalse(Position(0, 1).isDarkSquare)
        assertEquals(PlayerId.One.backRow, PlayerId.Two.deliveryRow)
        assertEquals(PlayerId.Two, PlayerId.One.opponent)
    }

    @Test
    fun `occupancy answers by square and is empty off the board`() {
        val occupancy = Occupancy(GameSetup.newGame())

        assertEquals(blender, occupancy[Position(5, 1)])
        assertNull(occupancy[Position(3, 3)])
        assertNull(occupancy[Position(-1, 0)])
        assertNull(occupancy[Position(0, 6)])
    }

    @Test
    fun `raw values look up their cases`() {
        assertEquals(PlayerId.Two, rawValueOf<PlayerId>("two"))
        assertEquals(WinReason.BananaSplit, rawValueOf<WinReason>("bananaSplit"))
        assertEquals(BoardWrap.Flat, rawValueOf<BoardWrap>("flat"))
        assertNull(rawValueOf<PieceKind>("Banana"))
        assertEquals("Walls", BoardWrap.Flat.displayName)
        assertEquals(Outcome.Win(PlayerId.One, WinReason.Score), Outcome.Win(PlayerId.One))
        assertEquals(PlayerId.One, Outcome.Win(PlayerId.One).winner)
        assertNull(Outcome.Draw.winner)
        assertTrue(Outcome.Win(PlayerId.Two, WinReason.Blenderama).isWinFor(PlayerId.Two))
    }
}
