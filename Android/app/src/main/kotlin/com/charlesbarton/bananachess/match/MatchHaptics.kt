package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.GameEvent

/**
 * Where the match asks the phone to tap back. The view model only says what happened; which
 * vibration that becomes is the device's business.
 */
interface MatchHaptics {
    /** A piece was picked up. */
    fun selection() {}

    /** [event] has just been shown on the board. */
    fun play(event: GameEvent) {}

    companion object {
        val None: MatchHaptics = object : MatchHaptics {}
    }
}
