package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.online.MatchOptions
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.link.LinkPlay
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two phones playing by link, each with its own saved match, exchanging nothing but the URL
 * strings. Every link that arrives is opened the way the app opens one: checked against the
 * phone's saved match, then turned into a fresh match view model.
 */
class LinkPlayTest {
    private class Phone {
        val snapshots = InMemoryMatchSnapshotStore()
        lateinit var match: MatchViewModel
    }

    // Nothing has run yet when this returns, so a test sees the board as the match opens.
    private fun TestScope.open(
        phone: Phone,
        start: MatchStart,
        haptics: MatchHaptics = MatchHaptics.None,
    ): MatchViewModel =
        MatchViewModel(
            mode = start.mode,
            initialState = start.state,
            settings = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true)),
            blendEffects = BlendEffectPolicy.Never,
            haptics = haptics,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            cpuDispatcher = StandardTestDispatcher(testScheduler),
            cpuOpponent = passingCpu,
            snapshots = phone.snapshots,
            lastMove = start.lastMove,
            animateFrom = start.animateFrom,
        ).also { phone.match = it }

    private suspend fun TestScope.invite(
        phone: Phone,
        options: MatchOptions = MatchOptions(),
    ): MatchViewModel {
        val start = checkNotNull(LinkPlay.start(LinkPlay.invite(options, MATCH_ID), phone.snapshots.load()))
        return open(phone, start)
    }

    private suspend fun TestScope.receive(
        phone: Phone,
        link: String,
        haptics: MatchHaptics = MatchHaptics.None,
    ): MatchViewModel {
        val source = LinkPlay.open(link, phone.snapshots.load())
        return open(phone, checkNotNull(LinkPlay.start(source, phone.snapshots.load())), haptics)
    }

    /** Plays the first legal action, lets it play out, and returns the link it leaves to send. */
    private fun TestScope.move(match: MatchViewModel): String {
        match.perform(RulesEngine.legalActions(match.ui.authoritativeState).first())
        settle()
        return checkNotNull(match.ui.linkToSend) { "a move by link always leaves something to send" }
    }

    @Test
    fun `a new invitation has a link to send straight away`() =
        runTest {
            val inviter = invite(Phone())

            assertEquals(MatchMode.ByLink(MATCH_ID, PlayerId.Two), inviter.ui.mode)
            assertTrue(checkNotNull(inviter.ui.linkToSend).startsWith("bananachess://m/1/"))
            assertEquals("Send the invitation", inviter.ui.linkHandoffTitle)
            assertEquals("Send the link to start", inviter.ui.turnIndicatorText)
            assertEquals("Your move in Banana Chess.", inviter.ui.linkShareText)
            // The opponent moves first, the inviter watches from player two's side.
            assertTrue(inviter.ui.isInputLocked)
            assertTrue(inviter.ui.isRotated)
            assertFalse(inviter.ui.canPlayAgain)
            assertFalse(inviter.ui.canUndo)
        }

    @Test
    fun `an invitation is dealt with the options it was made with`() =
        runTest {
            val options = MatchOptions(blenderBattles = true)
            val inviter = invite(Phone(), options)

            assertTrue(inviter.ui.authoritativeState.blenderBattles)
        }

    @Test
    fun `opening the invitation seats the opener as player one on the same board`() =
        runTest {
            val inviter = invite(Phone())
            val opener = receive(Phone(), checkNotNull(inviter.ui.linkToSend))

            assertEquals(MatchMode.ByLink(MATCH_ID, PlayerId.One), opener.ui.mode)
            assertEquals(inviter.ui.authoritativeState, opener.ui.authoritativeState)
            assertFalse(opener.ui.isRotated)
            assertFalse(opener.ui.isInputLocked)
            assertNull(opener.ui.linkToSend)
            assertEquals("You to move", opener.ui.turnIndicatorText)
        }

    @Test
    fun `a four-move exchange keeps both phones in agreement`() =
        runTest {
            val phones = listOf(Phone(), Phone())
            var link = checkNotNull(invite(phones[0]).ui.linkToSend)
            receive(phones[1], link)

            repeat(4) { turn ->
                val mover = phones[(turn + 1) % 2]
                val receiver = phones[turn % 2]
                link = move(mover.match)

                assertEquals("Send your move", mover.match.ui.linkHandoffTitle)
                assertEquals("Send your move", mover.match.ui.turnIndicatorText)
                assertTrue(mover.match.ui.isInputLocked)

                // The receiver has the position before this move saved, so it sees it played.
                val before = checkNotNull(receiver.snapshots.snapshot).state
                receive(receiver, link)
                assertEquals(before, receiver.match.ui.displayState)
                settle()

                assertEquals(mover.match.ui.authoritativeState, receiver.match.ui.authoritativeState)
                assertEquals(mover.match.ui.authoritativeState, receiver.match.ui.displayState)
                assertEquals(turn + 1, receiver.match.ui.authoritativeState.history.size)
                assertEquals("You to move", receiver.match.ui.turnIndicatorText)
                assertNull(receiver.match.ui.linkToSend)
            }
        }

    @Test
    fun `two phones play a whole match by link`() =
        runTest {
            val phones = listOf(Phone(), Phone())
            var link = checkNotNull(invite(phones[0]).ui.linkToSend)
            receive(phones[1], link)

            var turn = 0
            while (!phones[(turn + 1) % 2]
                    .match.ui.authoritativeState.status.isOver
            ) {
                check(turn < MAX_TURNS) { "the match never ended" }
                link = move(phones[(turn + 1) % 2].match)
                receive(phones[turn % 2], link)
                settle()
                turn += 1
            }

            val finisher = phones[turn % 2].match
            val watcher = phones[(turn + 1) % 2].match
            assertEquals(finisher.ui.authoritativeState, watcher.ui.authoritativeState)
            assertTrue(watcher.ui.authoritativeState.status.isOver)
            assertEquals("Send the final position", finisher.ui.linkHandoffTitle)
            assertEquals("Here's how our Banana Chess match finished.", finisher.ui.linkShareText)
            assertEquals("Match over", watcher.ui.turnIndicatorText)
            assertFalse(watcher.ui.canPlayAgain)
            // A finished match has nothing to resume on either phone.
            assertNull(phones[0].snapshots.load())
            assertNull(phones[1].snapshots.load())
        }

    @Test
    fun `an incoming move is played out from the position before it`() =
        runTest {
            val inviterPhone = Phone()
            val invitation = invite(inviterPhone)
            val before = invitation.ui.authoritativeState
            val link = move(receive(Phone(), checkNotNull(invitation.ui.linkToSend)))
            val sent = LinkPlay.read(link)

            val haptics = RecordingHaptics()
            val shown = mutableListOf<GameState>()
            haptics.onPlay = { shown += inviterPhone.match.ui.displayState }
            val match = receive(inviterPhone, link, haptics)

            assertEquals(before, match.ui.displayState)
            assertTrue(match.ui.isResolving)
            assertTrue(match.ui.isInputLocked)
            settle()

            val events: List<GameEvent> = checkNotNull(sent.lastMove).events
            assertEquals(events, haptics.events)
            assertEquals(events.size, shown.size)
            assertFalse(match.ui.isResolving)
            assertEquals(sent.state, match.ui.displayState)
            assertEquals("You to move", match.ui.turnIndicatorText)
        }

    @Test
    fun `the link for a move carries the match, its move number and the move`() =
        runTest {
            val invitation = invite(Phone())
            val link = move(receive(Phone(), checkNotNull(invitation.ui.linkToSend)))

            val payload = LinkPlay.read(link)
            assertEquals(MATCH_ID, payload.matchId)
            assertEquals(1, payload.moveNumber)
            assertEquals(PlayerId.One, checkNotNull(payload.lastMove).playedBy)
            assertEquals(payload.state.history.last(), payload.lastMove?.action)
        }

    @Test
    fun `a link match is saved between turns and still has its link after a resume`() =
        runTest {
            val phone = Phone()
            val invitation = invite(Phone())
            val link = move(receive(phone, checkNotNull(invitation.ui.linkToSend)))

            val saved = assertNotNull(phone.snapshots.load())
            assertEquals(MatchMode.ByLink(MATCH_ID, PlayerId.One), saved.mode)
            assertEquals(1, saved.state.history.size)

            val resumed = open(Phone(), MatchStart(saved.mode, saved.state))
            val resent = LinkPlay.read(checkNotNull(resumed.ui.linkToSend))
            assertEquals(LinkPlay.read(link).state, resent.state)
            assertEquals(1, resent.moveNumber)
        }

    @Test
    fun `an incoming link is saved before it has played out`() =
        runTest {
            val phone = Phone()
            val invitation = invite(phone)
            val link = move(receive(Phone(), checkNotNull(invitation.ui.linkToSend)))

            receive(phone, link)

            assertTrue(phone.match.ui.isResolving)
            assertEquals(LinkPlay.read(link).state, phone.snapshots.snapshot?.state)
        }

    private companion object {
        const val MATCH_ID = "4F3C1E2A-0000-4000-8000-000000000001"
        const val MAX_TURNS = 2_000
    }
}
