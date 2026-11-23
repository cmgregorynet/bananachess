package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.BattleResolution
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.random.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlenderBattleTest {
    // Player one's blender at r3c1 runs into player two's at r2c2, in the open. Player
    // one has a strawberry standing, which is a battle strength of one.

    private val square = Position(2, 2)
    private val attacker = blender("one.blender.0", PlayerId.One, Position(3, 1), home = Position(5, 1))
    private val defender = blender("two.blender.0", PlayerId.Two, square, home = Position(0, 1))
    private val attack = Move(attacker.id, Position(3, 1), square)
    private val oneFruit = fruit("one.strawberry.0", PlayerId.One, PieceKind.Strawberry, 4, 4)
    private val twoStrawberry = fruit("two.strawberry.0", PlayerId.Two, PieceKind.Strawberry, 1, 4)
    private val twoStrawberryTwo = fruit("two.strawberry.1", PlayerId.Two, PieceKind.Strawberry, 1, 5)
    private val twoPineapple = fruit("two.pineapple.0", PlayerId.Two, PieceKind.Pineapple, 1, 0)

    @Test
    fun `constants match the Swift engine`() {
        assertEquals(1, RulesEngine.BATTLE_ATTACKER_BONUS)
    }

    @Test
    fun `an attacker ahead on the total destroys the defender and takes the square`() {
        val state = battle(twoStrawberry)

        val transition = RulesEngine.apply(attack, state, battles = BattleResolution.Expected)

        val report = BattleReport(PlayerId.One, 1, 1, 4, defenderFruit = 1, defenderRoll = 4)
        assertTrue(report.attackerWins)
        assertEquals(
            listOf(
                GameEvent.Moved(attacker.id, Position(3, 1), square),
                GameEvent.BlenderBattle(report, square),
                GameEvent.BlenderDestroyed(defender.id, PlayerId.Two, by = attacker.id, at = square),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertEquals(square, transition.state.piece(attacker.id)?.position)
        assertNull(transition.state.piece(defender.id)?.position)
        assertEquals(Scores(), transition.state.scores)
        assertReplays(state, transition)
    }

    @Test
    fun `a tie goes to the defender`() {
        val state = battle(twoStrawberry, twoStrawberryTwo)

        val transition = RulesEngine.apply(attack, state, battles = BattleResolution.Expected)

        val report = BattleReport(PlayerId.One, 1, 1, 4, defenderFruit = 2, defenderRoll = 4)
        assertEquals(report.attackerTotal, report.defenderTotal)
        assertEquals(PlayerId.Two, report.winner)
        assertEquals(
            listOf(
                GameEvent.Moved(attacker.id, Position(3, 1), square),
                GameEvent.BlenderBattle(report, square),
                GameEvent.BlenderDestroyed(attacker.id, PlayerId.One, by = defender.id, at = square),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertAttackerLost(transition.state)
        assertReplays(state, transition)
    }

    @Test
    fun `an attacker behind on the total leaves the board and the defender keeps the square`() {
        val state = battle(twoStrawberry, twoStrawberryTwo, twoPineapple)

        val transition = RulesEngine.apply(attack, state, battles = BattleResolution.Expected)

        val report = BattleReport(PlayerId.One, 1, 1, 4, defenderFruit = 3, defenderRoll = 4)
        assertEquals(GameEvent.BlenderBattle(report, square), transition.events[1])
        assertEquals(
            GameEvent.BlenderDestroyed(attacker.id, PlayerId.One, by = defender.id, at = square),
            transition.events[2],
        )
        assertAttackerLost(transition.state)
        assertReplays(state, transition)

        // The loser is bought back like any other destroyed blender.
        val afterTwo = RulesEngine.apply(RulesEngine.legalActions(transition.state).first(), transition.state).state
        assertTrue(RestoreBlender(attacker.id, Position(5, 1)) in RulesEngine.legalActions(afterTwo))
    }

    @Test
    fun `strength counts half the fruit standing on the board, rounded down`() {
        val waitingBanana = Piece(PieceId("two.banana.0"), PlayerId.Two, PieceKind.Banana, null, home = Position(1, 3))
        val state = battle(twoStrawberry, twoPineapple, waitingBanana)

        val transition = RulesEngine.apply(attack, state, battles = BattleResolution.Expected)

        val report = (transition.events[1] as GameEvent.BlenderBattle).report
        assertEquals(1, report.attackerFruit)
        assertEquals(2, report.defenderFruit)
    }

    @Test
    fun `dice come from the position before the move and the defender's square`() {
        val rolls = mutableSetOf<Int>()
        val results = mutableSetOf<String>()

        for (score in 0 until 200) {
            val state = battle(twoStrawberry, scores = Scores(one = score, two = 0))
            val base = state.positionKey + (square.row * 6 + square.column).toULong()
            val attackerRoll = (SplitMix64(base + 0x9E3779B9uL).next() % 6uL).toInt() + 1
            val defenderRoll = (SplitMix64(base + 0x85EBCA6BuL).next() % 6uL).toInt() + 1

            val transition = RulesEngine.apply(attack, state)

            val report = BattleReport(PlayerId.One, 1, 1, attackerRoll, defenderFruit = 1, defenderRoll = defenderRoll)
            assertEquals(GameEvent.BlenderBattle(report, square), transition.events[1])
            val loser = if (attackerRoll + 2 > defenderRoll + 1) defender else attacker
            assertNull(transition.state.piece(loser.id)?.position)
            assertEquals(square, transition.state.pieceAt(square)?.position)
            assertEquals(1, transition.state.destroyedBlenders.size)
            assertReplays(state, transition)

            rolls += attackerRoll
            rolls += defenderRoll
            results +=
                when {
                    report.attackerWins -> "won"
                    report.attackerTotal == report.defenderTotal -> "tied"
                    else -> "lost"
                }
        }

        assertEquals((1..6).toSet(), rolls)
        assertEquals(setOf("won", "tied", "lost"), results)
    }

    @Test
    fun `the same move from the same position always rolls the same battle`() {
        val state = battle(twoStrawberry, twoPineapple, scores = Scores(one = 4, two = -3))

        assertEquals(RulesEngine.apply(attack, state), RulesEngine.apply(attack, state))
    }

    @Test
    fun `a blender running into its owner's blender never battles`() {
        val friend = blender("one.blender.1", PlayerId.One, square, home = Position(5, 4))
        val state =
            GameState(
                pieces = listOf(attacker, friend, oneFruit, twoStrawberry, twoStrawberryTwo, twoPineapple),
                blenderBattles = true,
            )

        val transition = RulesEngine.apply(attack, state)

        assertEquals(
            listOf(
                GameEvent.Moved(attacker.id, Position(3, 1), square),
                GameEvent.BlenderDestroyed(friend.id, PlayerId.One, by = attacker.id, at = square),
                GameEvent.TurnChanged(PlayerId.Two),
            ),
            transition.events,
        )
        assertEquals(square, transition.state.piece(attacker.id)?.position)
    }

    @Test
    fun `with battles off a collision is the plain kill however the fruit stands`() {
        val state = battle(twoStrawberry, twoStrawberryTwo, twoPineapple).updated(blenderBattles = false)

        val transition = RulesEngine.apply(attack, state)

        assertTrue(transition.events.none { it is GameEvent.BlenderBattle })
        assertEquals(square, transition.state.piece(attacker.id)?.position)
        assertNull(transition.state.piece(defender.id)?.position)
    }

    private fun assertAttackerLost(state: GameState) {
        assertNull(state.piece(attacker.id)?.position)
        assertTrue(state.piece(attacker.id)?.needsBuyingBack == true)
        assertEquals(square, state.piece(defender.id)?.position)
    }

    private fun battle(
        vararg twoFruit: Piece,
        scores: Scores = Scores(),
    ): GameState =
        GameState(
            pieces = listOf(attacker, defender, oneFruit) + twoFruit,
            scores = scores,
            blenderBattles = true,
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
