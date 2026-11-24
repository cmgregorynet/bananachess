package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.PlayerPair
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.applying
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchViewModelTest {
    // The standard opening, from player one's side: blenders on the back row, fruit in front.
    private val ownBanana = square(4, 0)
    private val ownStrawberry = square(4, 1)
    private val ownBlender = square(5, 1)
    private val theirBanana = square(1, 5)

    /** Player one's blender takes player one's banana: the shortest move with a blend in it. */
    private fun TestScope.aimSelfBlend(viewModel: MatchViewModel) {
        viewModel.tap(ownBlender)
        viewModel.tap(ownBanana)
    }

    private fun selfBlend(state: GameState): Move =
        Move(checkNotNull(state.pieceAt(ownBlender)).id, ownBlender, ownBanana)

    // Selection

    @Test
    fun `tapping your own piece selects it and shows where it can go`() =
        runTest {
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(haptics = haptics)

            viewModel.tap(ownBanana)

            assertEquals(
                viewModel.ui.displayState
                    .pieceAt(ownBanana)
                    ?.id,
                viewModel.ui.selectedPieceId,
            )
            assertEquals(setOf(square(3, 0), square(5, 0)), viewModel.ui.legalDestinations)
            assertEquals(ownBanana, viewModel.ui.selectedSquare)
            assertEquals(1, haptics.selections)
        }

    @Test
    fun `tapping the selected piece again puts it down`() =
        runTest {
            val viewModel = matchViewModel()

            viewModel.tap(ownBanana)
            viewModel.tap(ownBanana)

            assertNull(viewModel.ui.selectedPieceId)
            assertEquals(emptySet(), viewModel.ui.legalDestinations)
        }

    @Test
    fun `tapping another of your pieces moves the selection to it`() =
        runTest {
            val viewModel = matchViewModel()

            viewModel.tap(ownBanana)
            viewModel.tap(ownStrawberry)

            assertEquals(ownStrawberry, viewModel.ui.selectedSquare)
            assertEquals(setOf(square(3, 1)), viewModel.ui.legalDestinations)
        }

    @Test
    fun `the other side's pieces and empty squares cannot be picked up`() =
        runTest {
            val viewModel = matchViewModel()
            val before = viewModel.ui

            viewModel.tap(theirBanana)
            viewModel.tap(square(3, 3))

            assertEquals(before, viewModel.ui)
            assertFalse(viewModel.ui.isSelectable(checkNotNull(before.displayState.pieceAt(theirBanana))))
            assertTrue(viewModel.ui.isSelectable(checkNotNull(before.displayState.pieceAt(ownBanana))))
        }

    // Moves

    @Test
    fun `tapping a legal destination moves the piece and hands the turn over`() =
        runTest {
            val viewModel = matchViewModel()
            val banana = viewModel.ui.displayState.pieceAt(ownBanana)

            viewModel.tap(ownBanana)
            viewModel.tap(square(3, 0))

            assertNull(viewModel.ui.selectedPieceId)
            assertEquals(emptySet(), viewModel.ui.legalDestinations)
            assertTrue(viewModel.ui.isResolving)
            assertEquals(PlayerId.Two, viewModel.ui.authoritativeState.activePlayer)

            settle()

            assertFalse(viewModel.ui.isResolving)
            assertEquals(
                banana?.id,
                viewModel.ui.displayState
                    .pieceAt(square(3, 0))
                    ?.id,
            )
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
            assertEquals("Player Two to move", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `a second tap on the destination does not move twice`() =
        runTest {
            val viewModel = matchViewModel()
            viewModel.tap(ownBanana)

            viewModel.tap(square(3, 0))
            viewModel.tap(square(3, 0))
            settle()

            assertEquals(1, viewModel.ui.authoritativeState.history.size)
            assertNull(viewModel.ui.errorMessage)
        }

    @Test
    fun `a legal destination wins over picking up the piece standing on it`() =
        runTest {
            val viewModel = matchViewModel()

            aimSelfBlend(viewModel)

            assertEquals(ownBlender, viewModel.ui.selectedSquare)
            assertEquals(selfBlend(viewModel.ui.displayState), viewModel.ui.pendingSelfBlend?.move)
        }

    // Self-blend

    @Test
    fun `blending your own fruit asks first and changes nothing until answered`() =
        runTest {
            val viewModel = matchViewModel()
            val start = viewModel.ui.authoritativeState

            aimSelfBlend(viewModel)
            settle()

            val pending = assertNotNull(viewModel.ui.pendingSelfBlend)
            assertEquals("Blend your own banana?", pending.prompt)
            assertEquals("It leaves the board and costs you 1 point.", pending.detail)
            assertEquals(start, viewModel.ui.authoritativeState)
            assertFalse(viewModel.ui.isResolving)
        }

    @Test
    fun `declining a self-blend leaves the board and the selection as they were`() =
        runTest {
            val viewModel = matchViewModel()
            val start = viewModel.ui.authoritativeState
            aimSelfBlend(viewModel)

            viewModel.cancelSelfBlend()
            settle()

            assertNull(viewModel.ui.pendingSelfBlend)
            assertEquals(start, viewModel.ui.authoritativeState)
            assertEquals(ownBlender, viewModel.ui.selectedSquare)
        }

    @Test
    fun `a confirmed self-blend costs the fruit's value`() =
        runTest {
            val viewModel = matchViewModel()
            aimSelfBlend(viewModel)

            viewModel.confirmSelfBlend()
            viewModel.confirmSelfBlend()
            settle()

            assertNull(viewModel.ui.pendingSelfBlend)
            assertNull(viewModel.ui.selectedPieceId)
            assertEquals(-1, viewModel.ui.displayState.scores[PlayerId.One])
            assertEquals(-1, viewModel.ui.scoreboard(PlayerId.One).score)
            assertEquals(
                5,
                viewModel.ui.displayState
                    .fruit(PlayerId.One)
                    .size,
            )
            assertEquals(
                PieceKind.Blender,
                viewModel.ui.displayState
                    .pieceAt(ownBanana)
                    ?.kind,
            )
            assertEquals(1, viewModel.ui.authoritativeState.history.size)
        }

    @Test
    fun `the board takes no other move while a self-blend is waiting for its answer`() =
        runTest {
            val viewModel = matchViewModel()
            aimSelfBlend(viewModel)
            val asked = viewModel.ui

            viewModel.tap(square(4, 2))
            viewModel.tap(ownStrawberry)

            assertEquals(asked, viewModel.ui)
        }

    @Test
    fun `a pricier fruit is priced in the plural`() {
        val move = Move(blender(PlayerId.One, 0, ownBlender).id, ownBlender, ownBanana)

        val pending = PendingSelfBlend(move, PieceKind.Pineapple, BlenderamaStanding.NotPlaying)

        assertEquals("Blend your own pineapple?", pending.prompt)
        assertEquals("It leaves the board and costs you 3 points.", pending.detail)
    }

    @Test
    fun `with wildcards on the prompt says how far along Blenderama this is`() =
        runTest {
            val viewModel = matchViewModel(GameSetup.newGame(ruleSet = RuleSet.Wildcard))

            aimSelfBlend(viewModel)

            assertEquals(BlenderamaStanding.Counts(soFar = 0, target = 6), viewModel.ui.pendingSelfBlend?.blenderama)
            assertEquals(
                "It leaves the board and costs you 1 point. Blenderama: 1 of 6 once this is done.",
                viewModel.ui.pendingSelfBlend?.detail,
            )
        }

    @Test
    fun `the last self-blend of a Blenderama says it wins the match`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        listOf(
                            blender(PlayerId.One, 0, ownBlender),
                            fruit(PlayerId.One, PieceKind.Banana, row = 4, column = 0),
                            fruit(PlayerId.Two, PieceKind.Banana, row = 1, column = 5),
                        ),
                    ruleSet = RuleSet.Wildcard,
                    wildcards = WildcardProgress(ownFruitBlended = PlayerPair(one = 5, two = 0)),
                )
            val viewModel = matchViewModel(state)

            aimSelfBlend(viewModel)
            assertEquals(
                "It leaves the board and costs you 1 point. It is also your last one — this wins the match by Blenderama.",
                viewModel.ui.pendingSelfBlend?.detail,
            )

            viewModel.confirmSelfBlend()
            settle()
            assertEquals("Player One wins by Blenderama.", viewModel.ui.resultText)
            assertEquals("Every one of their own fruit, through their own blenders.", viewModel.ui.resultDetail)
            assertEquals("Match over", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `a self-blend with Blenderama already off says it is pure loss`() =
        runTest {
            val state =
                GameSetup
                    .newGame(ruleSet = RuleSet.Wildcard)
                    .updated(wildcards = WildcardProgress(blenderamaCountered = PlayerPair(one = true, two = false)))
            val viewModel = matchViewModel(state)

            aimSelfBlend(viewModel)

            assertEquals(
                "It leaves the board and costs you 1 point." +
                    " Blenderama is already off for this match, so this is points down and nothing else.",
                viewModel.ui.pendingSelfBlend?.detail,
            )
        }

    // Buy-back

    private val boardWithLostBlender =
        GameState(
            pieces =
                spareFruit +
                    listOf(
                        blender(PlayerId.One, 0, at = null),
                        blender(PlayerId.One, 1, at = square(5, 4)),
                        blender(PlayerId.Two, 0, at = square(0, 1)),
                    ),
        )

    @Test
    fun `tapping an empty spawn buys a destroyed blender back for three points`() =
        runTest {
            val flashes = mutableListOf<String?>()
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(boardWithLostBlender, haptics = haptics)
            haptics.onPlay = { flashes += viewModel.ui.flash?.text }
            val spawn = square(5, 1)
            assertEquals(listOf(spawn), viewModel.ui.purchasableSpawns)
            assertEquals(blender(PlayerId.One, 0, at = null).id, viewModel.ui.restorableBlender(spawn))

            viewModel.tap(square(4, 5))
            viewModel.tap(spawn)
            settle()

            assertNull(viewModel.ui.selectedPieceId)
            assertEquals(
                PieceKind.Blender,
                viewModel.ui.displayState
                    .pieceAt(spawn)
                    ?.kind,
            )
            assertEquals(-3, viewModel.ui.displayState.scores[PlayerId.One])
            assertEquals(PlayerId.Two, viewModel.ui.displayState.activePlayer)
            assertEquals("Blender back · −3", flashes.first())
            assertEquals(emptyList(), viewModel.ui.purchasableSpawns)
        }

    @Test
    fun `an occupied spawn offers no buy-back`() =
        runTest {
            val viewModel = matchViewModel(boardWithLostBlender)

            assertNull(viewModel.ui.restorableBlender(square(5, 4)))
            assertNull(viewModel.ui.restorableBlender(square(3, 3)))
        }

    // Input lock

    @Test
    fun `the board ignores taps while a move is still playing out`() =
        runTest {
            val viewModel = matchViewModel()
            viewModel.tap(ownBanana)
            viewModel.tap(square(3, 0))
            advance(100)
            assertTrue(viewModel.ui.isInputLocked)
            val midMove = viewModel.ui

            viewModel.tap(theirBanana)
            viewModel.tap(ownStrawberry)

            assertEquals(midMove, viewModel.ui)
            settle()
            assertFalse(viewModel.ui.isInputLocked)
            assertEquals(1, viewModel.ui.authoritativeState.history.size)
        }

    @Test
    fun `a side this device does not control cannot be moved`() =
        runTest {
            val mode = MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.Two)
            val viewModel = matchViewModel(mode = mode)
            val before = viewModel.ui

            viewModel.tap(ownBanana)
            viewModel.tap(theirBanana)

            assertTrue(viewModel.ui.isInputLocked)
            assertEquals(before, viewModel.ui)
            assertEquals("Easy CPU to move", viewModel.ui.turnIndicatorText)
        }

    @Test
    fun `nothing can be picked up once the match is over`() =
        runTest {
            val over = GameSetup.newGame().updated(status = GameStatus.Ended(Outcome.Draw))
            val viewModel = matchViewModel(over)

            viewModel.tap(ownBanana)

            assertTrue(viewModel.ui.isInputLocked)
            assertNull(viewModel.ui.selectedPieceId)
            assertEquals("Match over", viewModel.ui.turnIndicatorText)
            assertEquals("A draw — nothing left to blend.", viewModel.ui.resultText)
            assertNull(viewModel.ui.resultDetail)
        }

    // Timeline

    @Test
    fun `a move's events reach the board in order, each at its own beat`() =
        runTest {
            val viewModel = matchViewModel()
            val start = viewModel.ui.displayState
            val events = RulesEngine.apply(selfBlend(start), start).events
            assertIs<GameEvent.Moved>(events[0])
            assertIs<GameEvent.Blended>(events[1])
            assertIs<GameEvent.ScoreChanged>(events[2])
            assertIs<GameEvent.TurnChanged>(events[3])
            assertEquals(4, events.size)
            val afterMoved = start.applying(events[0])
            val afterBlended = afterMoved.applying(events[1])
            val afterScore = afterBlended.applying(events[2])
            val afterTurn = afterScore.applying(events[3])

            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(0)
            assertEquals(afterMoved, viewModel.ui.displayState)

            advance(279)
            assertEquals(afterMoved, viewModel.ui.displayState)
            advance(1)
            assertEquals(afterBlended, viewModel.ui.displayState)
            assertEquals(0, viewModel.ui.displayState.scores[PlayerId.One])

            advance(419)
            assertEquals(afterBlended, viewModel.ui.displayState)
            advance(1)
            assertEquals(afterScore, viewModel.ui.displayState)
            assertEquals(-1, viewModel.ui.displayState.scores[PlayerId.One])
            assertEquals(PlayerId.One, viewModel.ui.displayState.activePlayer)

            advance(119)
            assertEquals(afterScore, viewModel.ui.displayState)
            advance(1)
            assertEquals(afterTurn, viewModel.ui.displayState)

            advance(59)
            assertTrue(viewModel.ui.isResolving)
            advance(1)
            assertFalse(viewModel.ui.isResolving)
        }

    @Test
    fun `under reduced motion every event takes 90 ms`() =
        runTest {
            val viewModel = matchViewModel(reduceMotion = { true })
            val start = viewModel.ui.displayState
            val events = RulesEngine.apply(selfBlend(start), start).events

            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(0)

            var expected = start
            for (event in events) {
                expected = expected.applying(event)
                assertEquals(expected, viewModel.ui.displayState)
                assertTrue(viewModel.ui.isResolving)
                advance(89)
                assertEquals(expected, viewModel.ui.displayState)
                assertTrue(viewModel.ui.isResolving)
                advance(1)
            }
            assertFalse(viewModel.ui.isResolving)
        }

    @Test
    fun `when the timeline ends the board shows the engine's state and the label is gone`() =
        runTest {
            val viewModel = matchViewModel()
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(300)
            assertNotNull(viewModel.ui.flash)
            assertNotEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)

            settle()

            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
            assertNull(viewModel.ui.flash)
            assertFalse(viewModel.ui.isResolving)
        }

    @Test
    fun `leaving the screen holds the move and coming back plays the rest of it once`() =
        runTest {
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(haptics = haptics)
            val start = viewModel.ui.displayState
            val events = RulesEngine.apply(selfBlend(start), start).events
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(100)

            viewModel.onSceneInactive()
            settle()

            assertEquals(start.applying(events[0]), viewModel.ui.displayState)
            assertTrue(viewModel.ui.isResolving)
            assertEquals(events.take(1), haptics.events)

            viewModel.onSceneActive()
            settle()

            assertEquals(events, haptics.events)
            assertFalse(viewModel.ui.isResolving)
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
        }

    @Test
    fun `a move made just before leaving the screen is all still there on return`() =
        runTest {
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(haptics = haptics)
            val start = viewModel.ui.displayState
            viewModel.tap(ownBanana)
            viewModel.tap(square(3, 0))

            viewModel.onSceneInactive()
            settle()
            assertEquals(start, viewModel.ui.displayState)
            assertTrue(viewModel.ui.isInputLocked)

            viewModel.onSceneActive()
            settle()
            assertEquals(viewModel.ui.authoritativeState.history.size, 1)
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
            assertIs<GameEvent.Moved>(haptics.events.first())
        }

    @Test
    fun `the phone is told about each event as the board shows it`() =
        runTest {
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(haptics = haptics)
            val start = viewModel.ui.displayState
            val seen = mutableListOf<GameState>()
            haptics.onPlay = { seen += viewModel.ui.displayState }

            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            settle()

            val events = RulesEngine.apply(selfBlend(start), start).events
            assertEquals(events, haptics.events)
            assertEquals(events.runningFold(start) { state, event -> state.applying(event) }.drop(1), seen)
        }

    // Labels over the board

    private fun TestScope.flashesDuring(
        state: GameState,
        mode: MatchMode = MatchMode.LocalVersus,
        play: (MatchViewModel) -> Unit,
    ): List<Pair<GameEvent, String?>> {
        val haptics = RecordingHaptics()
        val viewModel = matchViewModel(state, mode = mode, haptics = haptics)
        val flashes = mutableListOf<Pair<GameEvent, String?>>()
        haptics.onPlay = { flashes += it to viewModel.ui.flash?.text }
        play(viewModel)
        settle()
        assertNull(viewModel.ui.errorMessage)
        return flashes
    }

    @Test
    fun `a self-blend is labelled an own goal and the label stays through the score change`() =
        runTest {
            val flashes =
                flashesDuring(GameSetup.newGame()) {
                    aimSelfBlend(it)
                    it.confirmSelfBlend()
                }

            assertEquals(listOf(null, "Own goal!  −1", "Own goal!  −1", "Own goal!  −1"), flashes.map { it.second })
        }

    @Test
    fun `blending the other side's fruit shows the points won`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        spareFruit +
                            listOf(
                                blender(PlayerId.One, 0, at = square(3, 3)),
                                fruit(PlayerId.Two, PieceKind.Strawberry, row = 2, column = 2),
                            ),
                )

            val flashes =
                flashesDuring(state) {
                    it.tap(square(3, 3))
                    it.tap(square(2, 2))
                }

            assertEquals("+2", flashes.first { it.first is GameEvent.Blended }.second)
        }

    @Test
    fun `a delivery shows the doubled points`() =
        runTest {
            val state = GameState(pieces = spareFruit + fruit(PlayerId.One, PieceKind.Banana, row = 1, column = 0))

            val flashes =
                flashesDuring(state) {
                    it.tap(square(1, 0))
                    it.tap(square(0, 0))
                }

            assertEquals("×2  +2", flashes.first { it.first is GameEvent.Delivered }.second)
        }

    private fun blendersFacing(blenderBattles: Boolean) =
        GameState(
            pieces =
                spareFruit +
                    listOf(
                        blender(PlayerId.One, 0, at = square(3, 3)),
                        blender(PlayerId.Two, 0, at = square(2, 2)),
                    ),
            blenderBattles = blenderBattles,
        )

    @Test
    fun `a blender knocked out without a battle is labelled destroyed`() =
        runTest {
            val flashes =
                flashesDuring(blendersFacing(blenderBattles = false)) {
                    it.tap(square(3, 3))
                    it.tap(square(2, 2))
                }

            assertEquals("Blender destroyed", flashes.first { it.first is GameEvent.BlenderDestroyed }.second)
        }

    @Test
    fun `a battle shows its arithmetic and keeps it up while the loser leaves`() =
        runTest {
            val flashes =
                flashesDuring(blendersFacing(blenderBattles = true)) {
                    it.tap(square(3, 3))
                    it.tap(square(2, 2))
                }

            val report = flashes.firstNotNullOf { (it.first as? GameEvent.BlenderBattle)?.report }
            val expected =
                "Battle!  ${report.attackerTotal} v ${report.defenderTotal}" +
                    "  (rolled ${report.attackerRoll} and ${report.defenderRoll})"
            assertEquals(expected, flashes.first { it.first is GameEvent.BlenderBattle }.second)
            assertEquals(expected, flashes.first { it.first is GameEvent.BlenderDestroyed }.second)
        }

    @Test
    fun `a battle reads from the bottom player's side even when they are defending`() =
        runTest {
            val flashes =
                flashesDuring(
                    blendersFacing(blenderBattles = true),
                    mode = MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.Two),
                ) {
                    it.perform(Move(blender(PlayerId.One, 0, at = null).id, square(3, 3), square(2, 2)))
                }

            val report = flashes.firstNotNullOf { (it.first as? GameEvent.BlenderBattle)?.report }
            assertEquals(
                "Battle!  ${report.defenderTotal} v ${report.attackerTotal}" +
                    "  (rolled ${report.defenderRoll} and ${report.attackerRoll})",
                flashes.first { it.first is GameEvent.BlenderBattle }.second,
            )
        }

    // Effects

    @Test
    fun `a blend throws a burst, and a splatter when the dice say so`() =
        runTest {
            val viewModel = matchViewModel(blendEffects = BlendEffectPolicy.Always)
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            // Long enough for the blend to be shown, not for its pulp to be retired.
            advance(SHOWN_MILLIS)

            val burst = viewModel.ui.blendBursts.single()
            assertEquals(PieceKind.Banana, burst.kind)
            assertEquals(ownBanana, burst.position)
            val splatter = assertNotNull(viewModel.ui.splatter)
            assertEquals(burst.id, splatter.burstId)
            assertEquals(PieceKind.Banana, splatter.kind)
            assertNotEquals(burst.seed, splatter.seed)

            viewModel.retireSplatter(splatter.id + 1)
            assertNotNull(viewModel.ui.splatter)
            viewModel.retireSplatter(splatter.id)
            assertNull(viewModel.ui.splatter)
            viewModel.retireBurst(burst.id)
            assertEquals(emptyList(), viewModel.ui.blendBursts)
        }

    @Test
    fun `no splatter when the dice say no`() =
        runTest {
            val viewModel = matchViewModel(blendEffects = BlendEffectPolicy.Never)
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            // Long enough for the blend to be shown, not for its pulp to be retired.
            advance(SHOWN_MILLIS)

            assertEquals(1, viewModel.ui.blendBursts.size)
            assertNull(viewModel.ui.splatter)
        }

    @Test
    fun `the splatter chance is compared with the roll`() =
        runTest {
            val rolls = ArrayDeque(listOf(0.29, 0.3))
            val policy = BlendEffectPolicy(roll = { rolls.removeFirst() })
            assertEquals(0.3, policy.splatterChance)

            val under = matchViewModel(blendEffects = policy)
            aimSelfBlend(under)
            under.confirmSelfBlend()
            val at = matchViewModel(blendEffects = policy)
            aimSelfBlend(at)
            at.confirmSelfBlend()
            // Long enough for the blend to be shown, not for its pulp to be retired.
            advance(SHOWN_MILLIS)

            assertNotNull(under.ui.splatter)
            assertNull(at.ui.splatter)
        }

    @Test
    fun `a destroyed blender throws a blender burst and never splatters`() =
        runTest {
            val viewModel =
                matchViewModel(blendersFacing(blenderBattles = false), blendEffects = BlendEffectPolicy.Always)

            viewModel.tap(square(3, 3))
            viewModel.tap(square(2, 2))
            // Long enough for the blend to be shown, not for its pulp to be retired.
            advance(SHOWN_MILLIS)

            val burst = viewModel.ui.blendBursts.single()
            assertEquals(PieceKind.Blender, burst.kind)
            assertEquals(square(2, 2), burst.position)
            assertNull(viewModel.ui.splatter)
        }

    @Test
    fun `the view model retires a burst and a splatter by itself when the view never does`() =
        runTest {
            val viewModel = matchViewModel(blendEffects = BlendEffectPolicy.Always)
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(SHOWN_MILLIS)
            assertEquals(1, viewModel.ui.blendBursts.size)
            assertNotNull(viewModel.ui.splatter)

            advance(BlendBurst.LIFETIME_MILLIS)
            assertEquals(emptyList(), viewModel.ui.blendBursts)
            assertNotNull(viewModel.ui.splatter, "the splatter lives longer than the burst")

            advance(Splatter.LIFETIME_MILLIS)
            assertNull(viewModel.ui.splatter)
        }

    @Test
    fun `effects do not pile up over many moves when nothing retires them`() =
        runTest {
            val viewModel = matchViewModel(blendEffects = BlendEffectPolicy.Always)
            var mostBursts = 0
            var moves = 0
            while (moves < 120 && !viewModel.ui.authoritativeState.status.isOver) {
                // Blends first, so plenty of pulp is thrown.
                val actions = RulesEngine.legalActions(viewModel.ui.authoritativeState)
                val action =
                    actions.firstOrNull { action ->
                        RulesEngine
                            .apply(
                                action,
                                viewModel.ui.authoritativeState,
                            ).events
                            .any { it is GameEvent.Blended }
                    } ?: actions.first()
                viewModel.perform(action)
                // Played straight through, as quickly as the timeline allows.
                while (viewModel.ui.isResolving) advance(10)
                mostBursts = maxOf(mostBursts, viewModel.ui.blendBursts.size)
                moves += 1
            }
            settle()

            assertTrue(moves > 10, "the match was long enough to say something")
            assertTrue(mostBursts <= 4, "bursts piled up to $mostBursts")
            assertEquals(emptyList(), viewModel.ui.blendBursts)
            assertNull(viewModel.ui.splatter)
        }

    @Test
    fun `attached haptics hear every event until they are detached, and are then let go`() =
        runTest {
            val viewModel = matchViewModel()
            val attached = RecordingHaptics()
            viewModel.attachHaptics(attached)
            assertTrue(viewModel.hasAttachedHaptics)

            viewModel.tap(square(4, 1))
            viewModel.tap(square(3, 1))
            settle()
            assertEquals(1, attached.selections)
            assertTrue(attached.events.isNotEmpty())

            viewModel.detachHaptics(RecordingHaptics())
            assertTrue(viewModel.hasAttachedHaptics, "only the haptics that were attached can be detached")
            viewModel.detachHaptics(attached)
            assertFalse(viewModel.hasAttachedHaptics)

            val heard = attached.events.size
            viewModel.tap(square(1, 1))
            viewModel.tap(square(2, 1))
            settle()
            assertEquals(1, attached.selections)
            assertEquals(heard, attached.events.size)
        }

    // Restart and undo

    @Test
    fun `restart asks first`() =
        runTest {
            val viewModel = matchViewModel()

            viewModel.requestRestart()
            assertTrue(viewModel.ui.isShowingRestartConfirmation)

            viewModel.cancelRestart()
            assertFalse(viewModel.ui.isShowingRestartConfirmation)
        }

    @Test
    fun `restart deals the same kind of match again and clears everything else`() =
        runTest {
            val options =
                GameSetup.newGame(
                    ruleSet = RuleSet.Wildcard,
                    boardSetup = BoardSetup.Blendy,
                    boardWrap = BoardWrap.Sides,
                    blenderBattles = true,
                )
            val viewModel = matchViewModel(options, blendEffects = BlendEffectPolicy.Always)
            // On a Blendy board the blenders stand in front of the fruit.
            viewModel.tap(square(4, 1))
            viewModel.tap(square(5, 0))
            viewModel.confirmSelfBlend()
            settle()
            viewModel.tap(square(0, 5))
            viewModel.requestRestart()
            assertNotEquals(Scores(), viewModel.ui.displayState.scores)
            assertNotNull(viewModel.ui.selectedPieceId)

            viewModel.restart()

            assertEquals(
                MatchUiState(mode = MatchMode.LocalVersus, displayState = options, authoritativeState = options),
                viewModel.ui,
            )
        }

    @Test
    fun `restarting in the middle of a move stops the move`() =
        runTest {
            val haptics = RecordingHaptics()
            val viewModel = matchViewModel(haptics = haptics)
            val fresh = viewModel.ui.displayState
            aimSelfBlend(viewModel)
            viewModel.confirmSelfBlend()
            advance(100)

            viewModel.restart()
            settle()

            assertEquals(fresh, viewModel.ui.displayState)
            assertEquals(fresh, viewModel.ui.authoritativeState)
            assertFalse(viewModel.ui.isResolving)
            assertEquals(1, haptics.events.size)

            viewModel.tap(ownBanana)
            viewModel.tap(square(3, 0))
            settle()
            assertEquals(1, viewModel.ui.authoritativeState.history.size)
            assertEquals(viewModel.ui.authoritativeState, viewModel.ui.displayState)
        }

    @Test
    fun `restarting a random board deals a different one`() =
        runTest {
            val first = GameSetup.newGame(boardSetup = BoardSetup.Random, seed = 7uL)
            val viewModel = matchViewModel(first)

            viewModel.restart()

            assertEquals(BoardSetup.Random, viewModel.ui.displayState.boardSetup)
            assertNotEquals(first.pieces, viewModel.ui.displayState.pieces)
            assertEquals(first.pieces.size, viewModel.ui.displayState.pieces.size)
        }

    @Test
    fun `undo steps back one move at a time`() =
        runTest {
            val viewModel = matchViewModel()
            val start = viewModel.ui.authoritativeState
            assertFalse(viewModel.ui.canUndo)
            viewModel.tap(ownBanana)
            viewModel.tap(square(3, 0))
            assertFalse(viewModel.ui.canUndo)
            viewModel.undo()
            assertTrue(viewModel.ui.isResolving)
            settle()
            assertTrue(viewModel.ui.canUndo)

            viewModel.undo()

            assertEquals(start, viewModel.ui.authoritativeState)
            assertEquals(start, viewModel.ui.displayState)
            assertFalse(viewModel.ui.canUndo)
        }

    // Errors

    @Test
    fun `a move the engine refuses is reported, and the report can be dismissed`() =
        runTest {
            val viewModel = matchViewModel()
            val start = viewModel.ui
            val banana = checkNotNull(start.displayState.pieceAt(ownBanana))

            viewModel.perform(Move(banana.id, ownBanana, square(0, 0)))
            settle()

            assertEquals("That square is not a legal destination.", viewModel.ui.errorMessage)
            assertEquals(start.copy(errorMessage = viewModel.ui.errorMessage), viewModel.ui)

            viewModel.dismissError()
            assertEquals(start, viewModel.ui)
        }

    // Presentation

    @Test
    fun `the turn indicator names whoever is to move`() =
        runTest {
            val viewModel = matchViewModel()
            assertEquals("Player One to move", viewModel.ui.turnIndicatorText)

            val thinking =
                viewModel.ui.copy(
                    mode = MatchMode.Computer(CpuDifficulty.Brutal, humanPlays = PlayerId.One),
                    isOpponentThinking = true,
                )
            assertEquals("Brutal CPU is thinking…", thinking.turnIndicatorText)
            assertTrue(thinking.isInputLocked)
        }

    @Test
    fun `a win names the winner and how`() =
        runTest {
            fun won(
                by: WinReason,
                mode: MatchMode = MatchMode.LocalVersus,
            ) = matchViewModel(
                GameSetup.newGame().updated(status = GameStatus.Ended(Outcome.Win(PlayerId.Two, by))),
                mode = mode,
            ).ui

            assertEquals("Player Two wins.", won(WinReason.Score).resultText)
            assertNull(won(WinReason.Score).resultDetail)
            assertEquals("Player Two wins by Banana Split.", won(WinReason.BananaSplit).resultText)
            assertEquals("Every banana across. The score did not matter.", won(WinReason.BananaSplit).resultDetail)
            assertEquals(
                "Hard CPU wins.",
                won(WinReason.Score, MatchMode.Computer(CpuDifficulty.Hard, humanPlays = PlayerId.One)).resultText,
            )
            assertNull(matchViewModel().ui.resultText)
        }

    @Test
    fun `the scoreboard says who is to move and what fruit is left`() =
        runTest {
            val viewModel = matchViewModel()

            val one = viewModel.ui.scoreboard(PlayerId.One)
            val two = viewModel.ui.scoreboard(PlayerId.Two)

            assertEquals("Player One", one.name)
            assertEquals(0, one.score)
            assertEquals(6, one.remainingFruit.size)
            assertEquals(12, one.pointsOnBoard)
            assertTrue(one.isActive)
            assertFalse(one.isOnLastFruit)
            assertNull(one.wildcards)
            assertEquals("· 12 pts on the board", one.fruitDetail)
            assertEquals("Player One, score 0, 6 fruit left, to move", one.accessibilityLabel)
            assertFalse(two.isActive)
            assertEquals("Player Two, score 0, 6 fruit left", two.accessibilityLabel)
        }

    @Test
    fun `the scoreboard warns on the last fruit and carries the wildcard standing when wildcards are on`() =
        runTest {
            val state =
                GameState(
                    pieces =
                        listOf(
                            fruit(PlayerId.One, PieceKind.Pineapple, row = 4, column = 3),
                            fruit(PlayerId.Two, PieceKind.Banana, row = 1, column = 5),
                            fruit(PlayerId.Two, PieceKind.Strawberry, row = 1, column = 4),
                        ),
                    scores = Scores(one = -2, two = 5),
                    ruleSet = RuleSet.Wildcard,
                )
            val viewModel = matchViewModel(state)

            val one = viewModel.ui.scoreboard(PlayerId.One)

            assertTrue(one.isOnLastFruit)
            assertEquals("· last fruit — ends the match", one.fruitDetail)
            assertEquals("Player One, score -2, 1 fruit left, to move", one.accessibilityLabel)
            assertNotNull(one.wildcards)
            assertEquals("· 3 pts on the board", viewModel.ui.scoreboard(PlayerId.Two).fruitDetail)
            assertEquals("no fruit left", one.copy(remainingFruit = emptyList(), isOnLastFruit = false).fruitDetail)
        }

    @Test
    fun `the board is turned round when the bottom seat is player two`() =
        runTest {
            val local = matchViewModel().ui
            val asTwo = matchViewModel(mode = MatchMode.Computer(CpuDifficulty.Easy, humanPlays = PlayerId.Two)).ui

            assertFalse(local.isRotated)
            assertEquals(PlayerId.One, local.bottomPlayer)
            assertTrue(asTwo.isRotated)
            assertEquals(PlayerId.Two, asTwo.bottomPlayer)
            assertEquals(PlayerId.One, asTwo.topPlayer)
        }

    @Test
    fun `only a match played on one phone can be played again or undone`() =
        runTest {
            val link = matchViewModel(mode = MatchMode.ByLink(matchId = "abc", localPlayer = PlayerId.One))
            link.tap(ownBanana)
            link.tap(square(3, 0))
            settle()

            assertFalse(link.ui.canPlayAgain)
            assertFalse(link.ui.canUndo)
            assertTrue(link.ui.isInputLocked)
            assertTrue(matchViewModel().ui.canPlayAgain)
        }

    // The first-match introduction

    /** Settings that are still being read until [arrive] is called. */
    private class SlowSettingsStore : SettingsStore {
        private val stored = MutableStateFlow<Settings?>(null)
        override val settings: Flow<Settings> = stored.filterNotNull()

        fun arrive(settings: Settings) {
            stored.value = settings
        }

        override suspend fun update(transform: (Settings) -> Settings) {
            stored.value = transform(stored.value ?: Settings())
        }
    }

    @Test
    fun `a first-ever match opens under the introduction`() =
        runTest {
            val viewModel = matchViewModel(settings = InMemorySettingsStore())

            assertTrue(viewModel.ui.isTeachingOverlayShowing)
        }

    @Test
    fun `closing the introduction records it as seen, and the next match does not show it`() =
        runTest {
            val store = InMemorySettingsStore()
            val first = matchViewModel(settings = store)

            first.dismissTeachingOverlay()

            assertFalse(first.ui.isTeachingOverlayShowing, "it closes on the call, before the write lands")
            settle()
            assertTrue(store.current.hasSeenTeachingOverlay)
            assertFalse(matchViewModel(settings = store).ui.isTeachingOverlayShowing)
        }

    @Test
    fun `a returning player never sees the introduction, even while the settings are being read`() =
        runTest {
            val store = SlowSettingsStore()
            val viewModel = matchViewModel(settings = store)
            assertFalse(viewModel.ui.isTeachingOverlayShowing, "nothing is shown before the settings arrive")

            store.arrive(Settings(hasSeenTeachingOverlay = true))
            settle()

            assertFalse(viewModel.ui.isTeachingOverlayShowing)
        }

    @Test
    fun `a first-time player sees the introduction once the settings arrive`() =
        runTest {
            val store = SlowSettingsStore()
            val viewModel = matchViewModel(settings = store)

            store.arrive(Settings())
            settle()

            assertTrue(viewModel.ui.isTeachingOverlayShowing)
        }

    @Test
    fun `restarting under the introduction keeps it up`() =
        runTest {
            val viewModel = matchViewModel(settings = InMemorySettingsStore())

            viewModel.restart()

            assertTrue(viewModel.ui.isTeachingOverlayShowing)
        }

    private companion object {
        // Every blend in these tests is shown well before this, and none is retired until after.
        const val SHOWN_MILLIS = 700L
    }
}
