package com.charlesbarton.bananachess.link

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.charlesbarton.bananachess.BananaChessApplication
import com.charlesbarton.bananachess.MainActivity
import com.charlesbarton.bananachess.engine.online.MatchOptions
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchSnapshot
import com.charlesbarton.bananachess.match.MatchTags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Play by link through the real activity: links arriving by intent, the alert for a bad one on
 * every screen, and sending a move through the share sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LinkUiTest {
    /**
     * The activity each test starts itself, with the intent it is about. Driven by Robolectric
     * directly: every lifecycle step happens on the call, so nothing waits on the main thread
     * that the compose rule is running the test on.
     */
    class LaunchedActivity : ExternalResource() {
        var controller: ActivityController<MainActivity>? = null

        override fun after() {
            controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        }
    }

    private val launched = LaunchedActivity()

    @get:Rule
    val composeRule = AndroidComposeTestRule(activityRule = launched) { checkNotNull(it.controller).get() }

    private val app: BananaChessApplication = ApplicationProvider.getApplicationContext()
    private val controller: ActivityController<MainActivity> get() = checkNotNull(launched.controller)

    // Every test here is about getting into and out of a match, so the introduction is marked as seen.
    @Before
    fun skipTheIntroduction() {
        runBlocking { app.container.settings.update { it.copy(hasSeenTeachingOverlay = true) } }
    }

    // region Helpers

    private fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun launch(intent: Intent? = null) {
        launched.controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
    }

    /** As the system hands a link to the app while it is already open. */
    private fun deliver(intent: Intent) {
        controller.newIntent(intent)
        composeRule.waitForIdle()
    }

    /** Reading a link waits on the snapshot file, which is on a thread Compose does not watch. */
    private fun waitForTag(tag: String) =
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun square(
        row: Int,
        column: Int,
    ): SemanticsNodeInteraction = composeRule.onNodeWithTag("square_${row}_$column")

    private fun assertAlert(message: String) {
        waitForText(ALERT_TITLE)
        composeRule.onNodeWithText(ALERT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    private fun dismissAlert() {
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ALERT_TITLE).assertDoesNotExist()
    }

    private fun openLocalMatchAndMove() {
        composeRule.onNodeWithText("Local Two Player").performClick()
        composeRule.waitForIdle()
        square(4, 0).performClick()
        square(3, 0).performClick()
        composeRule.waitForIdle()
        square(3, 0).assertContentDescriptionContains("Player One banana", substring = true)
    }

    private fun assertOnHome() {
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertDoesNotExist()
        composeRule.onNodeWithText("Local Two Player").assertIsDisplayed()
    }

    // endregion

    @Test
    fun `a bananachess match link resolves to the app`() {
        val view =
            viewIntent("bananachess://m/1/x")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE)

        val handlers = app.packageManager.queryIntentActivities(view, 0).map { it.activityInfo.name }

        assertEquals(listOf(MainActivity::class.java.name), handlers)
        // Only the match host: the scheme alone does not open the app.
        assertEquals(emptyList(), app.packageManager.queryIntentActivities(viewIntent("bananachess://x/1/x"), 0))
    }

    @Test
    fun `launched by an invitation, the app opens its match with the opener to move`() {
        launch(viewIntent(INVITATION))

        waitForTag(MatchTags.SCREEN)
        composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assertTextEquals("You to move")
        square(4, 0).assertContentDescriptionContains("You banana", substring = true)
        composeRule.onNodeWithTag(LinkTags.SEND).assertDoesNotExist()
    }

    @Test
    fun `the same link is opened once, however often the activity is recreated`() {
        launch(viewIntent(INVITATION))
        waitForTag(MatchTags.SCREEN)

        controller.recreate()
        composeRule.waitForIdle()
        controller.recreate()
        composeRule.waitForIdle()

        // Opened again, the link would be refused as no newer than the match it opened.
        composeRule.onNodeWithText(ALERT_TITLE).assertDoesNotExist()
        composeRule.onNodeWithTag(MatchTags.SCREEN).assertIsDisplayed()
        // One match on the back stack, so one press of Back is home.
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()
        assertOnHome()
    }

    @Test
    fun `a link handed back by Recents is not opened again`() {
        launch(viewIntent(INVITATION).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))

        assertOnHome()
        composeRule.onNodeWithText(ALERT_TITLE).assertDoesNotExist()
    }

    @Test
    fun `a damaged link is explained over an empty home`() {
        launch(viewIntent(DAMAGED))

        assertAlert(DAMAGED_MESSAGE)
        composeRule.onNodeWithText("Resume match").assertDoesNotExist()
        dismissAlert()
        assertOnHome()
    }

    @Test
    fun `a damaged link is explained over home with a match to resume, which stays on offer`() {
        app.container.snapshots.save(MatchSnapshot(MatchMode.LocalVersus, GameSetup.newGame()))

        launch(viewIntent(DAMAGED))

        assertAlert(DAMAGED_MESSAGE)
        waitForText("Resume match")
        composeRule.onNodeWithText("Resume match").assertIsDisplayed()
        dismissAlert()
        composeRule.onNodeWithText("Resume match").assertIsDisplayed()
    }

    @Test
    fun `during a match a bad link leaves it alone, and a good one replaces it`() {
        launch()
        openLocalMatchAndMove()

        deliver(viewIntent(DAMAGED))

        assertAlert(DAMAGED_MESSAGE)
        dismissAlert()
        square(3, 0).assertContentDescriptionContains("Player One banana", substring = true)
        square(4, 0).assertContentDescriptionContains("Empty square", substring = true)

        deliver(viewIntent(INVITATION))

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runCatching { composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assertTextEquals("You to move") }
                .isSuccess
        }
        square(3, 0).assertContentDescriptionContains("Empty square", substring = true)
        // The local match was taken off the back stack, not left under the new one.
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()
        assertOnHome()
    }

    @Test
    fun `a bad link is explained over the rules, which stay open`() {
        launch()
        composeRule.onNodeWithText("How to play").performScrollTo().performClick()
        composeRule.waitForIdle()

        deliver(viewIntent(DAMAGED))

        assertAlert(DAMAGED_MESSAGE)
        dismissAlert()
        composeRule.onNodeWithText("The turn").assertIsDisplayed()
    }

    @Test
    fun `invite by link offers the invitation, and sending it opens the share sheet with the link`() {
        launch()
        composeRule.onNodeWithText("Invite by Link").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MatchTags.TURN_INDICATOR).assertTextEquals("Send the link to start")
        // A link match belongs to both phones, so it cannot be restarted from one.
        composeRule.onNodeWithText("Restart").assertDoesNotExist()
        composeRule
            .onNodeWithTag(LinkTags.SEND)
            .assertTextEquals("Send the invitation")
            .performClick()

        val chooser = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val (message, link) = assertNotNull(send.getStringExtra(Intent.EXTRA_TEXT)).split("\n")
        assertEquals("Your move in Banana Chess.", message)
        assertEquals(0, LinkPlay.read(link).moveNumber)
        composeRule.onNodeWithTag(MatchTags.ERROR_BANNER).assertDoesNotExist()
    }

    @Test
    fun `with nothing to share with, the link is copied and the banner says so`() {
        launch()
        composeRule.onNodeWithText("Invite by Link").performClick()
        composeRule.waitForIdle()
        // From here on, an intent nothing handles fails as it does on a device.
        shadowOf(app).checkActivities(true)
        try {
            composeRule.onNodeWithTag(LinkTags.SEND).performClick()
            composeRule.waitForIdle()
        } finally {
            // Only the share sheet is meant to fail; the activity still has to close cleanly.
            shadowOf(app).checkActivities(false)
        }

        composeRule
            .onNodeWithText("Couldn't open the share sheet. The link was copied instead.")
            .assertIsDisplayed()
        val clipboard = checkNotNull(app.getSystemService(ClipboardManager::class.java))
        val copied =
            assertNotNull(clipboard.primaryClip)
                .getItemAt(0)
                .text
                .toString()
        assertEquals(0, LinkPlay.read(copied).moveNumber)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val ALERT_TITLE = "Can't open that match"
        const val DAMAGED = "bananachess://m/1/AAAA"
        const val DAMAGED_MESSAGE = "That match link is damaged and can't be read."
        val INVITATION = LinkPlay.invite(MatchOptions(), "4F3C1E2A-0000-4000-8000-0000000000AA").link
    }
}
