package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.online.GameStateCodec
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchSnapshotStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory: File get() = File(folder.root, "files")
    private val file: File get() = File(directory, FileMatchSnapshotStore.FILE_NAME)

    /** A wildcard match on a random board, three moves in, so every part of the state has something in it. */
    private val threeMovesIn: GameState =
        (1..3).fold(
            GameSetup.newGame(
                ruleSet = RuleSet.Wildcard,
                boardSetup = BoardSetup.Random,
                boardWrap = BoardWrap.Sides,
                blenderBattles = true,
                seed = 42uL,
            ),
        ) { state, _ -> RulesEngine.apply(RulesEngine.legalActions(state).first(), state).state }

    // Each store on its own: a second one reading the same folder is the app after a restart.
    // Not in backgroundScope, whose work advanceUntilIdle leaves alone.
    private fun TestScope.store(directory: File = this@MatchSnapshotStoreTest.directory) =
        FileMatchSnapshotStore(directory, CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `a local match comes back from the file as it was saved`() =
        runTest {
            store().save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))
            testScheduler.advanceUntilIdle()

            assertEquals(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn), store().load())
        }

    @Test
    fun `a computer match comes back with its difficulty and seat`() =
        runTest {
            val mode = MatchMode.Computer(CpuDifficulty.Brutal, humanPlays = PlayerId.Two)
            store().save(MatchSnapshot(mode, threeMovesIn))
            testScheduler.advanceUntilIdle()

            val loaded = store().load()

            assertEquals(MatchSnapshot(mode, threeMovesIn), loaded)
            assertEquals(threeMovesIn.turnNumber, loaded?.state?.turnNumber)
        }

    @Test
    fun `a link match keeps its id and seat`() =
        runTest {
            val mode = MatchMode.ByLink(matchId = "abc-123", localPlayer = PlayerId.Two)
            store().save(MatchSnapshot(mode, threeMovesIn))
            testScheduler.advanceUntilIdle()

            assertEquals(mode, store().load()?.mode)
        }

    @Test
    fun `the file holds the version, the mode and the engine's own encoding of the state`() =
        runTest {
            store().save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))
            testScheduler.advanceUntilIdle()

            val text = file.readText()
            assertTrue("\"rulesVersion\":${RulesEngine.RULES_VERSION}" in text, text)
            assertTrue("\"mode\":{\"type\":\"local\"}" in text, text)
            assertTrue(GameStateCodec.encode(threeMovesIn) in text)
            assertFalse(
                File(directory, "${FileMatchSnapshotStore.FILE_NAME}.tmp").exists(),
                "the temporary file stays behind",
            )
        }

    @Test
    fun `the last save wins, and a load waits for the saves before it`() =
        runTest {
            val store = store()
            val opening = GameSetup.newGame()
            store.save(MatchSnapshot(MatchMode.LocalVersus, opening))
            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))

            assertEquals(threeMovesIn, store.load()?.state)

            store.clear()
            assertNull(store.load())
            assertFalse(file.exists())
        }

    @Test
    fun `nothing saved is nothing to resume`() =
        runTest {
            assertNull(store().load())
        }

    @Test
    fun `a match saved under other rules is dropped and its file removed`() =
        runTest {
            store().save(
                MatchSnapshot(MatchMode.LocalVersus, threeMovesIn, rulesVersion = RulesEngine.RULES_VERSION - 1),
            )
            testScheduler.advanceUntilIdle()
            assertTrue(file.exists())

            assertNull(store().load())
            assertFalse(file.exists())
        }

    @Test
    fun `other rules are refused even when the rest of the file no longer reads`() =
        runTest {
            directory.mkdirs()
            file.writeText("""{"rulesVersion":1,"mode":{"type":"retired"},"state":{}}""")

            assertNull(store().load())
            assertFalse(file.exists())
        }

    @Test
    fun `a corrupt file is dropped and removed`() =
        runTest {
            val corruptions =
                listOf(
                    "",
                    "not json",
                    "[]",
                    """{"rulesVersion":"eighteen"}""",
                    """{"rulesVersion":${RulesEngine.RULES_VERSION},"mode":{"type":"local"}}""",
                    """{"rulesVersion":${RulesEngine.RULES_VERSION},"mode":{"type":"chess"},"state":{}}""",
                    """{"rulesVersion":${RulesEngine.RULES_VERSION},"mode":{"type":"local"},"state":{"pieces":[]}}""",
                )
            directory.mkdirs()
            for (corruption in corruptions) {
                file.writeText(corruption)

                assertNull(store().load(), corruption)
                assertFalse(file.exists(), corruption)
            }
        }

    @Test
    fun `a finished match is not offered and its file removed`() =
        runTest {
            val finished =
                GameState(
                    pieces = threeMovesIn.pieces,
                    status = GameStatus.Ended(Outcome.Win(PlayerId.One, WinReason.Score)),
                )
            store().save(MatchSnapshot(MatchMode.LocalVersus, finished))
            testScheduler.advanceUntilIdle()

            assertNull(store().load())
            assertFalse(file.exists())
        }

    @Test
    fun `a directory that cannot be written to fails quietly`() =
        runTest {
            // A file where the directory should be: nothing can be created inside it.
            folder.root.mkdirs()
            directory.writeText("in the way")
            val store = store()

            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))
            store.clear()
            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))

            assertNull(store.load())
            assertEquals("in the way", directory.readText())
        }

    @Test
    fun `a store whose scope has ended finds nothing rather than waiting forever`() =
        runTest {
            val scope = CoroutineScope(Job())
            val store = FileMatchSnapshotStore(directory, scope)
            scope.cancel()

            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))
            assertNull(store.load())
        }

    @Test
    fun `the in-memory store behaves like the file`() =
        runTest {
            val store = InMemoryMatchSnapshotStore()
            assertNull(store.load())

            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn))
            assertEquals(threeMovesIn, store.load()?.state)

            store.save(MatchSnapshot(MatchMode.LocalVersus, threeMovesIn, rulesVersion = 0))
            assertNull(store.load())
            assertNull(store.snapshot)
        }
}
