package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.BattleResolution
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.assertRefusedWith
import com.charlesbarton.bananachess.engine.support.battleReports
import com.charlesbarton.bananachess.engine.support.blends
import com.charlesbarton.bananachess.engine.support.deliveries
import com.charlesbarton.bananachess.engine.support.destructions
import com.charlesbarton.bananachess.engine.support.idleTransition
import com.charlesbarton.bananachess.engine.support.isOffBoard
import com.charlesbarton.bananachess.engine.support.move
import com.charlesbarton.bananachess.engine.support.position
import com.charlesbarton.bananachess.engine.support.restorations
import com.charlesbarton.bananachess.engine.support.square
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class CombatFixture(
    val state: GameState,
    val mine: PieceId,
    val theirs: PieceId,
)

private data class SpawnFixture(
    val state: GameState,
    val attacker: PieceId,
    val victim: PieceId,
)

private data class RestoreFixture(
    val state: GameState,
    val destroyed: PieceId,
)

private data class BattleFixture(
    val state: GameState,
    val attacker: PieceId,
    val defender: PieceId,
)

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/ScoringTests.swift`. Each nested class
 * is one Swift suite and each test keeps its Swift function name.
 */
class ScoringTests {
    @Nested
    @DisplayName("Signed blending")
    inner class BlendingTests {
        // RU-01
        @ParameterizedTest
        @CsvSource("Banana, 1", "Strawberry, 2", "Pineapple, 3")
        @DisplayName("Blending the opponent's fruit pays its base value")
        fun opponentBlendPaysBaseValue(
            kind: PieceKind,
            expected: Int,
        ) {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val fruit = builder.fruit(kind, PlayerId.Two, at = position(2, 4))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(expected, result.state.scores.one)
            assertEquals(0, result.state.scores.two)
            assertNull(result.state.piece(fruit))
            assertEquals(position(2, 4), result.state.square(blender), "the blender stays on the square")
            assertEquals(1, result.events.blends.size)
        }

        // RU-02
        @ParameterizedTest
        @CsvSource("Banana, -1", "Strawberry, -2", "Pineapple, -3")
        @DisplayName("Blending your own fruit costs you its base value")
        fun selfBlendCostsBaseValue(
            kind: PieceKind,
            expected: Int,
        ) {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val fruit = builder.fruit(kind, PlayerId.One, at = position(2, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(0, 0))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(expected, result.state.scores.one)
            assertEquals(0, result.state.scores.two, "the other player gains nothing from it")
            assertNull(result.state.piece(fruit))
            assertEquals(position(2, 4), result.state.square(blender))
        }

        // RU-03
        @Test
        @DisplayName("Scores go below zero and are never clamped")
        fun scoresMayGoNegative() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(2, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(0, 0))
            val state = builder.state(scores = Scores(one = 1, two = 0))

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(-1, result.state.scores.one)
            assertTrue(GameEvent.ScoreChanged(PlayerId.One, newScore = -1) in result.events)
        }

        @Test
        @DisplayName("Blending never uses the delivery multiplier")
        fun blendingIgnoresDeliveryMultiplier() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(1, 3), home = position(5, 1))
            // Opponent pineapple sitting on player one's own delivery row.
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(3, 0))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(blender, to = position(0, 2)), state)

            assertEquals(3, result.state.scores.one, "base value, not six")
        }

        @Test
        @DisplayName("Blending removes only the fruit it landed on")
        fun blendRemovesOnlyTheTarget() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 4))
            val bystander = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 2))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertNotNull(result.state.piece(bystander))
            assertEquals(1, result.state.remainingFruit.size)
        }
    }

    @Nested
    @DisplayName("Delivery")
    inner class DeliveryTests {
        @ParameterizedTest
        @CsvSource("Banana, 2", "Strawberry, 4", "Pineapple, 6")
        @DisplayName("Fruit reaching the far edge scores double and leaves the board")
        fun deliveryScoresDouble(
            kind: PieceKind,
            expected: Int,
        ) {
            val builder = BoardBuilder()
            val fruit = builder.fruit(kind, PlayerId.One, at = position(1, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(4, 5))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(fruit, to = position(0, 2)), state)

            assertEquals(expected, result.state.scores.one)
            assertNull(result.state.piece(fruit))
            assertEquals(1, result.events.deliveries.size)
        }

        @Test
        @DisplayName("Delivery works identically from player two's perspective")
        fun deliveryFromPlayerTwo() {
            val builder = BoardBuilder()
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 0))
            val fruit = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 3))
            val state = builder.state(active = PlayerId.Two)

            val result = RulesEngine.apply(state.move(fruit, to = position(5, 3)), state)

            assertEquals(6, result.state.scores.two)
            assertEquals(0, result.state.scores.one)
            assertNull(result.state.piece(fruit))
        }

        @Test
        @DisplayName("Reaching your own back row is not a delivery")
        fun ownBackRowIsNotDelivery() {
            val builder = BoardBuilder()
            val fruit = builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(4, 3))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(fruit, to = position(5, 3)), state)

            assertEquals(Scores(), result.state.scores)
            assertEquals(position(5, 3), result.state.square(fruit))
        }
    }

    @Nested
    @DisplayName("Blender combat")
    inner class BlenderCombatTests {
        private fun fixture(): CombatFixture {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val theirs = builder.blender(PlayerId.Two, at = position(2, 2), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            return CombatFixture(builder.state(), mine, theirs)
        }

        /**
         * Home is safe. A blender sitting on one of its owner's spawn squares cannot be
         * taken by anything, which is what stops a buy-back being knocked straight off
         * again for another three points.
         * Player two's spawns are (0,1) and (0,4). The victim is placed wherever the test
         * needs it, with the attacker one diagonal step away.
         */
        private fun spawnFixture(
            attackerAt: Position,
            victimAt: Position,
            victimHome: Position = position(0, 1),
        ): SpawnFixture {
            val builder = BoardBuilder()
            val attacker = builder.blender(PlayerId.One, at = attackerAt, home = position(5, 1))
            val victim = builder.blender(PlayerId.Two, at = victimAt, home = victimHome)
            // Their other blender, so both of player two's spawns are real squares.
            builder.blender(PlayerId.Two, at = position(3, 5), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            return SpawnFixture(builder.state(), attacker, victim)
        }

        @Test
        @DisplayName("A blender on its own spawn cannot be taken")
        fun aBlenderOnItsSpawnIsSafe() {
            val f = spawnFixture(attackerAt = position(1, 2), victimAt = position(0, 1))
            val attack = f.state.move(f.attacker, to = position(0, 1))

            assertFalse(attack in RulesEngine.legalMoves(f.attacker, f.state))
            assertFailsWith<RulesError> { RulesEngine.apply(attack, f.state) }
            assertEquals(position(0, 1), f.state.square(f.victim), "it just stands there")
        }

        @Test
        @DisplayName("Either of the owner's spawns is home, not only the one it started on")
        fun theOtherSpawnIsAlsoSafe() {
            // Its own home is (0,1); it was bought back onto its sibling's spawn instead.
            // That square is still one of player two's, so it is still safe.
            val f =
                spawnFixture(
                    attackerAt = position(1, 3),
                    victimAt = position(0, 4),
                    victimHome = position(0, 1),
                )
            val attack = f.state.move(f.attacker, to = position(0, 4))

            assertFalse(attack in RulesEngine.legalMoves(f.attacker, f.state))
        }

        @Test
        @DisplayName("One square off the spawn and it is fair game again")
        fun steppingOffTheSpawnEndsTheProtection() {
            val f = spawnFixture(attackerAt = position(1, 2), victimAt = position(2, 1))
            val attack = f.state.move(f.attacker, to = position(2, 1))

            assertTrue(attack in RulesEngine.legalMoves(f.attacker, f.state))
            val result = RulesEngine.apply(attack, f.state)
            assertTrue(result.state.isOffBoard(f.victim))
        }

        @Test
        @DisplayName("The protection covers friendly blenders too — home is simply safe")
        fun evenItsOwnSideCannotTakeIt() {
            val builder = BoardBuilder()
            val attacker = builder.blender(PlayerId.Two, at = position(1, 2), home = position(0, 4))
            builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state(active = PlayerId.Two)

            assertFalse(state.move(attacker, to = position(0, 1)) in RulesEngine.legalMoves(attacker, state))
        }

        @Test
        @DisplayName("A blender that runs into another destroys it and takes the square")
        fun attackerDestroysAndAdvances() {
            val f = fixture()
            val result = RulesEngine.apply(f.state.move(f.mine, to = position(2, 2)), f.state)

            assertEquals(position(2, 2), result.state.square(f.mine), "the attacker survives and lands")
            assertTrue(result.state.isOffBoard(f.theirs), "the defender is destroyed")
            assertEquals(Scores(), result.state.scores, "combat itself pays nothing")
            assertEquals(1, result.events.destructions.size)
        }

        @Test
        @DisplayName("Destroying a blender wins the square and nothing else")
        fun destroyingGrantsNoExtraMove() {
            val f = fixture()
            val result = RulesEngine.apply(f.state.move(f.mine, to = position(2, 2)), f.state)

            assertEquals(PlayerId.Two, result.state.activePlayer, "the turn passes straight over")
            assertEquals(position(2, 2), result.state.square(f.mine))
            assertTrue(result.state.isOffBoard(f.theirs))
        }

        @Test
        @DisplayName("The attacker gets an ordinary turn afterwards, not a double one")
        fun noSecondStepNextTurn() {
            val f = fixture()
            var current = RulesEngine.apply(f.state.move(f.mine, to = position(2, 2)), f.state).state
            current = idleTransition(current).state
            assertEquals(PlayerId.One, current.activePlayer)

            // One diagonal step, and the turn is over.
            val step = RulesEngine.apply(current.move(f.mine, to = position(1, 1)), current)

            assertEquals(PlayerId.Two, step.state.activePlayer, "one move, one turn")
            assertTrue(
                RulesEngine.legalActions(step.state).all { action ->
                    action !is Move || step.state.piece(action.pieceId)?.owner == PlayerId.Two
                },
            )
        }

        @Test
        @DisplayName("A blender may destroy a friendly one")
        fun friendlyFireIsLegal() {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val friend = builder.blender(PlayerId.One, at = position(2, 2), home = position(5, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(mine, to = position(2, 2)), state)

            assertTrue(result.state.isOffBoard(friend))
            assertEquals(position(2, 2), result.state.square(mine))
            assertEquals(PlayerId.Two, result.state.activePlayer)
        }

        @Test
        @DisplayName("Blenders do not come back on their own")
        fun noAutomaticRespawn() {
            val f = fixture()
            var current = RulesEngine.apply(f.state.move(f.mine, to = position(2, 2)), f.state).state

            repeat(8) {
                if (current.status.isOver) return@repeat
                current = idleTransition(current).state
                assertTrue(current.isOffBoard(f.theirs), "it stays destroyed until bought back")
            }
        }
    }

    @Nested
    @DisplayName("Buying a blender back")
    inner class BlenderRestoreTests {
        private fun fixture(): RestoreFixture {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            return RestoreFixture(builder.state(scores = Scores(one = 5, two = 0)), destroyed)
        }

        @Test
        @DisplayName("It costs the whole turn and three points")
        fun costsATurnAndThreePoints() {
            val f = fixture()
            val restore = RestoreBlender(f.destroyed, at = position(5, 1))
            assertTrue(restore in RulesEngine.legalActions(f.state))

            val result = RulesEngine.apply(restore, f.state)

            assertEquals(position(5, 1), result.state.square(f.destroyed))
            assertEquals(5 - RulesEngine.BLENDER_RESTORE_COST, result.state.scores.one)
            assertEquals(PlayerId.Two, result.state.activePlayer, "it used the whole turn")
            assertEquals(1, result.events.restorations.size)
        }

        @Test
        @DisplayName("The score is allowed to go negative paying for it")
        fun scoreMayGoNegative() {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state(scores = Scores(one = 1, two = 0))

            val result = RulesEngine.apply(RestoreBlender(destroyed, at = position(5, 1)), state)

            assertEquals(-2, result.state.scores.one)
        }

        @Test
        @DisplayName("A blender standing on the board cannot be bought")
        fun onlyDestroyedBlenders() {
            val builder = BoardBuilder()
            val standing = builder.blender(PlayerId.One, at = position(5, 1), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            assertRefusedWith(RulesError.BlenderNotDestroyed(standing)) {
                RulesEngine.apply(RestoreBlender(standing, at = position(5, 1)), state)
            }
        }

        @Test
        @DisplayName("It cannot be bought back onto an occupied spawn")
        fun spawnMustBeClear() {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()
            val restore = RestoreBlender(destroyed, at = position(5, 1))

            assertRefusedWith(RulesError.SpawnOccupied(destroyed)) { RulesEngine.apply(restore, state) }
            assertFalse(restore in RulesEngine.legalActions(state))
        }

        @Test
        @DisplayName("A blender may come back on either of its owner's spawns")
        fun eitherSpawnWillDo() {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 4))
            builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            val offered =
                RulesEngine
                    .legalActions(state)
                    .filterIsInstance<RestoreBlender>()
                    .filter { it.pieceId == destroyed }
                    .map { it.at }
            assertEquals(setOf(position(5, 1), position(5, 4)), offered.toSet(), "both spawns are on offer")

            val elsewhere = RulesEngine.apply(RestoreBlender(destroyed, at = position(5, 4)), state)
            assertEquals(position(5, 4), elsewhere.state.square(destroyed))
            assertEquals(-RulesEngine.BLENDER_RESTORE_COST, elsewhere.state.scores.one)
        }

        @Test
        @DisplayName("Coming back on the other spawn switches the colour it travels on")
        fun otherSpawnSwitchesDiagonal() {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            // Its own spawn and the other one sit on opposite colours by design.
            builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 4))
            val state = builder.state()
            assertTrue(position(5, 1).isDarkSquare != position(5, 4).isDarkSquare)

            val back = RulesEngine.apply(RestoreBlender(destroyed, at = position(5, 4)), state).state

            // It now moves on the other colour: the only way a blender ever changes
            // diagonal, and the reason a player's own two blenders can now meet.
            val reachable = RulesEngine.legalMoves(destroyed, back).map { it.destination }
            assertTrue(reachable.all { it.isDarkSquare == position(5, 4).isDarkSquare })
            assertFalse(reachable.any { it.isDarkSquare == position(5, 1).isDarkSquare })
        }

        @Test
        @DisplayName("A blender cannot come back on a square that is not a spawn")
        fun onlySpawnSquares() {
            val builder = BoardBuilder()
            val destroyed = builder.destroyedBlender(PlayerId.One, home = position(5, 1))
            builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            assertRefusedWith(RulesError.NotASpawnSquare(position(3, 3))) {
                RulesEngine.apply(RestoreBlender(destroyed, at = position(3, 3)), state)
            }
        }

        @Test
        @DisplayName("Only your own blenders")
        fun notTheirs() {
            val builder = BoardBuilder()
            val theirs = builder.destroyedBlender(PlayerId.Two, home = position(0, 4))
            builder.blender(PlayerId.One, at = position(5, 1), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val state = builder.state()

            assertRefusedWith(RulesError.NotActivePlayersPiece(theirs)) {
                RulesEngine.apply(RestoreBlender(theirs, at = position(0, 4)), state)
            }
        }
    }

    @Nested
    @DisplayName("Blender battles")
    inner class BlenderBattleTests {
        /**
         * Two enemy blenders a diagonal apart, with each side's fruit set by the test so
         * the standing totals are whatever it needs them to be.
         */
        private fun fixture(
            mine: List<PieceKind>,
            theirs: List<PieceKind>,
            battles: Boolean = true,
        ): BattleFixture {
            val builder = BoardBuilder()
            val attacker = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val defender = builder.blender(PlayerId.Two, at = position(2, 2), home = position(0, 4))
            mine.forEachIndexed { index, kind -> builder.fruit(kind, PlayerId.One, at = position(5, index)) }
            theirs.forEachIndexed { index, kind -> builder.fruit(kind, PlayerId.Two, at = position(0, index)) }
            return BattleFixture(builder.state().updated(blenderBattles = battles), attacker, defender)
        }

        @Test
        @DisplayName("With battles off, a blender still simply takes the square")
        fun battlesOffIsTheOldRule() {
            val f =
                fixture(
                    mine = listOf(PieceKind.Banana),
                    theirs = listOf(PieceKind.Pineapple, PieceKind.Pineapple),
                    battles = false,
                )
            val result = RulesEngine.apply(f.state.move(f.attacker, to = position(2, 2)), f.state)

            assertTrue(result.state.isOffBoard(f.defender), "the defender is gone regardless of totals")
            assertEquals(position(2, 2), result.state.square(f.attacker))
            assertFalse(result.events.any { it is GameEvent.BlenderBattle })
        }

        @Test
        @DisplayName("An overwhelming attacker wins and takes the square")
        fun aStrongAttackerWins() {
            // Twelve of fruit against one. Halved that is 6 against 0, and with the bonus the
            // attacker's worst roll still beats the defender's best.
            val f = fixture(mine = List(4) { PieceKind.Pineapple }, theirs = listOf(PieceKind.Banana))
            val result = RulesEngine.apply(f.state.move(f.attacker, to = position(2, 2)), f.state)

            val report = assertNotNull(result.events.battleReports.firstOrNull())
            assertEquals(PlayerId.One, report.attacker)
            assertEquals(6, report.attackerFruit, "twelve of standing fruit, counted at half")
            assertEquals(RulesEngine.BATTLE_ATTACKER_BONUS, report.attackerBonus)
            assertEquals(0, report.defenderFruit, "one point of fruit rounds down to nothing")
            assertTrue(report.attackerWins)
            assertTrue(result.state.isOffBoard(f.defender))
            assertEquals(position(2, 2), result.state.square(f.attacker), "and moves in")
        }

        @Test
        @DisplayName("An overwhelmed attacker loses its own blender, and the defender does not move")
        fun aWeakAttackerLosesItsOwn() {
            // 0 plus 1 against 6: the attacker's best roll ties the defender's worst, and a
            // tie goes to the defender, so the attack cannot succeed on any roll.
            val f = fixture(mine = listOf(PieceKind.Banana), theirs = List(4) { PieceKind.Pineapple })
            val result = RulesEngine.apply(f.state.move(f.attacker, to = position(2, 2)), f.state)

            val report = assertNotNull(result.events.battleReports.firstOrNull())
            assertFalse(report.attackerWins)
            assertEquals(PlayerId.Two, report.winner)
            assertTrue(result.state.isOffBoard(f.attacker), "the attacker is the one that leaves")
            assertEquals(position(2, 2), result.state.square(f.defender), "the defender holds its square")
            assertEquals(1, result.state.destroyedBlenders(PlayerId.One).size)
            assertTrue(result.state.destroyedBlenders(PlayerId.Two).isEmpty())
        }

        @Test
        @DisplayName("Only fruit still standing counts — blended and delivered fruit does not")
        fun onlyStandingFruitCounts() {
            val builder = BoardBuilder()
            val attacker = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val defender = builder.blender(PlayerId.Two, at = position(2, 2), home = position(0, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(5, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(0, 0))
            // A fruit of theirs taken off the board without leaving the match: it is still
            // their fruit, but it is not standing on anything.
            val waiting = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 3))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard)
                    .updated(blenderBattles = true)
                    .withPosition(null, waiting)

            assertEquals(2, state.fruit(PlayerId.Two).size, "both still theirs")
            assertEquals(1, state.fruitStandingValue(PlayerId.Two), "but only the banana is standing")
            assertEquals(3, state.fruitStandingValue(PlayerId.One))

            val result = RulesEngine.apply(state.move(attacker, to = position(2, 2)), state)
            val report = assertNotNull(result.events.battleReports.firstOrNull())
            assertEquals(1, report.attackerFruit, "3 standing, counted at half")
            assertEquals(0, report.defenderFruit, "1 standing, counted at half")
            // Close enough that the dice decide it, which is the point of halving, so this
            // asserts the right blender left rather than which one it was.
            assertTrue(result.state.isOffBoard(if (report.attackerWins) defender else attacker))
        }

        @Test
        @DisplayName("A tie goes to the defender")
        fun tiesGoToTheDefender() {
            val tied =
                BattleReport(
                    attacker = PlayerId.One,
                    attackerFruit = 3,
                    attackerBonus = 1,
                    attackerRoll = 2,
                    defenderFruit = 5,
                    defenderRoll = 1,
                )
            assertEquals(tied.defenderTotal, tied.attackerTotal)
            assertFalse(tied.attackerWins, "the attacker has to actually beat the total")
            assertEquals(PlayerId.Two, tied.winner)
        }

        /**
         * The bonus and the tie rule are one dial at half-step resolution, not two: a bonus
         * of B with ties to the attacker is the identical rule to B+1 with ties to the
         * defender. Pinned so that anyone retuning one remembers they are moving both.
         */
        @Test
        @DisplayName("The bonus and the tie rule are the same dial")
        fun bonusAndTieRuleAreOneDial() {
            for (gap in -3..3) {
                for (attackerRoll in 1..6) {
                    for (defenderRoll in 1..6) {
                        val report =
                            BattleReport(
                                attacker = PlayerId.One,
                                attackerFruit = gap,
                                attackerBonus = 1,
                                attackerRoll = attackerRoll,
                                defenderFruit = 0,
                                defenderRoll = defenderRoll,
                            )
                        assertEquals(
                            gap + attackerRoll >= defenderRoll,
                            report.attackerWins,
                            "bonus 1 with ties to the defender should equal bonus 0 with ties to the attacker",
                        )
                    }
                }
            }
        }

        @Test
        @DisplayName("Running into your own blender is not a battle — there is nobody to roll against")
        fun friendlyCollisionsAreNotBattles() {
            val builder = BoardBuilder()
            val attacker = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val friend = builder.blender(PlayerId.One, at = position(2, 2), home = position(5, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 0))
            val state = builder.state().updated(blenderBattles = true)

            val result = RulesEngine.apply(state.move(attacker, to = position(2, 2)), state)

            assertFalse(result.events.any { it is GameEvent.BlenderBattle })
            assertTrue(result.state.isOffBoard(friend))
            assertEquals(position(2, 2), result.state.square(attacker))
        }

        /**
         * Both dice come from the position, so the same move on two devices settles the
         * same way. Without this an online match would fork the moment a battle happened.
         */
        @Test
        @DisplayName("The same battle from the same position always goes the same way")
        fun battlesAreDeterministic() {
            val fruit = listOf(PieceKind.Strawberry, PieceKind.Banana)
            val f = fixture(mine = fruit, theirs = fruit)
            val move = f.state.move(f.attacker, to = position(2, 2))

            val first = RulesEngine.apply(move, f.state)
            val second = RulesEngine.apply(move, f.state)

            assertEquals(first.events, second.events)
            assertEquals(first.state, second.state)
        }

        /**
         * Evenly matched sides, so the dice decide. Sampled over many distinct positions to
         * check the rolls are actually varying rather than stuck.
         */
        @Test
        @DisplayName("The dice are dice: even sides do not always go the same way")
        fun evenBattlesAreDecidedByTheDice() {
            var attackerWins = 0
            val rolls = mutableSetOf<Int>()
            var sampled = 0

            for (row in 1 until Board.SIZE - 1) {
                for (column in 1 until Board.SIZE - 1) {
                    val builder = BoardBuilder()
                    val attacker = builder.blender(PlayerId.One, at = position(row, column), home = position(5, 1))
                    builder.blender(PlayerId.Two, at = position(row - 1, column - 1), home = position(0, 4))
                    // Level on standing fruit, so only the attacker's bonus and the dice differ.
                    builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(5, 5))
                    builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(0, 0))
                    val state = builder.state().updated(blenderBattles = true)

                    val result = RulesEngine.apply(state.move(attacker, to = position(row - 1, column - 1)), state)
                    val report = assertNotNull(result.events.battleReports.firstOrNull())
                    rolls += report.attackerRoll
                    rolls += report.defenderRoll
                    if (report.attackerWins) attackerWins += 1
                    sampled += 1
                }
            }

            assertTrue(rolls.all { it in 1..6 }, "a die shows one to six")
            assertTrue(rolls.size >= 5, "the rolls should vary across positions, saw ${rolls.sorted()}")
            assertTrue(attackerWins > 0, "the attacker should win some")
            assertTrue(attackerWins < sampled, "and lose some, even holding the bonus")
        }

        @Test
        @DisplayName("The CPU cannot see the dice before it decides")
        fun theSearchDoesNotPeekAtTheDice() {
            val f = fixture(mine = listOf(PieceKind.Strawberry), theirs = listOf(PieceKind.Strawberry))
            val move = f.state.move(f.attacker, to = position(2, 2))

            val searched = RulesEngine.apply(move, f.state, battles = BattleResolution.Expected)
            val searchReport = assertNotNull(searched.events.battleReports.firstOrNull())

            assertEquals(searchReport.defenderRoll, searchReport.attackerRoll, "no dice in the search")
            // Level fruit plus the attacker's bonus, so the search expects the attack to work.
            assertTrue(searchReport.attackerWins)
        }
    }
}
