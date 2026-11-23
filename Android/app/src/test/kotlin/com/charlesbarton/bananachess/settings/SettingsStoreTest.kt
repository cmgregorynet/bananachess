package com.charlesbarton.bananachess.settings

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val changed =
        Settings(
            cpuDifficulty = CpuDifficulty.Brutal,
            cpuHumanPlaysFirst = false,
            wildcardRulesEnabled = true,
            boardSetup = BoardSetup.Random,
            boardWrap = BoardWrap.All,
            blenderBattles = true,
            hasSeenTeachingOverlay = true,
        )

    private fun file(): File = File(folder.root, "settings.preferences_pb")

    // One DataStore may be open on a file at a time, so each "launch" gets its own scope and
    // closes it before the next one starts.
    private suspend fun <T> TestScope.launchOf(
        file: File,
        block: suspend (DataStoreSettingsStore) -> T,
    ): T {
        val job = Job()
        val ioScope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        try {
            return block(DataStoreSettingsStore(DataStoreSettingsStore.createDataStore(file, ioScope)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a first launch reads the defaults`() =
        runTest {
            val settings = launchOf(file()) { it.settings.first() }

            assertEquals(Settings(), settings)
            assertEquals(CpuDifficulty.Medium, settings.cpuDifficulty)
            assertEquals(true, settings.cpuHumanPlaysFirst)
            assertEquals(false, settings.wildcardRulesEnabled)
            assertEquals(BoardSetup.Standard, settings.boardSetup)
            assertEquals(BoardWrap.Flat, settings.boardWrap)
            assertEquals(false, settings.blenderBattles)
            assertEquals(false, settings.hasSeenTeachingOverlay)
        }

    @Test
    fun `every setting survives to the next launch`() =
        runTest {
            val file = file()
            launchOf(file) { it.update { changed } }

            assertEquals(changed, launchOf(file) { it.settings.first() })
        }

    @Test
    fun `changing one setting leaves the others as stored`() =
        runTest {
            val file = file()
            launchOf(file) { store ->
                store.update { changed }
                store.update { it.copy(boardWrap = BoardWrap.Sides) }
            }

            assertEquals(changed.copy(boardWrap = BoardWrap.Sides), launchOf(file) { it.settings.first() })
        }

    @Test
    fun `the settings flow reports each change`() =
        runTest {
            launchOf(file()) { store ->
                assertEquals(Settings(), store.settings.first())
                store.update { it.copy(hasSeenTeachingOverlay = true) }
                assertEquals(Settings(hasSeenTeachingOverlay = true), store.settings.first())
            }
        }

    @Test
    fun `an unreadable file reads as the defaults and can be written again`() =
        runTest {
            val file = file()
            file.writeBytes(byteArrayOf(0x13, 0x37, 0x7F, 0x00, 0x42, 0x11))

            launchOf(file) { store ->
                assertEquals(Settings(), store.settings.first())
                store.update { it.copy(blenderBattles = true) }
            }

            assertEquals(Settings(blenderBattles = true), launchOf(file) { it.settings.first() })
        }

    @Test
    fun `a file that cannot be written drops the change without throwing`() =
        runTest {
            // A directory where the file should be: every read and write fails with an IOException.
            val file = file().apply { mkdirs() }

            launchOf(file) { store ->
                assertEquals(Settings(), store.settings.first())
                store.update { changed }
                assertEquals(Settings(), store.settings.first())
            }
        }

    @Test
    fun `the in-memory store behaves like the real one`() =
        runTest {
            val store = InMemorySettingsStore()
            assertEquals(Settings(), store.settings.first())

            store.update { changed }
            store.update { it.copy(boardWrap = BoardWrap.Sides) }

            assertEquals(changed.copy(boardWrap = BoardWrap.Sides), store.settings.first())
            assertEquals(changed.copy(boardWrap = BoardWrap.Sides), store.current)
        }
}
