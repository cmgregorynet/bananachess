package com.charlesbarton.bananachess.engine.rules

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
import com.charlesbarton.bananachess.engine.model.PlayerPair
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.SmoothieProgress
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.model.wildcardStanding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WildcardRulesTest {
    // Player one delivers on row 0 and player two on row 5. Blender spawns are r5c1 and
    // r5c4 for player one, r0c1 and r0c4 for player two.

    private val oneStrawberry = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 4, 4)
    private val twoStrawberry = fruit("two.strawberry.0", PlayerId.Two, PieceKind.Strawberry, 1, 0)
    private val oneBlender = blender("one.blender.0", PlayerId.One, Position(3, 1), home = Position(5, 1))
    private val twoBlender = blender("two.blender.0", PlayerId.Two, Position(2, 1), home = Position(0, 1))

    @Test
    fun `constants match the Swift engine`() {
        assertEquals(3, RulesEngine.SMOOTHIE_AWARD)
        assertEquals(2, RulesEngine.SMOOTHIE_BLACKOUT_TURNS)
    }

    // Golden Pineapple

    @Test
    fun `losing a pineapple turns the survivor golden`() {
        val taken = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 3, 2)
        val survivor = fruit("one.pineapple.1", PlayerId.One, PieceKind.Pineapple, 4, 5)
        val state = wildcard(taken, survivor, twoBlender, twoStrawberry, active = PlayerId.Two)
        assertFalse(state.isGolden(survivor.id))

        val transition = play(Move(twoBlender.id, Position(2, 1), Position(3, 2)), state)

        // The pineapple that was taken was not golden: there were still two of them.
        assertEquals(
            listOf(
                GameEvent.Moved(twoBlender.id, Position(2, 1), Position(3, 2)),
                GameEvent.Blended(taken.id, PieceKind.Pineapple, PlayerId.One, Position(3, 2), PlayerId.Two, 3),
                GameEvent.ScoreChanged(PlayerId.Two, 3),
                GameEvent.PineappleTurnedGolden(survivor.id, PlayerId.One),
                GameEvent.TurnChanged(PlayerId.One),
            ),
            transition.events,
        )
        assertTrue(transition.state.isGolden(survivor.id))
    }

    @Test
    fun `a pineapple that was already golden is not announced again`() {
        val golden = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 4, 5)
        val state = wildcard(golden, oneStrawberry, twoStrawberry)

        val transition = play(Move(oneStrawberry.id, Position(4, 4), Position(3, 4)), state)

        assertTrue(transition.events.none { it is GameEvent.PineappleTurnedGolden })
    }

    @Test
    fun `a standard match has no golden pineapple`() {
        val taken = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 3, 2)
        val survivor = fruit("one.pineapple.1", PlayerId.One, PieceKind.Pineapple, 1, 3)
        val state = GameState(pieces = listOf(taken, survivor, twoBlender, twoStrawberry), activePlayer = PlayerId.Two)

        val blended = play(Move(twoBlender.id, Position(2, 1), Position(3, 2)), state)
        assertTrue(blended.events.none { it is GameEvent.PineappleTurnedGolden })

        val delivered = play(Move(survivor.id, Position(1, 3), Position(0, 3)), blended.state)
        assertTrue(
            GameEvent.Delivered(survivor.id, PieceKind.Pineapple, PlayerId.One, Position(0, 3), 6) in delivered.events,
        )
    }

    @Test
    fun `a golden pineapple is worth double through a blender, to either side`() {
        val golden = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 3, 2)

        val stolen =
            play(
                Move(twoBlender.id, Position(2, 1), Position(3, 2)),
                wildcard(golden, oneStrawberry, twoBlender, twoStrawberry, active = PlayerId.Two),
            )
        assertEquals(Scores(one = 0, two = 6), stolen.state.scores)
        assertTrue(stolen.events.any { it is GameEvent.Blended && it.points == 6 })

        val own = blender("one.blender.0", PlayerId.One, Position(4, 1), home = Position(5, 1))
        val selfBlended =
            play(Move(own.id, Position(4, 1), Position(3, 2)), wildcard(golden, oneStrawberry, own, twoStrawberry))
        assertEquals(Scores(one = -6, two = 0), selfBlended.state.scores)
    }

    @Test
    fun `a golden pineapple delivered pays three times its value`() {
        val golden = fruit("one.pineapple.0", PlayerId.One, PieceKind.Pineapple, 1, 3)
        val state = wildcard(golden, oneStrawberry, twoStrawberry)

        val transition = play(Move(golden.id, Position(1, 3), Position(0, 3)), state)

        assertTrue(
            GameEvent.Delivered(golden.id, PieceKind.Pineapple, PlayerId.One, Position(0, 3), 9) in transition.events,
        )
        assertEquals(Scores(one = 9, two = 0), transition.state.scores)
        assertNull(transition.state.piece(golden.id))
    }

    // Banana Split

    private val banana = Piece(PieceId("one.banana.0"), PlayerId.One, PieceKind.Banana, Position(1, 3), Position(4, 3))
    private val deliverBanana = Move(banana.id, Position(1, 3), Position(0, 3))

    @Test
    fun `a delivered banana steps off the board and comes home at the turn boundary`() {
        val state = wildcard(banana, oneStrawberry, twoStrawberry)

        val transition = play(deliverBanana, state)

        assertEquals(
            listOf(
                GameEvent.Moved(banana.id, Position(1, 3), Position(0, 3)),
                GameEvent.Delivered(banana.id, PieceKind.Banana, PlayerId.One, Position(0, 3), 2),
                GameEvent.ScoreChanged(PlayerId.One, 2),
                GameEvent.BananaReturned(banana.id, Position(4, 3)),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertEquals(Position(4, 3), transition.state.piece(banana.id)?.position)
        assertEquals(PlayerPair(1, 0), transition.state.wildcards.bananasDelivered)
    }

    @Test
    fun `a banana whose home is taken waits off the board until it clears`() {
        val squatter = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 4, 3)
        val state = wildcard(banana, squatter, twoStrawberry)

        val delivered = play(deliverBanana, state)
        assertTrue(delivered.events.none { it is GameEvent.BananaReturned })
        assertEquals(listOf(banana.copy(position = null)), delivered.state.waitingBananas(PlayerId.One))
        // In transit still counts as fruit in play.
        assertFalse(RulesEngine.isFruitDepleted(delivered.state))

        val twoMoved = play(Move(twoStrawberry.id, Position(1, 0), Position(2, 0)), delivered.state)
        assertTrue(twoMoved.events.none { it is GameEvent.BananaReturned })

        val cleared = play(Move(squatter.id, Position(4, 3), Position(4, 4)), twoMoved.state)
        assertEquals(
            listOf(
                GameEvent.Moved(squatter.id, Position(4, 3), Position(4, 4)),
                GameEvent.BananaReturned(banana.id, Position(4, 3)),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            cleared.events,
        )
    }

    @Test
    fun `a third banana delivery wins outright, before the banana comes home`() {
        val state =
            wildcard(
                banana,
                oneStrawberry,
                twoStrawberry,
                scores = Scores(one = 0, two = 20),
                progress = WildcardProgress(bananasDelivered = PlayerPair(2, 0)),
            )

        val transition = play(deliverBanana, state)

        val win = Outcome.Win(PlayerId.One, WinReason.BananaSplit)
        assertEquals(
            listOf(
                GameEvent.Moved(banana.id, Position(1, 3), Position(0, 3)),
                GameEvent.Delivered(banana.id, PieceKind.Banana, PlayerId.One, Position(0, 3), 2),
                GameEvent.ScoreChanged(PlayerId.One, 2),
                GameEvent.GameEnded(win),
            ),
            transition.events,
        )
        assertEquals(GameStatus.Ended(win), transition.state.status)
        assertEquals(1, transition.state.turnNumber)
        assertEquals(PlayerId.One, transition.state.activePlayer)
        assertNull(transition.state.piece(banana.id)?.position)
        assertEquals(emptyList(), RulesEngine.legalActions(transition.state))
    }

    @Test
    fun `a standard banana is consumed by delivery and counts toward nothing`() {
        val state = GameState(pieces = listOf(banana, oneStrawberry, twoStrawberry))

        val transition = play(deliverBanana, state)

        assertNull(transition.state.piece(banana.id))
        assertEquals(WildcardProgress(), transition.state.wildcards)
    }

    // Blenderama

    private val selfBlender = blender("one.blender.0", PlayerId.One, Position(4, 1), home = Position(5, 1))
    private val ownFruit = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 3, 2)
    private val spareFruit = fruit("one.banana.0", PlayerId.One, PieceKind.Banana, 4, 5)
    private val selfBlend = Move(selfBlender.id, Position(4, 1), Position(3, 2))

    @Test
    fun `a sixth self-blend wins by Blenderama whatever the score`() {
        val state =
            wildcard(
                selfBlender,
                ownFruit,
                spareFruit,
                twoStrawberry,
                progress = WildcardProgress(ownFruitBlended = PlayerPair(5, 0)),
            )

        val transition = play(selfBlend, state)

        val win = Outcome.Win(PlayerId.One, WinReason.Blenderama)
        assertEquals(
            listOf(
                GameEvent.Moved(selfBlender.id, Position(4, 1), Position(3, 2)),
                GameEvent.Blended(ownFruit.id, PieceKind.Strawberry, PlayerId.One, Position(3, 2), PlayerId.One, -2),
                GameEvent.ScoreChanged(PlayerId.One, -2),
                GameEvent.GameEnded(win),
            ),
            transition.events,
        )
        assertEquals(GameStatus.Ended(win), transition.state.status)
        assertEquals(Scores(one = -2, two = 0), transition.state.scores)
    }

    @Test
    fun `a countered Blenderama does not win`() {
        val state =
            wildcard(
                selfBlender,
                ownFruit,
                spareFruit,
                twoStrawberry,
                progress =
                    WildcardProgress(
                        ownFruitBlended = PlayerPair(5, 0),
                        blenderamaCountered = PlayerPair(one = true, two = false),
                    ),
            )

        val transition = play(selfBlend, state)

        assertEquals(GameStatus.InProgress, transition.state.status)
        assertEquals(GameEvent.TurnChanged(PlayerId.Two), transition.events.last())
        assertEquals(PlayerPair(6, 0), transition.state.wildcards.ownFruitBlended)
    }

    @Test
    fun `a self-blend counts toward Blenderama and counters nobody`() {
        val state = wildcard(selfBlender, ownFruit, spareFruit, twoStrawberry)

        val transition = play(selfBlend, state)

        assertEquals(PlayerPair(1, 0), transition.state.wildcards.ownFruitBlended)
        assertEquals(PlayerPair(false), transition.state.wildcards.blenderamaCountered)
        assertEquals(SmoothieProgress(strawberry = true), transition.state.wildcards.smoothie[PlayerId.One])
    }

    @Test
    fun `the opponent scoring at all takes Blenderama off`() {
        val runner = fruit("two.strawberry.0", PlayerId.Two, PieceKind.Strawberry, 4, 0)
        val twoSpare = fruit("two.banana.0", PlayerId.Two, PieceKind.Banana, 1, 5)
        val state = wildcard(selfBlender, ownFruit, spareFruit, runner, twoSpare, active = PlayerId.Two)

        val transition = play(Move(runner.id, Position(4, 0), Position(5, 0)), state)

        assertEquals(PlayerPair(one = true, two = false), transition.state.wildcards.blenderamaCountered)
        assertTrue(transition.state.wildcardStanding(PlayerId.One).isBlenderamaBlocked)
    }

    @Test
    fun `taking the other player's fruit counters their Blenderama and counts toward no one's`() {
        val target = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 3, 2)
        val state = wildcard(target, spareFruit, twoBlender, twoStrawberry, active = PlayerId.Two)

        val transition = play(Move(twoBlender.id, Position(2, 1), Position(3, 2)), state)

        assertEquals(PlayerPair(one = true, two = false), transition.state.wildcards.blenderamaCountered)
        assertEquals(PlayerPair(0), transition.state.wildcards.ownFruitBlended)
    }

    @Test
    fun `buying a blender back counters nobody`() {
        val destroyed = blender("one.blender.0", PlayerId.One, at = null, home = Position(5, 1))
        val state = wildcard(destroyed, oneStrawberry, twoStrawberry)

        val transition = play(RestoreBlender(destroyed.id, Position(5, 1)), state)

        assertEquals(Scores(one = -3, two = 0), transition.state.scores)
        assertEquals(PlayerPair(false), transition.state.wildcards.blenderamaCountered)
    }

    // Tropical Smoothie

    private val twoPineapple = fruit("two.pineapple.0", PlayerId.Two, PieceKind.Pineapple, 2, 2)
    private val twoOtherPineapple = fruit("two.pineapple.1", PlayerId.Two, PieceKind.Pineapple, 1, 5)
    private val blendPineapple = Move(oneBlender.id, Position(3, 1), Position(2, 2))

    @Test
    fun `the third kind of fruit pours a smoothie for three points`() {
        val state =
            wildcard(
                oneBlender,
                oneStrawberry,
                twoPineapple,
                twoOtherPineapple,
                progress =
                    WildcardProgress(
                        smoothie = PlayerPair(SmoothieProgress(banana = true, strawberry = true), SmoothieProgress()),
                    ),
            )

        val transition = play(blendPineapple, state)

        assertEquals(
            listOf(
                GameEvent.Moved(oneBlender.id, Position(3, 1), Position(2, 2)),
                GameEvent.Blended(twoPineapple.id, PieceKind.Pineapple, PlayerId.Two, Position(2, 2), PlayerId.One, 3),
                GameEvent.ScoreChanged(PlayerId.One, 3),
                GameEvent.SmoothiePoured(PlayerId.One, 3),
                GameEvent.ScoreChanged(PlayerId.One, 6),
                GameEvent.PineappleTurnedGolden(twoOtherPineapple.id, PlayerId.Two),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertTrue(
            transition.state.wildcards.smoothie[PlayerId.One]
                .isComplete,
        )
        assertEquals(PlayerPair(one = true, two = false), transition.state.wildcards.smoothieFired)
    }

    @Test
    fun `a smoothie only pours once`() {
        val complete = SmoothieProgress(banana = true, strawberry = true, pineapple = true)
        val state =
            wildcard(
                oneBlender,
                oneStrawberry,
                twoPineapple,
                twoOtherPineapple,
                progress =
                    WildcardProgress(
                        smoothie = PlayerPair(complete, SmoothieProgress()),
                        smoothieFired = PlayerPair(one = true, two = false),
                    ),
            )

        val transition = play(blendPineapple, state)

        assertTrue(transition.events.none { it is GameEvent.SmoothiePoured })
        assertEquals(Scores(one = 3, two = 0), transition.state.scores)
    }

    @Test
    fun `a standard match records no wildcard progress`() {
        val state = GameState(pieces = listOf(oneBlender, oneStrawberry, twoPineapple, twoOtherPineapple))

        val transition = play(blendPineapple, state)

        assertEquals(WildcardProgress(), transition.state.wildcards)
        assertEquals(GameEvent.TurnChanged(PlayerId.Two), transition.events.last())
    }

    // The retained smoothie blast

    private val twoHomeBlender = blender("two.blender.0", PlayerId.Two, Position(2, 2), home = Position(0, 1))
    private val twoOtherBlender = blender("two.blender.1", PlayerId.Two, Position(0, 4), home = Position(0, 4))

    @Test
    fun `the retained blast takes the opponent's blenders off for a turn and they return by themselves`() {
        val state = wildcard(oneStrawberry, spareFruit, twoHomeBlender, twoOtherBlender, twoStrawberry)
        val turn = TurnInProgress(state)

        WildcardRules.pourSmoothie(PlayerId.One, turn)

        val blast = GameEvent.Smoothie(PlayerId.One, listOf(twoHomeBlender.id, twoOtherBlender.id), respawnTurn = 3)
        assertEquals(listOf<GameEvent>(blast), turn.events)
        assertEquals(state.applying(blast).pieces, turn.state.pieces)
        assertTrue(turn.state.blenders(PlayerId.Two).all { it.isAwaitingRespawn && it.respawnTurn == 3 })
        assertEquals(2, turn.state.turnsUntilRespawnOf(twoHomeBlender.id))

        val oneMoved = play(Move(oneStrawberry.id, Position(4, 4), Position(3, 4)), turn.state)
        assertTrue(oneMoved.events.none { it is GameEvent.BlenderRespawned })
        // Not for sale: these come back on their own.
        assertTrue(RulesEngine.legalActions(oneMoved.state).none { it is RestoreBlender })

        val twoMoved = play(Move(twoStrawberry.id, Position(1, 0), Position(2, 0)), oneMoved.state)
        assertEquals(
            listOf(
                GameEvent.Moved(twoStrawberry.id, Position(1, 0), Position(2, 0)),
                GameEvent.BlenderRespawned(twoHomeBlender.id, PlayerId.Two, Position(0, 1)),
                GameEvent.BlenderRespawned(twoOtherBlender.id, PlayerId.Two, Position(0, 4)),
                GameEvent.TurnChanged(PlayerId.One),
            ),
            twoMoved.events,
        )
        assertTrue(twoMoved.state.blenders(PlayerId.Two).all { it.isOnBoard && it.respawnTurn == null })
    }

    @Test
    fun `the retained blast does nothing when no blender is standing`() {
        val gone = twoHomeBlender.copy(position = null)
        val state = wildcard(oneStrawberry, gone, twoStrawberry)
        val turn = TurnInProgress(state)

        WildcardRules.pourSmoothie(PlayerId.One, turn)

        assertEquals(emptyList(), turn.events)
        assertEquals(state, turn.state)
    }

    @Test
    fun `a blender due back waits for its spawn to clear`() {
        val squatter = fruit("two.banana.0", PlayerId.Two, PieceKind.Banana, 0, 1)
        val state =
            GameState(
                pieces =
                    listOf(
                        oneStrawberry,
                        spareFruit,
                        twoHomeBlender.copy(position = null, respawnTurn = 2),
                        twoOtherBlender.copy(position = null, respawnTurn = 2),
                        squatter,
                    ),
                ruleSet = RuleSet.Wildcard,
            )

        val oneMoved = play(Move(oneStrawberry.id, Position(4, 4), Position(3, 4)), state)
        assertEquals(
            listOf(GameEvent.BlenderRespawned(twoOtherBlender.id, PlayerId.Two, Position(0, 4))),
            oneMoved.events.filterIsInstance<GameEvent.BlenderRespawned>(),
        )
        assertTrue(oneMoved.state.piece(twoHomeBlender.id)?.isAwaitingRespawn == true)

        val twoMoved = play(Move(squatter.id, Position(0, 1), Position(1, 1)), oneMoved.state)
        assertEquals(
            listOf(GameEvent.BlenderRespawned(twoHomeBlender.id, PlayerId.Two, Position(0, 1))),
            twoMoved.events.filterIsInstance<GameEvent.BlenderRespawned>(),
        )
    }

    // Helpers

    /** Applies [action] and checks that replaying its events lands on the same board. */
    private fun play(
        action: GameAction,
        state: GameState,
    ): RulesEngine.Transition = RulesEngine.apply(action, state).also { assertReplays(state, it) }

    private fun GameState.turnsUntilRespawnOf(id: PieceId): Int? = piece(id)?.let(::turnsUntilRespawn)

    private fun wildcard(
        vararg pieces: Piece,
        active: PlayerId = PlayerId.One,
        scores: Scores = Scores(),
        progress: WildcardProgress = WildcardProgress(),
    ): GameState =
        GameState(
            pieces = pieces.toList(),
            activePlayer = active,
            scores = scores,
            ruleSet = RuleSet.Wildcard,
            wildcards = progress,
        )

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
