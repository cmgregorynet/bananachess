package com.charlesbarton.bananachess.match

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MatchHapticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = TestScope()

    // region Which tap each event is

    @Test
    fun `each event taps back as it does on iOS, and the rest pass quietly`() {
        val id = PieceId("one.blender.0")
        val at = square(2, 2)
        val report =
            BattleReport(
                attacker = PlayerId.One,
                attackerFruit = 3,
                attackerBonus = 1,
                attackerRoll = 4,
                defenderFruit = 2,
                defenderRoll = 3,
            )
        val expected: List<Pair<GameEvent, MatchFeedback?>> =
            listOf(
                GameEvent.Moved(id, square(3, 3), at) to MatchFeedback.Light,
                GameEvent.BlenderBattle(report, at) to MatchFeedback.Heavy,
                GameEvent.BlenderDestroyed(id, PlayerId.Two, PieceId("one.blender.1"), at) to MatchFeedback.Reject,
                GameEvent.Smoothie(PlayerId.One, listOf(id), respawnTurn = 9) to MatchFeedback.Reject,
                GameEvent.BlenderRestored(id, PlayerId.One, at, cost = 2) to MatchFeedback.Soft,
                GameEvent.BlenderRespawned(id, PlayerId.One, at) to MatchFeedback.Soft,
                GameEvent.BananaReturned(PieceId("one.banana.0"), at) to MatchFeedback.Soft,
                blended() to MatchFeedback.Medium,
                GameEvent.Delivered(PieceId("one.banana.0"), PieceKind.Banana, PlayerId.One, at, 2) to
                    MatchFeedback.Confirm,
                GameEvent.SmoothiePoured(PlayerId.One, points = 5) to MatchFeedback.Confirm,
                GameEvent.GameEnded(Outcome.Draw) to MatchFeedback.Confirm,
                GameEvent.ScoreChanged(PlayerId.One, newScore = 3) to null,
                GameEvent.TurnPassed(PlayerId.Two) to null,
                GameEvent.TurnChanged(PlayerId.Two) to null,
                GameEvent.PineappleTurnedGolden(PieceId("one.pineapple.1"), PlayerId.One) to null,
            )
        for ((event, feedback) in expected) assertEquals(feedback, MatchFeedback.of(event), "$event")
    }

    // endregion

    // region Which constant each API level gets

    @Test
    fun `on Android 8·0 the newer taps fall back to the nearest older ones`() {
        val api26 = 26
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, MatchFeedback.constant(MatchFeedback.Selection, api26))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, MatchFeedback.constant(MatchFeedback.Light, api26))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, MatchFeedback.constant(MatchFeedback.Medium, api26))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, MatchFeedback.constant(MatchFeedback.Heavy, api26))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, MatchFeedback.constant(MatchFeedback.Soft, api26))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, MatchFeedback.constant(MatchFeedback.Reject, api26))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, MatchFeedback.constant(MatchFeedback.Confirm, api26))
    }

    @Test
    fun `newer phones get the release, confirm and reject taps`() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE, MatchFeedback.constant(MatchFeedback.Soft, 27))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, MatchFeedback.constant(MatchFeedback.Reject, 29))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, MatchFeedback.constant(MatchFeedback.Confirm, 29))
        for (api in listOf(30, 35)) {
            assertEquals(HapticFeedbackConstants.REJECT, MatchFeedback.constant(MatchFeedback.Reject, api))
            assertEquals(HapticFeedbackConstants.CONFIRM, MatchFeedback.constant(MatchFeedback.Confirm, api))
        }
    }

    // endregion

    // region Through a view

    private class RecordingView(
        context: Context,
    ) : View(context) {
        val performed = mutableListOf<Int>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            performed += feedbackConstant
            return super.performHapticFeedback(feedbackConstant)
        }
    }

    @Test
    fun `the phone taps through its view, with no vibrate permission`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        val haptics = AndroidMatchHaptics(view, sdkInt = 35)

        haptics.selection()
        haptics.play(blended())
        haptics.play(GameEvent.TurnChanged(PlayerId.Two))
        haptics.play(GameEvent.GameEnded(Outcome.Draw))

        assertEquals(
            listOf(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.CONFIRM,
            ),
            view.performed,
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("VIBRATE" in manifest)
    }

    @Test
    fun `the match screen lends the view model its haptics only while it is shown`() {
        val viewModel = scope.matchViewModel()
        var showing by mutableStateOf(true)
        composeRule.setContent {
            PaperTheme {
                if (showing) {
                    MatchScreen(
                        viewModel = viewModel,
                        settings = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true)),
                        onExit = {},
                        onShowRules = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(viewModel.hasAttachedHaptics)

        showing = false
        composeRule.waitForIdle()
        assertFalse(viewModel.hasAttachedHaptics, "the view model must not keep the activity's view")
    }

    // endregion

    private fun blended() =
        GameEvent.Blended(
            pieceId = PieceId("two.banana.0"),
            kind = PieceKind.Banana,
            fruitOwner = PlayerId.Two,
            awardedTo = PlayerId.One,
            points = 1,
            at = square(2, 2),
        )
}
