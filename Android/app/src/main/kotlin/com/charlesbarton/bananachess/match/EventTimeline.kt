package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.GameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Performs the engine's events one beat at a time.
 *
 * A queue rather than a loop over a local list, so a timeline paused part-way through picks
 * up at the next event instead of starting the move again. Everything runs on [scope]'s one
 * thread and the job can only be interrupted at its delay, so an event is either fully shown
 * and off the queue, or still on it. That is what lets a paused timeline resume without
 * showing anything twice.
 *
 * @param show called for each event with the one shown before it in this run, if any.
 * @param finished called once the queue has been played out.
 */
internal class EventTimeline(
    private val scope: CoroutineScope,
    private val reduceMotion: () -> Boolean,
    private val show: (event: GameEvent, previous: GameEvent?) -> Unit,
    private val finished: () -> Unit,
) {
    private val pending = ArrayDeque<GameEvent>()

    // Kept across a pause, so a resumed timeline still knows what the board last showed.
    private var lastShown: GameEvent? = null
    private var job: Job? = null
    private var isPaused = false

    /** Whether events have been handed over that [finished] has not yet answered. */
    var isPlaying = false
        private set

    /** Adds [events] to the end of the queue and plays it, unless the timeline is paused. */
    fun play(events: List<GameEvent>) {
        isPlaying = true
        pending.addAll(events)
        run()
    }

    /** Stops between events. Whatever is queued stays queued. */
    fun pause() {
        isPaused = true
        cancelJob()
    }

    /** Carries on from the next queued event. */
    fun resume() {
        isPaused = false
        if (isPlaying) run()
    }

    /** Drops everything still queued, without calling [finished]. */
    fun stop() {
        cancelJob()
        pending.clear()
        lastShown = null
        isPlaying = false
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun run() {
        if (isPaused || job?.isActive == true) return
        // Stored before it starts: on an immediate dispatcher the body runs inside `launch`, and
        // anything it started in turn would otherwise be overwritten here.
        val run =
            scope.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    val event = pending.removeFirstOrNull() ?: break
                    show(event, lastShown)
                    lastShown = event
                    delay(beatMillis(event, reduceMotion()))
                }
                // Cleared first: finishing may hand over the next move's events at once.
                job = null
                lastShown = null
                isPlaying = false
                finished()
            }
        job = run
        run.start()
    }

    companion object {
        const val REDUCED_MOTION_BEAT_MILLIS: Long = 90

        /** How long the board holds on [event] before the next one. */
        fun beatMillis(
            event: GameEvent,
            reduceMotion: Boolean,
        ): Long {
            if (reduceMotion) return REDUCED_MOTION_BEAT_MILLIS
            return when (event) {
                is GameEvent.Moved -> 280

                // Losing a blender to combat is permanent, so it gets a beat of its own.
                is GameEvent.BlenderDestroyed -> 480

                is GameEvent.BlenderRestored,
                is GameEvent.BananaReturned,
                is GameEvent.BlenderRespawned,
                -> 380

                // The two big wildcard moments get a beat to land before play carries on.
                is GameEvent.PineappleTurnedGolden,
                is GameEvent.Smoothie,
                is GameEvent.SmoothiePoured,
                -> 620

                // Long enough to actually read the arithmetic before the board moves on.
                is GameEvent.BlenderBattle -> 900

                // Long enough for the pulp to read; the tail of the burst is allowed to overrun
                // into the next event rather than holding up the turn.
                is GameEvent.Blended -> 420

                is GameEvent.Delivered -> 320

                is GameEvent.ScoreChanged -> 120

                is GameEvent.TurnPassed,
                is GameEvent.TurnChanged,
                is GameEvent.GameEnded,
                -> 60
            }
        }
    }
}
