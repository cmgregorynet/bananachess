package com.charlesbarton.bananachess.match

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.charlesbarton.bananachess.engine.model.GameEvent

/**
 * The kinds of tap the match asks for. Light and sparing, matching the calm the art bible asks
 * for: a tick on selection, a knock on collision, a soft rise on a score. Nothing buzzes.
 */
enum class MatchFeedback {
    Selection,
    Light,
    Medium,
    Heavy,
    Soft,
    Reject,
    Confirm,
    ;

    companion object {
        /** What [event] feels like, or null for the events that pass without a tap. */
        fun of(event: GameEvent): MatchFeedback? =
            when (event) {
                is GameEvent.Moved -> Light
                is GameEvent.BlenderBattle -> Heavy
                is GameEvent.BlenderDestroyed, is GameEvent.Smoothie -> Reject
                is GameEvent.BlenderRestored, is GameEvent.BananaReturned, is GameEvent.BlenderRespawned -> Soft
                is GameEvent.Blended -> Medium
                is GameEvent.Delivered, is GameEvent.SmoothiePoured, is GameEvent.GameEnded -> Confirm
                else -> null
            }

        /**
         * The closest [HapticFeedbackConstants] value on a device running [sdkInt]. Confirm and
         * reject arrived in Android 11; before that a click and a long press stand in.
         */
        fun constant(
            feedback: MatchFeedback,
            sdkInt: Int,
        ): Int =
            when (feedback) {
                Selection -> HapticFeedbackConstants.CLOCK_TICK
                Light -> HapticFeedbackConstants.CONTEXT_CLICK
                Medium -> HapticFeedbackConstants.VIRTUAL_KEY
                Heavy -> HapticFeedbackConstants.LONG_PRESS
                Soft -> softConstant(sdkInt)
                Reject -> if (sdkInt >= Build.VERSION_CODES.R) REJECT else HapticFeedbackConstants.LONG_PRESS
                Confirm -> if (sdkInt >= Build.VERSION_CODES.R) CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            }

        private fun softConstant(sdkInt: Int): Int =
            if (sdkInt >= Build.VERSION_CODES.O_MR1) VIRTUAL_KEY_RELEASE else HapticFeedbackConstants.CLOCK_TICK

        // The platform's values, spelled out because the constants themselves are newer than
        // the minimum SDK; each is only used on a device that has it.
        internal const val VIRTUAL_KEY_RELEASE = 8
        internal const val CONFIRM = 16
        internal const val REJECT = 17
    }
}

/**
 * Taps back through [view], so no vibrate permission is needed and the user's system haptics
 * setting is honoured: without the ignore-settings flag the platform skips feedback the user
 * has turned off.
 *
 * It holds a view, so it lives only as long as the screen: the screen attaches it to the view
 * model on entering and detaches it on leaving.
 */
class AndroidMatchHaptics(
    private val view: View,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : MatchHaptics {
    override fun selection() = perform(MatchFeedback.Selection)

    override fun play(event: GameEvent) {
        MatchFeedback.of(event)?.let(::perform)
    }

    private fun perform(feedback: MatchFeedback) {
        view.performHapticFeedback(MatchFeedback.constant(feedback, sdkInt))
    }
}
