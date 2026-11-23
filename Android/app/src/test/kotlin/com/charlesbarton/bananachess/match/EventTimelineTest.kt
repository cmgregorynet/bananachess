package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventTimelineTest {
    private val id = PieceId("one.blender.0")
    private val at = square(2, 2)
    private val moved = GameEvent.Moved(id, square(3, 3), at)
    private val battle =
        GameEvent.BlenderBattle(
            BattleReport(
                PlayerId.One,
                attackerFruit = 3,
                attackerBonus = 1,
                attackerRoll = 4,
                defenderFruit = 2,
                defenderRoll = 1,
            ),
            at,
        )
    private val destroyed = GameEvent.BlenderDestroyed(PieceId("two.blender.0"), PlayerId.Two, by = id, at = at)
    private val turnChanged = GameEvent.TurnChanged(PlayerId.Two)

    private class Stage(
        scope: TestScope,
        reduceMotion: Boolean = false,
    ) {
        val shown = mutableListOf<Pair<GameEvent, GameEvent?>>()
        var finishes = 0
        val timeline =
            EventTimeline(
                scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler)),
                reduceMotion = { reduceMotion },
                show = { event, previous -> shown += event to previous },
                finished = { finishes += 1 },
            )
        val events: List<GameEvent> get() = shown.map { it.first }
    }

    @Test
    fun `every kind of event holds for its own beat`() {
        val beats =
            mapOf(
                moved to 280L,
                destroyed to 480L,
                GameEvent.BlenderRestored(id, PlayerId.One, at, cost = 3) to 380L,
                GameEvent.BananaReturned(id, at) to 380L,
                GameEvent.BlenderRespawned(id, PlayerId.One, at) to 380L,
                GameEvent.PineappleTurnedGolden(id, PlayerId.One) to 620L,
                GameEvent.Smoothie(PlayerId.One, listOf(id), respawnTurn = 4) to 620L,
                GameEvent.SmoothiePoured(PlayerId.One, points = 3) to 620L,
                battle to 900L,
                GameEvent.Blended(id, PieceKind.Banana, PlayerId.Two, at, PlayerId.One, points = 1) to 420L,
                GameEvent.Delivered(id, PieceKind.Banana, PlayerId.One, at, points = 2) to 320L,
                GameEvent.ScoreChanged(PlayerId.One, newScore = 2) to 120L,
                GameEvent.TurnPassed(PlayerId.Two) to 60L,
                turnChanged to 60L,
                GameEvent.GameEnded(Outcome.Draw) to 60L,
            )

        for ((event, beat) in beats) {
            assertEquals(beat, EventTimeline.beatMillis(event, reduceMotion = false), "$event")
            assertEquals(90L, EventTimeline.beatMillis(event, reduceMotion = true), "$event under reduced motion")
        }
    }

    @Test
    fun `events are shown in order, each after the beat of the one before`() =
        runTest {
            val stage = Stage(this)

            stage.timeline.play(listOf(moved, battle, destroyed, turnChanged))
            advance(0)
            assertEquals(listOf<GameEvent>(moved), stage.events)

            advance(279)
            assertEquals(listOf<GameEvent>(moved), stage.events)
            advance(1)
            assertEquals(listOf(moved, battle), stage.events)

            advance(899)
            assertEquals(listOf(moved, battle), stage.events)
            advance(1)
            assertEquals(listOf(moved, battle, destroyed), stage.events)

            advance(480)
            assertEquals(listOf(moved, battle, destroyed, turnChanged), stage.events)
            assertEquals(0, stage.finishes)
            assertTrue(stage.timeline.isPlaying)

            advance(59)
            assertEquals(0, stage.finishes)
            advance(1)
            assertEquals(1, stage.finishes)
            assertFalse(stage.timeline.isPlaying)
        }

    @Test
    fun `each event is told which one the board showed before it`() =
        runTest {
            val stage = Stage(this)

            stage.timeline.play(listOf(battle, destroyed))
            settle()

            assertEquals(listOf<Pair<GameEvent, GameEvent?>>(battle to null, destroyed to battle), stage.shown)
        }

    @Test
    fun `reduced motion plays every event at 90 ms`() =
        runTest {
            val stage = Stage(this, reduceMotion = true)

            stage.timeline.play(listOf(moved, battle, destroyed))
            advance(89)
            assertEquals(listOf<GameEvent>(moved), stage.events)
            advance(1)
            assertEquals(listOf(moved, battle), stage.events)
            advance(90)
            assertEquals(listOf(moved, battle, destroyed), stage.events)
            advance(90)
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `a paused timeline keeps what it has not shown and resumes with the next event`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(moved, battle, destroyed))
            advance(100)

            stage.timeline.pause()
            settle()
            assertEquals(listOf<GameEvent>(moved), stage.events)
            assertEquals(0, stage.finishes)
            assertTrue(stage.timeline.isPlaying)

            stage.timeline.resume()
            settle()
            assertEquals(
                listOf<Pair<GameEvent, GameEvent?>>(moved to null, battle to moved, destroyed to battle),
                stage.shown,
            )
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `pausing before the first event has been shown loses nothing`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(moved, turnChanged))

            stage.timeline.pause()
            settle()
            assertEquals(emptyList(), stage.events)

            stage.timeline.resume()
            settle()
            assertEquals(listOf(moved, turnChanged), stage.events)
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `a timeline paused during its last beat finishes on resume`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(turnChanged))
            advance(30)

            stage.timeline.pause()
            settle()
            assertEquals(0, stage.finishes)

            stage.timeline.resume()
            settle()
            assertEquals(listOf<GameEvent>(turnChanged), stage.events)
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `events handed over while paused wait for the resume`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.pause()

            stage.timeline.play(listOf(moved))
            settle()
            assertEquals(emptyList(), stage.events)

            stage.timeline.resume()
            settle()
            assertEquals(listOf<GameEvent>(moved), stage.events)
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `resuming twice does not start a second run`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(moved, turnChanged))
            advance(0)

            stage.timeline.resume()
            stage.timeline.resume()
            settle()

            assertEquals(listOf(moved, turnChanged), stage.events)
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `events handed over mid-run join the queue without cutting the current beat short`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(moved))
            advance(100)

            stage.timeline.play(listOf(turnChanged))
            advance(179)
            assertEquals(listOf<GameEvent>(moved), stage.events)
            advance(1)
            assertEquals(listOf(moved, turnChanged), stage.events)
            settle()
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `stopping drops the queue and never reports a finish`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(moved, battle, destroyed))
            advance(100)

            stage.timeline.stop()
            settle()

            assertEquals(listOf<GameEvent>(moved), stage.events)
            assertEquals(0, stage.finishes)
            assertFalse(stage.timeline.isPlaying)
        }

    @Test
    fun `a run started after a stop begins with no memory of the last one`() =
        runTest {
            val stage = Stage(this)
            stage.timeline.play(listOf(battle, moved))
            advance(0)
            stage.timeline.stop()

            stage.timeline.play(listOf(destroyed))
            settle()

            assertEquals(destroyed to null, stage.shown.last())
            assertEquals(1, stage.finishes)
        }

    @Test
    fun `a move with no events still finishes`() =
        runTest {
            val stage = Stage(this)

            stage.timeline.play(emptyList())
            assertTrue(stage.timeline.isPlaying)
            settle()

            assertEquals(1, stage.finishes)
            assertFalse(stage.timeline.isPlaying)
        }

    @Test
    fun `finishing may hand over the next events at once`() =
        runTest {
            val shown = mutableListOf<GameEvent>()
            var finishes = 0
            lateinit var timeline: EventTimeline
            timeline =
                EventTimeline(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    reduceMotion = { false },
                    show = { event, _ -> shown += event },
                    finished = {
                        finishes += 1
                        if (finishes == 1) timeline.play(listOf(turnChanged))
                    },
                )

            timeline.play(listOf(moved))
            settle()

            assertEquals(listOf(moved, turnChanged), shown)
            assertEquals(2, finishes)
            assertFalse(timeline.isPlaying)
        }
}
