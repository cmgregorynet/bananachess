package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A port of `Engine/Tests/BananaChessEngineTests/CPUTests.swift`, plus the checks the
 * injected clock makes possible. Only the hard-cap test reads the real clock; everything
 * else runs on a fake one, so no result depends on how busy the machine is.
 */
class CpuPlayerTest {
    // Ported from the Swift suite

    @Test
    fun `every difficulty returns a legal move and never mutates its snapshot`() {
        val state = afterSixPlies()
        // Built separately, so it shares no list with the state handed to the CPU.
        val snapshot = afterSixPlies()
        val description = state.toString()

        for (difficulty in CpuDifficulty.entries) {
            val cpu = CpuPlayer(difficulty, seed = 42uL, clock = SteppingClock()::read)
            val move = assertNotNull(cpu.selectMove(state), "$difficulty")

            assertTrue(move in RulesEngine.legalMoves(state), "$difficulty played $move")
            assertEquals(snapshot, state, "$difficulty must not touch the state it was handed")
            assertEquals(description, state.toString(), "$difficulty")
            RulesEngine.apply(move, state)
        }
    }

    @Test
    fun `a finished match yields no CPU move`() {
        val builder = BoardBuilder()
        builder.blender(PlayerId.One, Position(3, 3), home = Position(5, 1))
        val state = builder.state().updated(status = GameStatus.Ended(Outcome.Draw))

        for (difficulty in CpuDifficulty.entries) {
            assertNull(CpuPlayer(difficulty).selectMove(state), "$difficulty")
            assertNull(CpuPlayer(difficulty).selectAction(state), "$difficulty")
        }
    }

    @Test
    fun `Easy is reproducible from a seed`() {
        val state = GameSetup.newGame()
        val first = CpuPlayer(CpuDifficulty.Easy, seed = 99uL).selectMove(state)
        val second = CpuPlayer(CpuDifficulty.Easy, seed = 99uL).selectMove(state)
        val other = CpuPlayer(CpuDifficulty.Easy, seed = 1234uL).selectMove(state)

        assertNotNull(first)
        assertEquals(first, second)
        assertNotEquals(first, other, "different seeds should not lock onto the same move")
    }

    @Test
    fun `a seeded Easy game replays move for move`() {
        val one = CpuPlayer(CpuDifficulty.Easy, seed = 5uL)
        val two = CpuPlayer(CpuDifficulty.Easy, seed = 6uL)
        val first = playOut(GameSetup.newGame(), one, two)
        val second = playOut(GameSetup.newGame(), one, two)

        assertEquals(first, second)
        assertEquals(first.history, second.history)
        assertTrue(first.history.isNotEmpty())
    }

    @Test
    fun `Medium takes an available delivery over a neutral shuffle`() {
        val builder = BoardBuilder()
        val pineapple = builder.fruit(PieceKind.Pineapple, PlayerId.One, Position(1, 2))
        builder.fruit(PieceKind.Banana, PlayerId.One, Position(4, 5))
        builder.fruit(PieceKind.Banana, PlayerId.Two, Position(3, 0))
        builder.blender(PlayerId.Two, Position(5, 5), home = Position(0, 4))

        val move = medium(seed = 3uL).selectMove(builder.state())

        assertEquals(Move(pineapple, Position(1, 2), Position(0, 2)), move)
    }

    @Test
    fun `Medium takes a free blend over a neutral shuffle`() {
        val builder = BoardBuilder()
        val blender = builder.blender(PlayerId.One, Position(3, 3), home = Position(5, 1))
        builder.fruit(PieceKind.Banana, PlayerId.One, Position(5, 5))
        builder.fruit(PieceKind.Pineapple, PlayerId.Two, Position(2, 4))

        val move = medium(seed = 3uL).selectMove(builder.state())

        assertEquals(Move(blender, Position(3, 3), Position(2, 4)), move)
    }

    @Test
    fun `Hard finds a two-move forced delivery`() {
        val builder = BoardBuilder()
        val strawberry = builder.fruit(PieceKind.Strawberry, PlayerId.One, Position(2, 2))
        builder.fruit(PieceKind.Banana, PlayerId.One, Position(5, 5))
        builder.fruit(PieceKind.Banana, PlayerId.Two, Position(4, 0))
        builder.blender(PlayerId.Two, Position(5, 3), home = Position(0, 4))

        val cpu = CpuPlayer(CpuDifficulty.Hard, seed = 11uL, clock = SteppingClock()::read)

        assertEquals(Move(strawberry, Position(2, 2), Position(1, 2)), cpu.selectMove(builder.state()))
    }

    @Test
    fun `every difficulty answers within its hard cap from the opening`() {
        val state = GameSetup.newGame()
        for (difficulty in CpuDifficulty.entries) {
            val start = System.nanoTime()
            val action = CpuPlayer(difficulty, seed = 1uL).selectAction(state)
            val elapsed = (System.nanoTime() - start).nanoseconds

            assertNotNull(action, "$difficulty")
            val limit = difficulty.plan.hardTimeCap + 250.milliseconds
            assertTrue(elapsed < limit, "$difficulty took $elapsed against a limit of $limit")
        }
    }

    @Test
    fun `the CPU does not blend its own fruit when anything else is available`() {
        // A blender surrounded by its owner's own fruit: every diagonal is a legal
        // self-blend, and every one of them loses points.
        val builder = BoardBuilder()
        val blender = builder.blender(PlayerId.One, Position(3, 3), home = Position(5, 1))
        for (square in listOf(Position(2, 2), Position(2, 4), Position(4, 2), Position(4, 4))) {
            builder.fruit(PieceKind.Strawberry, PlayerId.One, square)
        }
        builder.fruit(PieceKind.Banana, PlayerId.One, Position(0, 5))
        builder.fruit(PieceKind.Banana, PlayerId.Two, Position(5, 0))
        val state = builder.state()

        for (difficulty in CpuDifficulty.entries - CpuDifficulty.Easy) {
            val cpu = CpuPlayer(difficulty, seed = 2uL, clock = SteppingClock()::read)
            val move = assertNotNull(cpu.selectMove(state), "$difficulty")
            val result = RulesEngine.apply(move, state)

            assertNotEquals(blender, move.pieceId, "$difficulty: there is a free fruit move")
            assertTrue(result.state.scores.one >= 0, "$difficulty")
        }
    }

    @Test
    fun `the CPU will consider a self-blend`() {
        // Blending its own banana is legal and costs a point; the CPU must at least
        // evaluate such moves and not treat them as unavailable.
        val builder = BoardBuilder()
        val blender = builder.blender(PlayerId.One, Position(3, 3), home = Position(5, 1))
        builder.fruit(PieceKind.Banana, PlayerId.One, Position(2, 2))
        builder.fruit(PieceKind.Banana, PlayerId.Two, Position(1, 5))
        val state = builder.state()

        val legal = RulesEngine.legalMoves(state)

        assertTrue(Move(blender, Position(3, 3), Position(2, 2)) in legal, "the move is available to the search")
        assertTrue(medium(seed = 2uL).selectMove(state) in legal)
    }

    @Test
    fun `Medium beats Easy over a series`() {
        var mediumWins = 0
        var easyWins = 0

        for (seed in 1uL..6uL) {
            val easy = CpuPlayer(CpuDifficulty.Easy, seed = seed * 31uL)
            // Alternate who moves first so neither side gets a structural edge.
            val starting = if (seed % 2uL == 0uL) PlayerId.One else PlayerId.Two
            val final = playOut(GameSetup.newGame(startingPlayer = starting), one = medium(seed), two = easy)

            if (final.outcome?.isWinFor(PlayerId.One) == true) mediumWins += 1
            if (final.outcome?.isWinFor(PlayerId.Two) == true) easyWins += 1
        }

        assertTrue(mediumWins > easyWins, "medium won $mediumWins, easy won $easyWins")
    }

    @Test
    fun `self-play matches terminate and never overlap pieces`() {
        var overlapped = false
        val final =
            playOut(GameSetup.newGame(), one = medium(seed = 17uL), two = medium(seed = 18uL)) { state ->
                val squares = state.pieces.mapNotNull { it.position }
                if (squares.toSet().size != squares.size) overlapped = true
            }

        assertFalse(overlapped)
        assertTrue(final.status.isOver, "match stalled after the turn limit")
        assertTrue(RulesEngine.isFruitDepleted(final), "one side ran out of fruit, which ends it")
    }

    // Plans

    @Test
    fun `the shipped plans match the Swift engine`() {
        assertEquals(
            SearchPlan(
                depth = 1,
                candidateFraction = 0.5,
                softTimeBudget = 50.milliseconds,
                hardTimeCap = 600.milliseconds,
            ),
            CpuDifficulty.Easy.plan,
        )
        assertEquals(
            SearchPlan(
                depth = 4,
                candidateFraction = 0.0,
                softTimeBudget = 220.milliseconds,
                hardTimeCap = 450.milliseconds,
                tempoMargin = 48,
            ),
            CpuDifficulty.Medium.plan,
        )
        assertEquals(
            SearchPlan(
                depth = 12,
                candidateFraction = 0.0,
                softTimeBudget = 1_500.milliseconds,
                hardTimeCap = 2_000.milliseconds,
            ),
            CpuDifficulty.Hard.plan,
        )
        assertEquals(
            SearchPlan(
                depth = 12,
                candidateFraction = 0.0,
                softTimeBudget = 2_000.milliseconds,
                hardTimeCap = 4_000.milliseconds,
                quiescenceDepth = 8,
            ),
            CpuDifficulty.Brutal.plan,
        )
        for (difficulty in CpuDifficulty.entries) {
            assertEquals(SearchPlan.standard(difficulty), CpuPlayer(difficulty).plan)
        }
    }

    @Test
    fun `a candidate fraction of one plays any legal move, chosen by the seed`() {
        val state = GameSetup.newGame()
        val anyMove = CpuDifficulty.Easy.plan.copy(candidateFraction = 1.0)
        val picks =
            (1uL..40uL).map { seed ->
                val action = CpuPlayer(CpuDifficulty.Easy, seed = seed, plan = anyMove).selectAction(state)
                assertEquals(action, CpuPlayer(CpuDifficulty.Easy, seed = seed, plan = anyMove).selectAction(state))
                assertNotNull(action)
            }

        val legal = RulesEngine.legalActions(state)
        assertTrue(legal.containsAll(picks))
        // The stronger half of the opening moves is seven of fourteen; a random pick roams wider.
        assertTrue(picks.toSet().size > legal.size / 2, "only ${picks.toSet().size} different moves")
    }

    @Test
    fun `Easy picks among the better half of what one ply shows`() {
        val state = GameSetup.newGame()
        val legal = RulesEngine.legalActions(state)
        val betterHalf = rankedByOnePly(legal, state).take((legal.size + 1) / 2).toSet()

        val picks = (1uL..40uL).map { CpuPlayer(CpuDifficulty.Easy, seed = it).selectAction(state) }

        assertTrue(betterHalf.containsAll(picks))
        assertTrue(picks.toSet().size > 1, "Easy should not always play the same opening move")
    }

    // The clock

    @Test
    fun `a fixed seed and a clock that never expires give every level the same action each time`() {
        // Small enough that the deepest plan runs to a forced result with no clock to stop it.
        val delivery = BoardBuilder()
        delivery.fruit(PieceKind.Strawberry, PlayerId.One, Position(2, 2))
        delivery.fruit(PieceKind.Banana, PlayerId.One, Position(5, 5))
        delivery.fruit(PieceKind.Banana, PlayerId.Two, Position(4, 0))
        delivery.blender(PlayerId.Two, Position(5, 3), home = Position(0, 4))
        val chase = BoardBuilder()
        chase.fruit(PieceKind.Strawberry, PlayerId.One, Position(2, 2))
        chase.fruit(PieceKind.Banana, PlayerId.Two, Position(3, 4))
        chase.blender(PlayerId.One, Position(4, 1), home = Position(5, 1))

        for (state in listOf(delivery.state(), chase.state(), chase.state(active = PlayerId.Two))) {
            for (difficulty in CpuDifficulty.entries) {
                val analyses = List(3) { CpuPlayer(difficulty, seed = 8uL, clock = { 0L }).analyse(state) }

                assertNotNull(analyses[0].action, "$difficulty")
                assertEquals(listOf(analyses[0]), analyses.distinct(), "$difficulty")
            }
        }
    }

    @Test
    fun `a fixed seed and a deterministic clock give every level the same action from the opening`() {
        val state = GameSetup.newGame()
        for (difficulty in CpuDifficulty.entries) {
            val first = CpuPlayer(difficulty, seed = 8uL, clock = SteppingClock()::read).analyse(state)
            val second = CpuPlayer(difficulty, seed = 8uL, clock = SteppingClock()::read).analyse(state)

            assertNotNull(first.action, "$difficulty")
            assertEquals(first, second, "$difficulty")
        }
    }

    @Test
    fun `no iteration starts once the soft budget has passed`() {
        val state = GameSetup.newGame()
        // The first reading starts the clock; every later one is past every deadline.
        val expired = ReadingsClock(freshReadings = 1)

        val analysis = CpuPlayer(CpuDifficulty.Hard, seed = 1uL, clock = expired::read).analyse(state)

        assertEquals(0, analysis.completedDepth)
        assertEquals(0, analysis.nodes)
        // With nothing searched, the statically best-ranked legal move stands.
        val expected =
            RulesEngine
                .legalActions(
                    state,
                ).maxWith(compareBy<GameAction>({ Search.staticRank(it, state) }, { it }))
        assertEquals(expected, analysis.action)
    }

    @Test
    fun `an iteration cut short by the hard cap is discarded`() {
        val state = GameSetup.newGame()

        fun toDepth(depth: Int): CpuPlayer.Analysis {
            val plan = CpuDifficulty.Hard.plan.copy(depth = depth)
            return CpuPlayer(CpuDifficulty.Hard, seed = 1uL, plan = plan, clock = { 0L }).analyse(state)
        }
        val threePly = toDepth(3)
        val fourPly = toDepth(4)
        assertEquals(3, threePly.completedDepth)
        assertEquals(4, fourPly.completedDepth)
        // Depth three is finished before the first poll of the hard cap; depth four is not.
        assertTrue(threePly.nodes < Search.TIME_CHECK_INTERVAL, "depth three took ${threePly.nodes} nodes")
        assertTrue(fourPly.nodes > Search.TIME_CHECK_INTERVAL, "depth four took ${fourPly.nodes} nodes")

        // Fresh for the start and the three soft checks, then expired at the first hard poll.
        val clock = ReadingsClock(freshReadings = 4)
        val cutShort = CpuPlayer(CpuDifficulty.Hard, seed = 1uL, clock = clock::read).analyse(state)

        assertEquals(3, cutShort.completedDepth)
        assertEquals(threePly.action, cutShort.action)
        assertEquals(Search.TIME_CHECK_INTERVAL, cutShort.nodes)
    }

    @Test
    fun `the search stops at the plan's depth when the clock allows more`() {
        val state = afterSixPlies()
        for (difficulty in listOf(CpuDifficulty.Medium, CpuDifficulty.Hard, CpuDifficulty.Brutal)) {
            val plan = difficulty.plan.copy(depth = 3)
            val analysis = CpuPlayer(difficulty, seed = 1uL, plan = plan, clock = { 0L }).analyse(state)

            assertEquals(3, analysis.completedDepth, "$difficulty")
            assertTrue(analysis.action in RulesEngine.legalActions(state), "$difficulty")
        }
    }

    // Support

    /** Medium on a clock that never expires: always its full four plies, whatever the machine is doing. */
    private fun medium(seed: ULong): CpuPlayer = CpuPlayer(CpuDifficulty.Medium, seed = seed, clock = { 0L })

    /** A few plies in, so the position is not the trivial opening. */
    private fun afterSixPlies(): GameState {
        var state = GameSetup.newGame()
        repeat(6) {
            val move = assertNotNull(medium(seed = 7uL).selectMove(state))
            state = RulesEngine.apply(move, state).state
        }
        return state
    }

    private fun rankedByOnePly(
        actions: List<GameAction>,
        state: GameState,
    ): List<GameAction> {
        val values =
            actions.associateWith { action ->
                Evaluation.score(assertNotNull(state.searchedAfter(action)), state.activePlayer)
            }
        return actions.sortedWith(compareBy<GameAction>({ values.getValue(it) }, { it }).reversed())
    }

    private fun playOut(
        state: GameState,
        one: CpuPlayer,
        two: CpuPlayer,
        turnLimit: Int = 600,
        onEachTurn: (GameState) -> Unit = {},
    ): GameState {
        var current = state
        var turns = 0
        while (!current.status.isOver && turns < turnLimit) {
            val cpu = if (current.activePlayer == PlayerId.One) one else two
            val action = cpu.selectAction(current) ?: break
            current =
                try {
                    RulesEngine.apply(action, current).state
                } catch (_: RulesError) {
                    break
                }
            onEachTurn(current)
            turns += 1
        }
        return current
    }

    /** Twenty milliseconds pass on every reading, so a search is cut off after a fixed amount of work. */
    private class SteppingClock {
        private var now = 0L

        fun read(): Long {
            now += 20.milliseconds.inWholeNanoseconds
            return now
        }
    }

    /** Stands still for [freshReadings] readings, then jumps past every deadline. */
    private class ReadingsClock(
        private val freshReadings: Int,
    ) {
        private var readings = 0

        fun read(): Long {
            readings += 1
            return if (readings <= freshReadings) 0L else Long.MAX_VALUE / 2
        }
    }

    /** Builds arbitrary positions, with the piece ids the Swift test builder gives them. */
    private class BoardBuilder {
        private val pieces = mutableListOf<Piece>()
        private var fruitCount = 0
        private var blenderCount = 0

        fun fruit(
            kind: PieceKind,
            owner: PlayerId,
            at: Position,
        ): PieceId {
            val id = PieceId("${owner.rawValue}.fruit.${fruitCount++}")
            pieces += Piece(id, owner, kind, position = at, home = at)
            return id
        }

        fun blender(
            owner: PlayerId,
            at: Position,
            home: Position,
        ): PieceId {
            val id = PieceId("${owner.rawValue}.blender.${blenderCount++}")
            pieces += Piece(id, owner, PieceKind.Blender, position = at, home = home)
            return id
        }

        fun state(active: PlayerId = PlayerId.One): GameState = GameState(pieces = pieces, activePlayer = active)
    }
}
