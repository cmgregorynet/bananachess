package com.charlesbarton.bananachess.home

import androidx.lifecycle.ViewModel
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchOptions
import com.charlesbarton.bananachess.match.MatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.random.nextULong

/**
 * What the start screen shows: the six choices that are remembered between launches, and the
 * match there is to resume, if any.
 */
data class HomeUiState(
    val difficulty: CpuDifficulty = CpuDifficulty.Medium,
    val humanPlaysFirst: Boolean = true,
    val usesWildcardRules: Boolean = false,
    val boardSetup: BoardSetup = BoardSetup.Standard,
    val boardWrap: BoardWrap = BoardWrap.Flat,
    val blenderBattles: Boolean = false,
    val resume: ResumeOffer? = null,
)

/** The saved match Home offers to carry on with. [detail] says who it is against. */
data class ResumeOffer(
    val detail: String,
) {
    companion object {
        fun of(mode: MatchMode): ResumeOffer =
            ResumeOffer(
                when (mode) {
                    MatchMode.LocalVersus -> "Local two player"
                    is MatchMode.Computer -> "${mode.difficulty.displayName} CPU"
                    is MatchMode.ByLink -> "By link"
                },
            )
    }
}

/**
 * @param seeds picks the seed a new match is dealt from. It is carried with the match, so a
 *   Random board can be dealt again identically after the process is restarted.
 * @param snapshots where the match to resume is looked for, each time Home is shown.
 */
class HomeViewModel(
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val seeds: () -> ULong = { Random.nextULong() },
    private val snapshots: MatchSnapshotStore = MatchSnapshotStore.None,
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    // A choice made before the stored settings arrive is the newer of the two, so it is kept.
    private var hasEdited = false

    private val loading =
        scope.launch {
            val stored = settings.settings.first()
            if (!hasEdited) mutableState.update { stored.toUiState().copy(resume = it.resume) }
        }

    /**
     * Looks again for a match to resume. Called whenever Home comes into view, because the
     * match that was just left may have been saved, finished or replaced since Home last looked.
     */
    fun refreshResume() {
        scope.launch {
            val offer = snapshots.load()?.let { ResumeOffer.of(it.mode) }
            mutableState.update { it.copy(resume = offer) }
        }
    }

    val options: MatchOptions
        get() =
            with(state.value) {
                MatchOptions(
                    ruleSet = if (usesWildcardRules) RuleSet.Wildcard else RuleSet.Standard,
                    boardSetup = boardSetup,
                    boardWrap = boardWrap,
                    blenderBattles = blenderBattles,
                )
            }

    fun setDifficulty(difficulty: CpuDifficulty) =
        change({ it.copy(difficulty = difficulty) }) { it.copy(cpuDifficulty = difficulty) }

    fun setHumanPlaysFirst(humanPlaysFirst: Boolean) =
        change({ it.copy(humanPlaysFirst = humanPlaysFirst) }) { it.copy(cpuHumanPlaysFirst = humanPlaysFirst) }

    fun setUsesWildcardRules(usesWildcardRules: Boolean) =
        change({ it.copy(usesWildcardRules = usesWildcardRules) }) { it.copy(wildcardRulesEnabled = usesWildcardRules) }

    fun setBoardSetup(boardSetup: BoardSetup) =
        change({ it.copy(boardSetup = boardSetup) }) { it.copy(boardSetup = boardSetup) }

    fun setBoardWrap(boardWrap: BoardWrap) =
        change({ it.copy(boardWrap = boardWrap) }) { it.copy(boardWrap = boardWrap) }

    fun setBlenderBattles(blenderBattles: Boolean) =
        change({ it.copy(blenderBattles = blenderBattles) }) { it.copy(blenderBattles = blenderBattles) }

    /** A fresh pass-and-play match on the board the options describe. */
    fun startLocal(): MatchSource.New = MatchSource.New(MatchSource.Kind.Local, options, seeds())

    /** A fresh match against the CPU. Player one always moves first, so the human is player two when the CPU opens. */
    fun startComputer(): MatchSource.New {
        val current = state.value
        val humanPlays = if (current.humanPlaysFirst) PlayerId.One else PlayerId.Two
        return MatchSource.New(MatchSource.Kind.Computer(current.difficulty, humanPlays), options, seeds())
    }

    // The screen changes at once; the store catches up, one field at a time so that a write
    // never puts back a stale copy of some other setting.
    private fun change(
        onScreen: (HomeUiState) -> HomeUiState,
        stored: (Settings) -> Settings,
    ) {
        hasEdited = true
        mutableState.update(onScreen)
        scope.launch {
            loading.join()
            settings.update(stored)
        }
    }
}

private fun Settings.toUiState(): HomeUiState =
    HomeUiState(
        difficulty = cpuDifficulty,
        humanPlaysFirst = cpuHumanPlaysFirst,
        usesWildcardRules = wildcardRulesEnabled,
        boardSetup = boardSetup,
        boardWrap = boardWrap,
        blenderBattles = blenderBattles,
    )
