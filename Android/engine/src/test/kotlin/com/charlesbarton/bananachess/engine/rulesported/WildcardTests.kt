package com.charlesbarton.bananachess.engine.rulesported

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.model.wildcardStanding
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.rules.TurnInProgress
import com.charlesbarton.bananachess.engine.rules.WildcardRules
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.engine.support.BoardBuilder
import com.charlesbarton.bananachess.engine.support.advanceTurns
import com.charlesbarton.bananachess.engine.support.assertRefusedWith
import com.charlesbarton.bananachess.engine.support.bananaReturn
import com.charlesbarton.bananachess.engine.support.blends
import com.charlesbarton.bananachess.engine.support.idleTransition
import com.charlesbarton.bananachess.engine.support.isOffBoard
import com.charlesbarton.bananachess.engine.support.move
import com.charlesbarton.bananachess.engine.support.position
import com.charlesbarton.bananachess.engine.support.square
import com.charlesbarton.bananachess.engine.support.withBananasDelivered
import com.charlesbarton.bananachess.engine.support.withBlenderamaCountered
import com.charlesbarton.bananachess.engine.support.withOwnFruitBlended
import com.charlesbarton.bananachess.engine.support.withSmoothieCollected
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class GoldenFixture(
    val state: GameState,
    val blender: PieceId,
    val doomed: PieceId,
    val survivor: PieceId,
)

private data class BananaFixture(
    val state: GameState,
    val banana: PieceId,
)

private data class SmoothieFixture(
    val state: GameState,
    val blender: PieceId,
    val theirBlenders: List<PieceId>,
)

private data class ShutoutFixture(
    val state: GameState,
    val fruit: PieceId,
)

/**
 * Ported from `Engine/Tests/BananaChessEngineTests/WildcardTests.swift`. Each nested
 * class is one Swift suite and each test keeps its Swift function name.
 *
 * Where the Swift assigns `state.activePlayer = .one` to give one player several moves in
 * a row, this replaces the state with `updated(activePlayer = ...)`: the same edit, and
 * like the Swift one it leaves the board history alone.
 */
class WildcardTests {
    @Nested
    @DisplayName("Wildcards are off unless asked for")
    inner class WildcardOptInTests {
        @Test
        @DisplayName("A standard match ignores every wildcard rule")
        fun standardMatchIsUnchanged() {
            // A position that would trigger three wildcards at once under the other rules.
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val pineapple = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state()

            assertEquals(RuleSet.Standard, state.ruleSet)
            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(3, result.state.scores.one, "plain base value, no golden anything")
            assertEquals(WildcardProgress(), result.state.wildcards, "nothing is tracked at all")
            assertNull(result.state.piece(pineapple))
            assertFalse(result.events.any { it is GameEvent.PineappleTurnedGolden })
        }

        @Test
        @DisplayName("A new game carries the rule set it was created with")
        fun ruleSetIsCarried() {
            val wild = GameSetup.newGame(ruleSet = RuleSet.Wildcard)
            assertEquals(RuleSet.Wildcard, wild.ruleSet)

            val move = RulesEngine.legalMoves(wild)[0]
            assertEquals(RuleSet.Wildcard, RulesEngine.apply(move, wild).state.ruleSet)
        }
    }

    @Nested
    @DisplayName("Golden Pineapple")
    inner class GoldenPineappleTests {
        /**
         * Player two owns both pineapples in this fixture; removing one makes the other
         * golden, whichever way it went.
         */
        private fun fixture(): GoldenFixture {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val doomed = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            val survivor = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(1, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            return GoldenFixture(builder.state(ruleSet = RuleSet.Wildcard), blender, doomed, survivor)
        }

        @Test
        @DisplayName("Two pineapples means neither is golden")
        fun twoPineapplesMeansNoGold() {
            val f = fixture()
            assertFalse(f.state.isGolden(f.doomed))
            assertFalse(f.state.isGolden(f.survivor))
            assertNull(f.state.goldenPineapple(PlayerId.Two))
        }

        @Test
        @DisplayName("Losing one to a blender makes the other golden")
        fun blendingOnePromotesTheOther() {
            val f = fixture()
            val result = RulesEngine.apply(f.state.move(f.blender, to = position(2, 4)), f.state)

            assertTrue(result.state.isGolden(f.survivor))
            assertEquals(f.survivor, result.state.goldenPineapple(PlayerId.Two)?.id)
            assertNull(result.state.goldenPineapple(PlayerId.One), "only the owner's own")
            assertTrue(GameEvent.PineappleTurnedGolden(f.survivor, owner = PlayerId.Two) in result.events)
            assertEquals(3, result.state.scores.one, "the one that was blended scored normally")
        }

        /**
         * The rule is about how many are left, not about how the other one went.
         * Delivering one promotes the survivor exactly as blending it does.
         */
        @Test
        @DisplayName("Delivering one makes the other golden too")
        fun deliveringOnePromotesTheOther() {
            val builder = BoardBuilder()
            val delivered = builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(1, 2))
            val survivor = builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(4, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(3, 0))
            val state = builder.state(ruleSet = RuleSet.Wildcard)
            assertFalse(state.isGolden(survivor), "two of them, so neither yet")

            val result = RulesEngine.apply(state.move(delivered, to = position(0, 2)), state)

            assertNull(result.state.piece(delivered))
            assertTrue(result.state.isGolden(survivor), "one left, so it is golden")
            assertTrue(GameEvent.PineappleTurnedGolden(survivor, owner = PlayerId.One) in result.events)
            assertEquals(6, result.state.scores.one, "the delivery itself was an ordinary one")
        }

        @Test
        @DisplayName("A pineapple the opponent delivers... is not a thing, but their own blend still counts")
        fun eitherSideCanCauseThePromotion() {
            // Player two blends one of their own pineapples: still one left, still golden.
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 4))
            val doomed = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            val survivor = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(1, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            val state = builder.state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertNull(result.state.piece(doomed))
            assertTrue(result.state.isGolden(survivor))
            assertEquals(-3, result.state.scores.two, "and it cost them, as a self-blend does")
        }

        @Test
        @DisplayName("Each player's pineapples are counted independently")
        fun bothPlayersCanHoldOne() {
            val builder = BoardBuilder()
            val mine = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val theirs = builder.blender(PlayerId.Two, at = position(2, 1), home = position(0, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            val theirSurvivor = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(3, 0))
            val mySurvivor = builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(5, 5))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            var current = RulesEngine.apply(state.move(mine, to = position(2, 4)), state).state
            current = RulesEngine.apply(current.move(theirs, to = position(3, 0)), current).state

            assertEquals(theirSurvivor, current.goldenPineapple(PlayerId.Two)?.id)
            assertEquals(mySurvivor, current.goldenPineapple(PlayerId.One)?.id)
        }

        @Test
        @DisplayName("Blending the last one leaves nothing golden")
        fun theLastPineappleLeavesNothing() {
            val f = fixture()
            var state = RulesEngine.apply(f.state.move(f.blender, to = position(2, 4)), f.state).state
            assertTrue(state.isGolden(f.survivor))

            // Walk round to the golden one and take that too.
            for (step in listOf(position(1, 3), position(2, 2))) {
                state = state.updated(activePlayer = PlayerId.One)
                state = RulesEngine.apply(state.move(f.blender, to = step), state).state
            }
            state = state.updated(activePlayer = PlayerId.One)
            val onGolden = RulesEngine.apply(state.move(f.blender, to = position(1, 1)), state)

            assertNull(onGolden.state.goldenPineapple(PlayerId.Two), "none left to be golden")
            assertEquals(3 + 6, onGolden.state.scores.one, "and it paid double on the way out")
            assertFalse(
                onGolden.events.any { it is GameEvent.PineappleTurnedGolden },
                "nothing was promoted by taking the last one",
            )
        }

        @Test
        @DisplayName("A Golden Pineapple delivers for three times base, not two")
        fun goldenDeliversForTriple() {
            val builder = BoardBuilder()
            val golden = builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(1, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(4, 4))
            val state = builder.state(ruleSet = RuleSet.Wildcard)
            assertTrue(state.isGolden(golden), "their only pineapple")

            val result = RulesEngine.apply(state.move(golden, to = position(0, 2)), state)

            assertEquals(9, result.state.scores.one, "3 × base 3, where a plain pineapple would score 6")
        }

        @Test
        @DisplayName("The opponent blending a Golden Pineapple takes double")
        fun opponentBlendsGoldenForDouble() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(6, result.state.scores.one, "2 × base 3, where a plain pineapple would pay 3")
        }

        @Test
        @DisplayName("Blending your own Golden Pineapple costs double too")
        fun selfBlendingGoldenCostsDouble() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(2, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(-6, result.state.scores.one)
        }

        @Test
        @DisplayName("A standard match has no Golden Pineapple at all")
        fun standardRulesHaveNoGold() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val lone = builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(2, 4))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 1))
            val state = builder.state()
            assertFalse(state.isGolden(lone), "a lone pineapple is just a pineapple here")

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)
            assertEquals(3, result.state.scores.one, "plain base value")
        }

        /**
         * The opening deals two each, so nothing starts golden. Worth pinning, since the
         * rule is now a count and a setup change could hand someone gold on turn one.
         */
        @ParameterizedTest
        @EnumSource(BoardSetup::class)
        @DisplayName("Nothing is golden at the opening")
        fun theOpeningIsNeverGolden(setup: BoardSetup) {
            val state = GameSetup.newGame(ruleSet = RuleSet.Wildcard, boardSetup = setup, seed = 42uL)
            for (player in PlayerId.entries) {
                assertEquals(2, state.pineapples(player).size)
                assertNull(state.goldenPineapple(player))
            }
        }
    }

    @Nested
    @DisplayName("Banana delivery")
    inner class BananaDeliveryTests {
        /** One banana one step from the far edge, with its spawn free behind it. */
        private fun fixture(
            delivered: Int = 0,
            ruleSet: RuleSet = RuleSet.Wildcard,
        ): BananaFixture {
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2), home = position(4, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 4))
            return BananaFixture(builder.state(ruleSet = ruleSet).withBananasDelivered(PlayerId.One, delivered), banana)
        }

        @Test
        @DisplayName("A delivered banana scores and goes back to its own spawn")
        fun bananaRecycles() {
            val f = fixture()
            val result = RulesEngine.apply(f.state.move(f.banana, to = position(0, 2)), f.state)

            assertEquals(2, result.state.scores.one, "an ordinary banana delivery")
            assertEquals(position(4, 2), result.state.square(f.banana), "and it is back where it started")
            assertEquals(position(4, 2), result.events.bananaReturn(of = f.banana))
            assertEquals(1, result.state.wildcards.bananasDelivered[PlayerId.One])
        }

        @Test
        @DisplayName("Deliveries count cumulatively, and the third one wins")
        fun thirdDeliveryWins() {
            val f = fixture(delivered = GameSetup.BANANA_DELIVERIES_TO_WIN - 1)
            val result = RulesEngine.apply(f.state.move(f.banana, to = position(0, 2)), f.state)

            assertEquals(GameSetup.BANANA_DELIVERIES_TO_WIN, result.state.wildcards.bananasDelivered[PlayerId.One])
            assertEquals(
                GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.BananaSplit)),
                result.state.status,
            )
        }

        @Test
        @DisplayName("Two deliveries is not yet three")
        fun twoIsNotEnough() {
            val f = fixture(delivered = GameSetup.BANANA_DELIVERIES_TO_WIN - 2)
            val result = RulesEngine.apply(f.state.move(f.banana, to = position(0, 2)), f.state)

            assertEquals(GameStatus.InProgress, result.state.status)
            assertEquals(GameSetup.BANANA_DELIVERIES_TO_WIN - 1, result.state.wildcards.bananasDelivered[PlayerId.One])
        }

        /**
         * The detail flagged in the handoff: a banana must not be lost because its spawn
         * happened to be busy, or the three-banana win could become unreachable.
         */
        @Test
        @DisplayName("A banana whose spawn is busy waits, then returns when it clears")
        fun bananaWaitsForItsSpawn() {
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2), home = position(4, 2))
            val squatter = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(4, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 5))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            var current = RulesEngine.apply(state.move(banana, to = position(0, 2)), state).state
            assertTrue(current.isOffBoard(banana), "it is in transit, not on the board")
            assertEquals(listOf(banana), current.waitingBananas(PlayerId.One).map { it.id })
            assertEquals(2, current.scores.one, "it still scored")

            // Player two moves, then player one steps the squatter aside.
            current = idleTransition(current, protecting = setOf(squatter)).state
            current = current.updated(activePlayer = PlayerId.One)
            val cleared = RulesEngine.apply(current.move(squatter, to = position(3, 2)), current)

            assertEquals(position(4, 2), cleared.state.square(banana), "and comes straight back")
            assertEquals(position(4, 2), cleared.events.bananaReturn(of = banana))
        }

        @Test
        @DisplayName("A banana in transit still counts as fruit in play")
        fun transitDoesNotEndTheMatch() {
            // Player one's only fruit is the banana being delivered.
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2), home = position(4, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(4, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 5))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(banana, to = position(0, 2)), state)

            assertTrue(result.state.onBoardFruit(PlayerId.One).isEmpty(), "nothing of theirs is on the board")
            assertEquals(1, result.state.waitingBananas(PlayerId.One).size)
            assertEquals(
                GameStatus.InProgress,
                result.state.status,
                "but the banana is coming back, so play continues",
            )
        }

        @Test
        @DisplayName("Bananas do not recycle in a standard match")
        fun standardBananasAreConsumed() {
            val f = fixture(ruleSet = RuleSet.Standard)
            val result = RulesEngine.apply(f.state.move(f.banana, to = position(0, 2)), f.state)

            assertNull(result.state.piece(f.banana), "delivered and gone, as before")
            assertEquals(2, result.state.scores.one)
            assertEquals(0, result.state.wildcards.bananasDelivered[PlayerId.One], "and nothing is counted")
        }

        @Test
        @DisplayName("A blended banana is gone for good and cannot be delivered again")
        fun blendedBananasDoNotRecycle() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 1))
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 4), home = position(4, 4))
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state = builder.state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertNull(result.state.piece(banana))
            assertTrue(result.state.waitingBananas(PlayerId.One).isEmpty())
        }
    }

    @Nested
    @DisplayName("Tropical Smoothie")
    inner class TropicalSmoothieTests {
        /** One of each fruit type, all reachable by a single blender in turn. */
        private fun fixture(): SmoothieFixture {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(2, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 4))
            val first = builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            val second = builder.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            // A spare fruit each, so the filler turns always have something inert to move.
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            return SmoothieFixture(builder.state(ruleSet = RuleSet.Wildcard), blender, listOf(first, second).sorted())
        }

        /** The walk that collects banana, strawberry and pineapple in that order. */
        private val recipeWalk =
            listOf(position(2, 2), position(1, 3), position(2, 4), position(3, 3), position(4, 4))

        /** Drives the retained blowout directly, as the Swift tests do: it is no longer reachable through a move. */
        private fun pourSmoothie(state: GameState): TurnInProgress =
            TurnInProgress(state).also { WildcardRules.pourSmoothie(PlayerId.One, it) }

        @Test
        @DisplayName("One of each fruit type pays three points")
        fun completingASmoothiePaysThePoints() {
            val f = fixture()
            var state = f.state

            for (destination in recipeWalk.dropLast(1)) {
                state = state.updated(activePlayer = PlayerId.One)
                state = RulesEngine.apply(state.move(f.blender, to = destination), state).state
            }
            assertEquals(2, state.wildcards.smoothie[PlayerId.One].collectedCount, "still short a pineapple")
            assertFalse(state.wildcards.smoothieFired[PlayerId.One])

            val before = state.scores.one
            state = state.updated(activePlayer = PlayerId.One)
            val result = RulesEngine.apply(state.move(f.blender, to = recipeWalk.last()), state)

            assertTrue(
                result.state.wildcards.smoothie[PlayerId.One]
                    .isComplete,
            )
            assertTrue(result.state.wildcards.smoothieFired[PlayerId.One])
            assertTrue(GameEvent.SmoothiePoured(PlayerId.One, points = RulesEngine.SMOOTHIE_AWARD) in result.events)

            // The pineapple it landed on is worth its own points; the smoothie adds three
            // more on top.
            val blended = result.events.blends.sumOf { it.points }
            assertEquals(before + blended + RulesEngine.SMOOTHIE_AWARD, result.state.scores.one)
        }

        @Test
        @DisplayName("Both of the opponent's blenders are left alone — a smoothie pays, it does not blow up")
        fun aSmoothieNoLongerTouchesBlenders() {
            val f = fixture()
            var state = f.state
            for (destination in recipeWalk) {
                state = state.updated(activePlayer = PlayerId.One)
                state = RulesEngine.apply(state.move(f.blender, to = destination), state).state
            }

            assertTrue(state.wildcards.smoothieFired[PlayerId.One])
            assertEquals(2, state.activeBlenders(PlayerId.Two).size, "both still standing")
            assertTrue(state.destroyedBlenders(PlayerId.Two).isEmpty())
            assertEquals(0, state.scores.two, "and it cost them nothing at all")
        }

        @Test
        @DisplayName("Whose fruit went in does not matter — your own make a smoothie too")
        fun yourOwnFruitCountsJustTheSame() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            // Every type, and every one of them yours.
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(2, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(4, 4))
            // A second pineapple, out of the blender's way, so the one being blended is not
            // golden. This test is about the smoothie, not about the doubled price of gold.
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(0, 0))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            var state = builder.state(ruleSet = RuleSet.Wildcard)

            for (destination in recipeWalk.dropLast(1)) {
                state = state.updated(activePlayer = PlayerId.One)
                state = RulesEngine.apply(state.move(blender, to = destination), state).state
            }
            state = state.updated(activePlayer = PlayerId.One)
            val result = RulesEngine.apply(state.move(blender, to = recipeWalk.last()), state)

            assertTrue(result.state.wildcards.smoothieFired[PlayerId.One], "no stolen ingredient needed")
            assertTrue(GameEvent.SmoothiePoured(PlayerId.One, points = RulesEngine.SMOOTHIE_AWARD) in result.events)
            // Six points of self-harm on the way, three back at the end.
            assertEquals(-6 + RulesEngine.SMOOTHIE_AWARD, result.state.scores.one)
        }

        @Test
        @DisplayName("A smoothie pays once and only once")
        fun aSmoothiePaysOnce() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(4, 4))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            // Already collected strawberry and pineapple.
            var state =
                builder
                    .state(ruleSet = RuleSet.Wildcard)
                    .withSmoothieCollected(PlayerId.One, PieceKind.Strawberry, PieceKind.Pineapple)

            state = RulesEngine.apply(state.move(blender, to = position(2, 2)), state).state
            assertTrue(state.wildcards.smoothieFired[PlayerId.One], "the banana completed it")
            val afterFirst = state.scores.one

            // Blend another banana: points for the fruit, but no second bonus.
            state = state.updated(activePlayer = PlayerId.One)
            state = RulesEngine.apply(state.move(blender, to = position(3, 3)), state).state
            state = state.updated(activePlayer = PlayerId.One)
            val again = RulesEngine.apply(state.move(blender, to = position(4, 4)), state)

            assertFalse(again.events.any { it is GameEvent.SmoothiePoured })
            assertEquals(afterFirst + PieceKind.Banana.baseValue, again.state.scores.one)
        }

        @Test
        @DisplayName("A standard match pays no smoothie bonus")
        fun standardRulesPayNothing() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 2))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            val state =
                builder.state().withSmoothieCollected(PlayerId.One, PieceKind.Strawberry, PieceKind.Pineapple)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertFalse(result.state.wildcards.smoothieFired[PlayerId.One])
            assertEquals(PieceKind.Banana.baseValue, result.state.scores.one, "the fruit, and nothing else")
        }

        // The retained blowout.
        //
        // `WildcardRules.pourSmoothie` is no longer called by the rules (a smoothie pays
        // points instead) but it is kept intact so the effect can be switched back on.
        // These drive it directly so it cannot quietly rot in the meantime.

        @Test
        @DisplayName("Retained: the blowout still takes both blenders off the board")
        fun theRetainedBlowoutStillWorks() {
            val f = fixture()
            val poured = pourSmoothie(f.state)
            val state = poured.state

            assertTrue(state.activeBlenders(PlayerId.Two).isEmpty())
            assertEquals(1, state.activeBlenders(PlayerId.One).size, "your own are untouched")
            assertTrue(poured.events.any { it is GameEvent.Smoothie })

            val victim = state.piece(f.theirBlenders[0])
            assertEquals(state.turnNumber + RulesEngine.SMOOTHIE_BLACKOUT_TURNS, victim?.respawnTurn)
            assertEquals(false, victim?.needsBuyingBack, "no need to pay for it")
        }

        @Test
        @DisplayName("Retained: standing on a spawn is no defence against the blowout")
        fun theRetainedBlowoutIgnoresSpawnProtection() {
            // Both of player two's blenders are sitting on their own spawns, which makes
            // them safe from capture. The blowout is not a capture.
            val f = fixture()
            assertTrue(
                f.theirBlenders.all { id ->
                    f.state.piece(id)?.let { RulesEngine.isOnASpawn(it, f.state) } ?: false
                },
            )

            val state = pourSmoothie(f.state).state

            assertTrue(state.activeBlenders(PlayerId.Two).isEmpty())
        }

        @Test
        @DisplayName("Retained: victims come back by themselves, for nothing")
        fun theRetainedVictimsReturnOnTheirOwn() {
            val f = fixture()
            var state = pourSmoothie(f.state).state.updated(activePlayer = PlayerId.Two)

            // Player two plays their one blenderless turn, and they are back after it.
            assertTrue(state.activeBlenders(PlayerId.Two).isEmpty())
            state = advanceTurns(2, from = state)

            assertEquals(position(0, 1), state.square(f.theirBlenders[0]))
            assertEquals(position(0, 4), state.square(f.theirBlenders[1]))
            assertEquals(0, state.scores.two, "and it cost them nothing")
        }

        @Test
        @DisplayName("Retained: a victim on its way back cannot be bought back")
        fun theRetainedVictimsAreNotForSale() {
            val f = fixture()
            val state = pourSmoothie(f.state).state.updated(activePlayer = PlayerId.Two)

            val victim = f.theirBlenders[0]
            val restore = RestoreBlender(victim, at = position(0, 1))
            assertRefusedWith(RulesError.BlenderReturningOnItsOwn(victim)) { RulesEngine.apply(restore, state) }
            assertFalse(restore in RulesEngine.legalActions(state))
        }
    }

    @Nested
    @DisplayName("Fruit depletion ends the match")
    inner class FruitDepletionTests {
        @Test
        @DisplayName("Running one player out of fruit ends it, even with fruit still on the board")
        fun oneEmptySideEndsIt() {
            val builder = BoardBuilder()
            val last = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2))
            // Player two still has plenty standing; none of it counts.
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 5))
            builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(3, 0))
            val state = builder.state(scores = Scores(one = 0, two = 1))

            val result = RulesEngine.apply(state.move(last, to = position(0, 2)), state)

            assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.Score)), result.state.status)
            assertEquals(2, result.state.scores.one, "the delivery that emptied their side still scored")
            assertEquals(1, result.state.scores.two, "eight points of standing fruit counted for nothing")
            assertEquals(3, result.state.fruit(PlayerId.Two).size)
        }

        @Test
        @DisplayName("Being blended out also ends it")
        fun blendingOutTheLastFruitEndsIt() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(2, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(5, 5))
            val state = builder.state(scores = Scores(one = 0, two = 6))

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.Two, by = WinReason.Score)), result.state.status)
            assertEquals(1, result.state.scores.one, "taking their last fruit paid its base value")
        }

        @Test
        @DisplayName("Blending your own last fruit ends the match too, at a price")
        fun endingItOnYourOwnTerms() {
            // Player one leads by enough that paying a point to stop the clock is worth it.
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 4))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state = builder.state(scores = Scores(one = 9, two = 4))

            val result = RulesEngine.apply(state.move(blender, to = position(2, 4)), state)

            assertEquals(8, result.state.scores.one, "it cost a point")
            assertEquals(
                GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.Score)),
                result.state.status,
                "and won the match",
            )
        }

        @Test
        @DisplayName("A match with fruit on both sides carries on")
        fun bothSidesStockedKeepsPlaying() {
            val builder = BoardBuilder()
            val banana = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(3, 3))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state = builder.state()

            val result = RulesEngine.apply(state.move(banana, to = position(2, 3)), state)

            assertEquals(GameStatus.InProgress, result.state.status)
        }

        @Test
        @DisplayName("Depletion applies under standard rules as well as wildcard")
        fun appliesToBothRuleSets() {
            for (ruleSet in RuleSet.entries) {
                val builder = BoardBuilder()
                val last = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(1, 2))
                builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 4))
                val state = builder.state(ruleSet = ruleSet)

                val result = RulesEngine.apply(state.move(last, to = position(0, 2)), state)

                assertTrue(result.state.status.isOver, "$ruleSet should end on depletion")
            }
        }

        /** The victory priority from §6: an alternate win is settled before the score is. */
        @Test
        @DisplayName("An alternate win beats the depletion winner when one move triggers both")
        fun alternateWinTakesPriority() {
            val builder = BoardBuilder()
            val last = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(4, 4))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard, scores = Scores(one = 0, two = 50))
                    .withBananasDelivered(PlayerId.One, GameSetup.BANANA_DELIVERIES_TO_WIN - 1)

            // This delivery both completes a Banana Split and empties player one's side.
            val result = RulesEngine.apply(state.move(last, to = position(0, 2)), state)

            assertEquals(
                GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.BananaSplit)),
                result.state.status,
                "the Split wins it, not player two's fifty points",
            )
        }
    }

    @Nested
    @DisplayName("Blenderama")
    inner class BlenderamaTests {
        @Test
        @DisplayName("Blending every one of your own fruit wins outright")
        fun blendingAllOwnFruitWins() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            val last = builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard, scores = Scores(one = -20, two = 30))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.Blenderama)), result.state.status)
            assertNull(result.state.piece(last))
            assertEquals(-21, result.state.scores.one, "twenty-one points down, and the winner")
        }

        /**
         * The exact shape of "I completed Blenderama and lost". Blending all six of your
         * own fruit costs you twelve points, so if the win does not fire you are deeply
         * negative, and the match ends the instant your last fruit leaves the board.
         */
        @Test
        @DisplayName("A countered Blenderama run ends in a loss, which is the whole risk of it")
        fun aCounteredRunLosesTheMatch() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            // Five of their own already blended, and the opponent took the sixth earlier,
            // so the count can never reach six and the run was dead before it finished.
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard, scores = Scores(one = -11, two = 0))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)
                    .withBlenderamaCountered(PlayerId.One)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(GameSetup.fruitCount, result.state.wildcards.ownFruitBlended[PlayerId.One])
            assertEquals(WinReason.Score, result.state.outcome?.reason, "no Blenderama: it was countered")
            assertEquals(
                Outcome.Win(PlayerId.Two, by = WinReason.Score),
                result.state.outcome,
                "and the run cost the match",
            )
            assertEquals(-12, result.state.scores.one)
        }

        @Test
        @DisplayName("An uncountered run of the same length wins outright")
        fun anUncounteredRunWins() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard, scores = Scores(one = -11, two = 0))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(Outcome.Win(PlayerId.One, by = WinReason.Blenderama), result.state.outcome)
            assertEquals(-12, result.state.scores.one, "twelve points down, and the winner")
        }

        /**
         * The shutout condition. Blending one of your fruit was never the only way to stop
         * a Blenderama: the opponent simply getting on the board at all does it.
         */
        private fun deliveryFixture(ruleSet: RuleSet = RuleSet.Wildcard): ShutoutFixture {
            val builder = BoardBuilder()
            // One step from player two's delivery row, which is player one's back row.
            val runner = builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(4, 2))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(1, 1))
            builder.blender(PlayerId.One, at = position(5, 1), home = position(5, 1))
            val state = builder.state(active = PlayerId.Two, ruleSet = ruleSet).withOwnFruitBlended(PlayerId.One, 3)
            return ShutoutFixture(state, runner)
        }

        @Test
        @DisplayName("A fruit across takes the opponent's Blenderama off — the shutout rule")
        fun aDeliveryNegatesTheOpponentsBlenderama() {
            val f = deliveryFixture()
            assertFalse(f.state.wildcards.blenderamaCountered[PlayerId.One])

            val result =
                RulesEngine.apply(f.state.move(f.fruit, to = position(PlayerId.Two.deliveryRow, 2)), f.state)

            assertTrue(result.state.scores.two > 0, "they got on the board")
            assertTrue(result.state.wildcards.blenderamaCountered[PlayerId.One], "so player one's run is off")
            assertFalse(result.state.wildcards.blenderamaCountered[PlayerId.Two], "and theirs is untouched")
        }

        @Test
        @DisplayName("A run already at five cannot be finished once they have scored")
        fun aScoringTurnKillsARunInProgress() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            val theirs = builder.fruit(PieceKind.Strawberry, PlayerId.Two, at = position(4, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            var state =
                builder
                    .state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard, scores = Scores(one = -11, two = 0))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)

            // They deliver. Nothing about player one's run has changed except that it is dead.
            state = RulesEngine.apply(state.move(theirs, to = position(PlayerId.Two.deliveryRow, 2)), state).state
            assertTrue(state.wildcards.blenderamaCountered[PlayerId.One])

            state = state.updated(activePlayer = PlayerId.One)
            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(GameSetup.fruitCount, result.state.wildcards.ownFruitBlended[PlayerId.One], "six of six")
            assertNotEquals(WinReason.Blenderama, result.state.outcome?.reason, "and it wins nothing")
        }

        @Test
        @DisplayName("Your own scoring never touches your own run, and losing points never counts")
        fun onlyTheOpponentsPointsNegate() {
            // Player one delivers: their own points cannot negate their own Blenderama.
            val builder = BoardBuilder()
            val mine = builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(1, 2))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(4, 4))
            builder.blender(PlayerId.Two, at = position(0, 1), home = position(0, 1))
            var state = builder.state(ruleSet = RuleSet.Wildcard).withOwnFruitBlended(PlayerId.One, 2)

            state = RulesEngine.apply(state.move(mine, to = position(PlayerId.One.deliveryRow, 2)), state).state
            assertTrue(state.scores.one > 0)
            assertFalse(
                state.wildcards.blenderamaCountered[PlayerId.One],
                "your own points are your own business",
            )
            assertTrue(state.wildcards.blenderamaCountered[PlayerId.Two], "but they take theirs off")

            // And a turn that costs points (buying a blender back) negates nothing.
            val second = BoardBuilder()
            val victim = second.destroyedBlender(PlayerId.Two, home = position(0, 1))
            second.blender(PlayerId.Two, at = position(0, 4), home = position(0, 4))
            second.fruit(PieceKind.Banana, PlayerId.One, at = position(5, 5))
            second.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 5))
            val paying = second.state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard)

            val after = RulesEngine.apply(RestoreBlender(victim, at = position(0, 1)), paying)
            assertEquals(-RulesEngine.BLENDER_RESTORE_COST, after.state.scores.two)
            assertFalse(after.state.wildcards.blenderamaCountered[PlayerId.One], "paying is not scoring")
        }

        @Test
        @DisplayName("A standard match ignores the shutout rule entirely")
        fun standardRulesIgnoreTheShutout() {
            val f = deliveryFixture(ruleSet = RuleSet.Standard)
            val result =
                RulesEngine.apply(f.state.move(f.fruit, to = position(PlayerId.Two.deliveryRow, 2)), f.state)

            assertTrue(result.state.scores.two > 0)
            assertFalse(result.state.wildcards.blenderamaCountered[PlayerId.One], "nothing to counter")
        }

        @Test
        @DisplayName("A standard match has no Blenderama at all, so the same run just loses")
        fun standardRulesHaveNoBlenderama() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state =
                builder
                    .state(scores = Scores(one = -11, two = 0))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(RuleSet.Standard, result.state.ruleSet)
            assertEquals(Outcome.Win(PlayerId.Two, by = WinReason.Score), result.state.outcome)
        }

        @Test
        @DisplayName("Fruit the opponent blends does not count toward your Blenderama")
        fun opponentBlendsDoNotCount() {
            val builder = BoardBuilder()
            val theirBlender = builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state =
                builder
                    .state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard)
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)

            val result = RulesEngine.apply(state.move(theirBlender, to = position(2, 2)), state)

            assertEquals(
                GameStatus.InProgress,
                result.state.status,
                "they took it, so it was never yours to blend",
            )
            assertEquals(GameSetup.fruitCount - 1, result.state.wildcards.ownFruitBlended[PlayerId.One])
            assertEquals(0, result.state.wildcards.ownFruitBlended[PlayerId.Two])
        }

        /** §4's hard counter, tracked explicitly rather than inferred from the count. */
        @Test
        @DisplayName("One stolen self-blend puts Blenderama permanently out of reach")
        fun opponentBlendCountersPermanently() {
            val builder = BoardBuilder()
            val theirBlender = builder.blender(PlayerId.Two, at = position(3, 3), home = position(0, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(5, 5))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state = builder.state(active = PlayerId.Two, ruleSet = RuleSet.Wildcard)

            val result = RulesEngine.apply(state.move(theirBlender, to = position(2, 2)), state)

            assertTrue(result.state.wildcards.blenderamaCountered[PlayerId.One], "they took one of mine")
            assertFalse(result.state.wildcards.blenderamaCountered[PlayerId.Two])
        }

        @Test
        @DisplayName("Even reaching the full count cannot win once countered")
        fun counteredBeatsTheCount() {
            val builder = BoardBuilder()
            val blender = builder.blender(PlayerId.One, at = position(3, 3), home = position(5, 1))
            builder.fruit(PieceKind.Banana, PlayerId.One, at = position(2, 2))
            builder.fruit(PieceKind.Pineapple, PlayerId.Two, at = position(0, 0))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard, scores = Scores(one = 20, two = 0))
                    .withOwnFruitBlended(PlayerId.One, GameSetup.fruitCount - 1)
                    .withBlenderamaCountered(PlayerId.One)

            val result = RulesEngine.apply(state.move(blender, to = position(2, 2)), state)

            assertEquals(GameSetup.fruitCount, result.state.wildcards.ownFruitBlended[PlayerId.One])
            assertEquals(
                WinReason.Score,
                result.state.outcome?.reason,
                "countered, so it ends on points instead",
            )
            assertEquals(GameStatus.Ended(Outcome.Win(PlayerId.One, by = WinReason.Score)), result.state.status)
        }
    }

    @Nested
    @DisplayName("Wildcard standing")
    inner class WildcardStandingTests {
        @Test
        @DisplayName("A Split is blocked only once the player has no banana left to run")
        fun splitBlockedWithoutBananas() {
            val builder = BoardBuilder()
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(4, 0))
            builder.fruit(PieceKind.Banana, PlayerId.Two, at = position(1, 0))
            val state = builder.state(ruleSet = RuleSet.Wildcard)

            assertTrue(state.wildcardStanding(PlayerId.One).isBananaSplitBlocked)
            assertFalse(state.wildcardStanding(PlayerId.Two).isBananaSplitBlocked)
        }

        @Test
        @DisplayName("A Split already delivered is complete, never blocked")
        fun completedSplitIsNotBlocked() {
            val builder = BoardBuilder()
            builder.fruit(PieceKind.Strawberry, PlayerId.One, at = position(4, 0))
            val state =
                builder
                    .state(ruleSet = RuleSet.Wildcard)
                    .withBananasDelivered(PlayerId.One, GameSetup.BANANA_DELIVERIES_TO_WIN)

            val standing = state.wildcardStanding(PlayerId.One)
            assertTrue(standing.isBananaSplitComplete)
            assertFalse(standing.isBananaSplitBlocked)
        }

        @Test
        @DisplayName("Blenderama dies when it is countered or when fruit leaves any other way")
        fun blenderamaBlocked() {
            val builder = BoardBuilder()
            for (column in 0 until GameSetup.fruitCount - 1) {
                builder.fruit(PieceKind.Banana, PlayerId.One, at = position(4, column % 6))
            }
            var state = builder.state(ruleSet = RuleSet.Wildcard)

            // One fruit short and none blended: something left by another route.
            assertTrue(state.wildcardStanding(PlayerId.One).isBlenderamaBlocked)

            state = state.withOwnFruitBlended(PlayerId.One, 1)
            assertFalse(state.wildcardStanding(PlayerId.One).isBlenderamaBlocked)

            state = state.withBlenderamaCountered(PlayerId.One)
            assertTrue(state.wildcardStanding(PlayerId.One).isBlenderamaBlocked)
        }
    }
}
