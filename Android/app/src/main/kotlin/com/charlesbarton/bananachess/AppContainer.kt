package com.charlesbarton.bananachess

import android.content.Context
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.match.CpuOpponent
import com.charlesbarton.bananachess.match.FileMatchSnapshotStore
import com.charlesbarton.bananachess.match.InMemoryMatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchSnapshotStore
import com.charlesbarton.bananachess.settings.DataStoreSettingsStore
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The dispatchers the app runs on, named so that nothing picks one implicitly. */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * The app-wide objects, made once per process by [BananaChessApplication] and handed to
 * screens through their constructors. Holds nothing tied to an activity or a view.
 *
 * Later additions go here as constructor parameters, made in [create].
 *
 * @param snapshots keeps the match in progress so Home can offer to resume it. Tests that do
 *   not give one get a store in memory, which a simulated process restart loses.
 * @param cpuOpponent makes the computer a match plays against. Its searches run on
 *   [AppDispatchers.default].
 * @param ioScope where app-wide files are read and written. It lasts as long as the container.
 */
class AppContainer(
    val settings: SettingsStore,
    val dispatchers: AppDispatchers = AppDispatchers(),
    val cpuOpponent: (CpuDifficulty) -> CpuOpponent = CpuOpponent::forDifficulty,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io),
    val snapshots: MatchSnapshotStore = InMemoryMatchSnapshotStore(),
) {
    /**
     * Ends the container's work and releases its files. A real process is killed rather than
     * closed, so this only runs where an application is torn down in place, as in tests.
     */
    fun close() = ioScope.cancel()

    companion object {
        /** The production container. Call once per process: DataStore allows one instance per file. */
        fun create(
            context: Context,
            dispatchers: AppDispatchers = AppDispatchers(),
        ): AppContainer {
            val ioScope = CoroutineScope(SupervisorJob() + dispatchers.io)
            val applicationContext = context.applicationContext
            return AppContainer(
                settings = DataStoreSettingsStore.create(applicationContext, ioScope),
                dispatchers = dispatchers,
                ioScope = ioScope,
                snapshots = FileMatchSnapshotStore(applicationContext.filesDir, ioScope),
            )
        }
    }
}
