package com.charlesbarton.bananachess.link

import androidx.lifecycle.SavedStateHandle
import com.charlesbarton.bananachess.MatchOpening
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.online.LastMove
import com.charlesbarton.bananachess.engine.online.MatchLink
import com.charlesbarton.bananachess.engine.online.MatchPayload
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.home.HomeViewModel
import com.charlesbarton.bananachess.match.InMemoryMatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchSnapshot
import com.charlesbarton.bananachess.match.MatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Starting an invitation and opening a link from Home: the seats, the errors and the stale check. */
class LinkOpeningTest {
    private fun TestScope.home(snapshots: MatchSnapshotStore = MatchSnapshotStore.None): HomeViewModel =
        HomeViewModel(
            settings = InMemorySettingsStore(),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            snapshots = snapshots,
            matchIds = { MATCH_ID },
        ).also { testScheduler.advanceUntilIdle() }

    private fun TestScope.opened(
        home: HomeViewModel,
        url: String,
    ): HomeViewModel {
        home.open(url)
        testScheduler.advanceUntilIdle()
        return home
    }

    private fun payload(
        moveNumber: Int,
        matchId: String = MATCH_ID,
    ): MatchPayload {
        var state = GameSetup.newGame()
        repeat(moveNumber) { state = RulesEngine.apply(RulesEngine.legalActions(state).first(), state).state }
        return MatchPayload(matchId = matchId, moveNumber = moveNumber, state = state)
    }

    private fun linkMatchSaved(moveNumber: Int): InMemoryMatchSnapshotStore {
        val saved = payload(moveNumber)
        return InMemoryMatchSnapshotStore(MatchSnapshot(MatchMode.ByLink(MATCH_ID, PlayerId.One), saved.state))
    }

    // region Starting and opening

    @Test
    fun `an invitation seats the inviter second, with player one to move, on the board chosen`() =
        runTest {
            val home = home()
            home.setUsesWildcardRules(true)
            home.setBoardSetup(BoardSetup.Blendy)

            val source = home.invite()
            val payload = LinkPlay.read(source.link)

            assertEquals(PlayerId.Two, source.localPlayer)
            assertEquals(MATCH_ID, payload.matchId)
            assertEquals(0, payload.moveNumber)
            assertEquals(PlayerId.One, payload.state.activePlayer)
            assertEquals(RuleSet.Wildcard, payload.state.ruleSet)
            assertEquals(BoardSetup.Blendy, payload.state.boardSetup)
            assertNull(payload.lastMove)
        }

    @Test
    fun `match ids are new each time and written as iOS writes them`() =
        runTest {
            val home = HomeViewModel(InMemorySettingsStore(), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val first = LinkPlay.read(home.invite().link).matchId
            val second = LinkPlay.read(home.invite().link).matchId

            assert(first != second)
            assert(first.matches(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"))) { first }
        }

    @Test
    fun `opening a link seats this phone as whoever the match is waiting on`() =
        runTest {
            val link = MatchLink.url(payload(moveNumber = 1))
            val home = opened(home(), link)

            val source = assertIs<MatchSource.ByLink>(home.state.value.linkToOpen)
            assertEquals(link, source.link)
            assertEquals(PlayerId.Two, source.localPlayer)
            assertNull(home.state.value.linkError)

            home.linkOpened()
            assertNull(home.state.value.linkToOpen)
        }

    @Test
    fun `opening a golden link written by iOS works`() =
        runTest {
            val (step0, step1) = goldenLinks()
            val home = opened(home(), step1)

            val source = assertIs<MatchSource.ByLink>(home.state.value.linkToOpen)
            assertEquals(PlayerId.One, source.localPlayer)
            val start = checkNotNull(LinkPlay.start(source, saved = null))
            assertEquals(MatchMode.ByLink("golden-1001", PlayerId.One), start.mode)
            assertEquals(1, start.state.history.size)
            // Nothing on this phone held the position before the move, so it opens on the result.
            assertNull(start.animateFrom)

            // With the previous link's match saved, the move is played out from it.
            val previous = LinkPlay.read(step0).state
            val saved = MatchSnapshot(MatchMode.ByLink("golden-1001", PlayerId.Two), previous)
            val replayed = checkNotNull(LinkPlay.start(source, saved))
            assertEquals(previous, replayed.animateFrom)
            assertEquals(LinkPlay.read(step1).lastMove, replayed.lastMove)
        }

    // endregion

    // region Errors

    @Test
    fun `each kind of bad link says what is wrong with it`() =
        runTest {
            val newerSchema = MatchLink.url(payload(0).copy(schemaVersion = MatchPayload.CURRENT_SCHEMA_VERSION + 1))
            val olderSchema = MatchLink.url(payload(0).copy(schemaVersion = MatchPayload.CURRENT_SCHEMA_VERSION - 1))
            val newerRules = MatchLink.url(payload(0).copy(rulesVersion = RulesEngine.RULES_VERSION + 1))
            val olderRules = MatchLink.url(payload(0).copy(rulesVersion = RulesEngine.RULES_VERSION - 1))
            val cases =
                mapOf(
                    "https://example.com/hello" to LinkError.NotAMatchLink,
                    "not a link at all" to LinkError.NotAMatchLink,
                    "bananachess://m/1/%%%" to LinkError.Damaged,
                    "bananachess://m/1/AAAA" to LinkError.Damaged,
                    "bananachess://m/2/AAAA" to LinkError.FromNewerVersion,
                    newerSchema to LinkError.FromNewerVersion,
                    newerRules to LinkError.FromNewerVersion,
                    olderSchema to LinkError.FromOlderVersion,
                    olderRules to LinkError.FromOlderVersion,
                )

            for ((url, expected) in cases) {
                val home = opened(home(), url)
                assertEquals(expected, home.state.value.linkError, url)
                assertNull(home.state.value.linkToOpen, url)
            }
        }

    @Test
    fun `every link error has its own message, written for the player`() {
        val errors =
            listOf(
                LinkError.NotAMatchLink,
                LinkError.Damaged,
                LinkError.FromNewerVersion,
                LinkError.FromOlderVersion,
                LinkError.Stale,
            )
        assertEquals(
            listOf(
                "That link isn't a Banana Chess match.",
                "That match link is damaged and can't be read.",
                "That match was sent from a newer version of Banana Chess.",
                "That match was sent from an older version of Banana Chess and can't be opened.",
                "That link is older than the match on this phone.",
            ),
            errors.map { it.message },
        )
    }

    @Test
    fun `a link error stays until it is dismissed`() =
        runTest {
            val home = opened(home(), "https://example.com")
            assertEquals(LinkError.NotAMatchLink, home.state.value.linkError)

            home.dismissLinkError()

            assertNull(home.state.value.linkError)
        }

    // endregion

    // region The match already on this phone

    @Test
    fun `an older link is refused, the same link opens the match again, and a newer one is accepted`() =
        runTest {
            val snapshots = linkMatchSaved(moveNumber = 2)

            val older = opened(home(snapshots), MatchLink.url(payload(moveNumber = 1)))
            assertEquals(LinkError.Stale, older.state.value.linkError)
            assertEquals(
                "That link is older than the match on this phone.",
                older.state.value.linkError
                    ?.message,
            )

            // Tapping the opponent's message again to carry on is not a stale link.
            val same = opened(home(snapshots), MatchLink.url(payload(moveNumber = 2)))
            assertNull(same.state.value.linkError)
            val again = assertIs<MatchSource.ByLink>(same.state.value.linkToOpen)
            assertEquals(2, checkNotNull(LinkPlay.start(again, snapshots.snapshot)).state.history.size)

            val newer = opened(home(snapshots), MatchLink.url(payload(moveNumber = 3)))
            assertNull(newer.state.value.linkError)
            assertIs<MatchSource.ByLink>(newer.state.value.linkToOpen)
        }

    @Test
    fun `the final position opens seated as the side that received it`() =
        runTest {
            val playing = payload(moveNumber = 3)
            val sender = playing.state.activePlayer
            // A finished match never hands the turn over, so the sender is still the active player.
            val finished = playing.state.updated(status = GameStatus.Ended(Outcome.Win(sender)))
            val action = RulesEngine.legalActions(playing.state).first()

            val withLastMove =
                opened(
                    home(),
                    MatchLink.url(
                        playing.copy(
                            state = finished,
                            lastMove = LastMove(action, playedBy = sender, events = emptyList()),
                        ),
                    ),
                )
            assertEquals(sender.opponent, assertIs<MatchSource.ByLink>(withLastMove.state.value.linkToOpen).localPlayer)

            // iOS sends no last move.
            val withoutLastMove = opened(home(), MatchLink.url(playing.copy(state = finished)))
            assertEquals(
                sender.opponent,
                assertIs<MatchSource.ByLink>(withoutLastMove.state.value.linkToOpen).localPlayer,
            )
        }

    @Test
    fun `a link for a different match replaces the one on this phone`() =
        runTest {
            val snapshots = linkMatchSaved(moveNumber = 4)

            val home = opened(home(snapshots), MatchLink.url(payload(moveNumber = 1, matchId = "ANOTHER")))

            assertNull(home.state.value.linkError)
            assertIs<MatchSource.ByLink>(home.state.value.linkToOpen)
        }

    @Test
    fun `a link match route opened again prefers the saved match once it has moved on`() =
        runTest {
            val link = MatchLink.url(payload(moveNumber = 1))
            val source = MatchSource.ByLink(link, PlayerId.Two)

            val onward = opening(source, linkMatchSaved(moveNumber = 2))
            assertEquals(2, onward.state.history.size)

            val behind = opening(source, linkMatchSaved(moveNumber = 0))
            assertEquals(1, behind.state.history.size)
            assertEquals(MatchMode.ByLink(MATCH_ID, PlayerId.Two), behind.mode)

            val unrelated =
                InMemoryMatchSnapshotStore(MatchSnapshot(MatchMode.ByLink("ANOTHER", PlayerId.One), payload(3).state))
            assertEquals(1, opening(source, unrelated).state.history.size)
        }

    @Test
    fun `a link match route whose link cannot be read opens nothing`() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val opening =
                MatchOpening(
                    MatchSource.ByLink("nonsense", PlayerId.One),
                    SavedStateHandle(),
                    MatchSnapshotStore.None,
                    scope,
                )
            testScheduler.advanceUntilIdle()

            assertEquals(MatchOpening.State.NoMatch, opening.state.value)
        }

    private fun TestScope.opening(
        source: MatchSource.ByLink,
        snapshots: MatchSnapshotStore,
    ) = MatchOpening(source, SavedStateHandle(), snapshots, CoroutineScope(StandardTestDispatcher(testScheduler)))
        .also { testScheduler.advanceUntilIdle() }
        .let { assertIs<MatchOpening.State.Ready>(it.state.value).start }

    // endregion

    /** Two consecutive links, steps 0 and 1 of one match, as the Swift engine wrote them. */
    private fun goldenLinks(): Pair<String, String> {
        val text =
            checkNotNull(
                javaClass.getResourceAsStream("/golden-links.json"),
            ).use { it.readBytes().decodeToString() }
        val samples =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("samples")
                .jsonArray
        val links =
            samples.map {
                it.jsonObject
                    .getValue("link")
                    .jsonPrimitive.content
            }
        return links[0] to links[1]
    }

    private companion object {
        const val MATCH_ID = "4F3C1E2A-0000-4000-8000-000000000001"
    }
}
