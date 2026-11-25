package com.charlesbarton.bananachess.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

/** Everything the app remembers between launches. The defaults are a first launch. */
data class Settings(
    val cpuDifficulty: CpuDifficulty = CpuDifficulty.Medium,
    val cpuHumanPlaysFirst: Boolean = true,
    val wildcardRulesEnabled: Boolean = false,
    val boardSetup: BoardSetup = BoardSetup.Standard,
    val boardWrap: BoardWrap = BoardWrap.Flat,
    val blenderBattles: Boolean = false,
    val hasSeenTeachingOverlay: Boolean = false,
)

interface SettingsStore {
    /** The stored settings now, and again after every change. */
    val settings: Flow<Settings>

    /** Changes the stored settings. Best-effort: a write that fails is dropped, never thrown. */
    suspend fun update(transform: (Settings) -> Settings)
}

/** Settings in a DataStore Preferences file, under the same key names the iOS app uses. */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override val settings: Flow<Settings> =
        dataStore.data
            // Preferences are best-effort. A file that cannot be read means a first launch,
            // not an error the player has to deal with.
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        try {
            dataStore.edit { preferences -> preferences.write(transform(preferences.toSettings())) }
        } catch (_: IOException) {
            // Best-effort, as above: the choice still holds for this session, it is just not
            // remembered. There is nothing useful the player could do about a full disk here.
        }
    }

    companion object {
        private const val FILE_NAME = "settings"

        /**
         * The app's settings, read and written in [ioScope]. DataStore allows one instance per
         * file per process, so only the app container calls this, once.
         */
        fun create(
            context: Context,
            ioScope: CoroutineScope,
        ): DataStoreSettingsStore =
            DataStoreSettingsStore(createDataStore(context.preferencesDataStoreFile(FILE_NAME), ioScope))

        /** A Preferences DataStore on [file], which must end in `.preferences_pb`. */
        fun createDataStore(
            file: File,
            ioScope: CoroutineScope,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // An unreadable file is replaced by defaults, for the same best-effort reason.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = ioScope,
                produceFile = { file },
            )
    }
}

/** Settings that live only in memory, for tests and previews. */
class InMemorySettingsStore(
    initial: Settings = Settings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<Settings> = state

    val current: Settings get() = state.value

    override suspend fun update(transform: (Settings) -> Settings) {
        state.update(transform)
    }
}

private object Keys {
    val cpuDifficulty = stringPreferencesKey("lastCPUDifficulty")
    val cpuHumanPlaysFirst = booleanPreferencesKey("lastCPUHumanPlaysFirst")
    val wildcardRulesEnabled = booleanPreferencesKey("wildcardRulesEnabled")
    val boardSetup = stringPreferencesKey("boardSetup")
    val boardWrap = stringPreferencesKey("boardWrap")
    val blenderBattles = booleanPreferencesKey("blenderBattles")
    val hasSeenTeachingOverlay = booleanPreferencesKey("hasSeenTeachingOverlay")
}

// A missing key, or a raw value this build does not know, reads as the default.
private fun Preferences.toSettings(): Settings {
    val defaults = Settings()
    return Settings(
        cpuDifficulty =
            CpuDifficulty.entries.firstOrNull { it.rawValue == this[Keys.cpuDifficulty] } ?: defaults.cpuDifficulty,
        cpuHumanPlaysFirst = this[Keys.cpuHumanPlaysFirst] ?: defaults.cpuHumanPlaysFirst,
        wildcardRulesEnabled = this[Keys.wildcardRulesEnabled] ?: defaults.wildcardRulesEnabled,
        boardSetup = BoardSetup.entries.firstOrNull { it.rawValue == this[Keys.boardSetup] } ?: defaults.boardSetup,
        boardWrap = BoardWrap.entries.firstOrNull { it.rawValue == this[Keys.boardWrap] } ?: defaults.boardWrap,
        blenderBattles = this[Keys.blenderBattles] ?: defaults.blenderBattles,
        hasSeenTeachingOverlay = this[Keys.hasSeenTeachingOverlay] ?: defaults.hasSeenTeachingOverlay,
    )
}

private fun MutablePreferences.write(settings: Settings) {
    this[Keys.cpuDifficulty] = settings.cpuDifficulty.rawValue
    this[Keys.cpuHumanPlaysFirst] = settings.cpuHumanPlaysFirst
    this[Keys.wildcardRulesEnabled] = settings.wildcardRulesEnabled
    this[Keys.boardSetup] = settings.boardSetup.rawValue
    this[Keys.boardWrap] = settings.boardWrap.rawValue
    this[Keys.blenderBattles] = settings.blenderBattles
    this[Keys.hasSeenTeachingOverlay] = settings.hasSeenTeachingOverlay
}
