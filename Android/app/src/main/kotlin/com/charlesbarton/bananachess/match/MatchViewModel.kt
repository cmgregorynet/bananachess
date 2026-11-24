package com.charlesbarton.bananachess.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.cpu.CpuPlayer
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.wildcardStanding
import com.charlesbarton.bananachess.engine.online.LastMove
import com.charlesbarton.bananachess.engine.online.MatchAuthorityError
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import com.charlesbarton.bananachess.engine.rules.applying
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.link.LinkPlay
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One match: taps become engine actions, and the engine's events are played back onto the
 * board one beat at a time.
 *
 * Every function here is called on [scope]'s single thread, which is also where the timeline
 * runs. Nothing is synchronised because nothing is shared.
 *
 * @param settings where the first-match introduction is recorded as seen. Read once, when the
 *   match opens.
 * @param haptics told about every selection and event for as long as the view model lives. A
 *   haptics that holds a view goes through [attachHaptics] instead, because the view model
 *   outlives the screen.
 * @param reduceMotion asked before every beat, so a change in the system setting takes
 *   effect on the next event.
 * @param cpuDispatcher where the computer's search runs. Never [scope]'s thread: a search can
 *   take seconds.
 * @param cpuOpponent makes the computer player for a [MatchMode.Computer] match, once.
 * @param snapshots keeps this match for resuming: replaced when the match opens and after every
 *   move, restart and undo, and cleared once it is over.
 * @param lastMove in link play, the move that produced [initialState] as its link carried it.
 * @param animateFrom in link play, the position just before [lastMove]: the match opens there and
 *   plays the move out, so the opponent's move is seen rather than only its result.
 */
class MatchViewModel(
    mode: MatchMode,
    initialState: GameState,
    private val settings: SettingsStore,
    private val blendEffects: BlendEffectPolicy = BlendEffectPolicy.Standard,
    private val haptics: MatchHaptics = MatchHaptics.None,
    reduceMotion: () -> Boolean = { false },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    cpuOpponent: (CpuDifficulty) -> CpuOpponent = CpuOpponent::forDifficulty,
    private val snapshots: MatchSnapshotStore = MatchSnapshotStore.None,
    lastMove: LastMove? = null,
    animateFrom: GameState? = null,
) : ViewModel(scope) {
    // The move the next outgoing link carries, so the opponent sees it played out.
    private var lastMove: LastMove? = lastMove

    // Played out as the match opens, from [animateFrom]. Without that position there is nothing to play them on.
    private val incomingEvents: List<GameEvent> = if (animateFrom != null) lastMove?.events.orEmpty() else emptyList()

    private val mutableState =
        MutableStateFlow(
            MatchUiState(
                mode = mode,
                // An incoming move opens on the position before it, never flashing the one after.
                displayState = animateFrom?.takeIf { incomingEvents.isNotEmpty() } ?: initialState,
                authoritativeState = initialState,
                linkToSend = LinkPlay.linkToSend(mode, initialState, lastMove),
            ),
        )
    val state: StateFlow<MatchUiState> = mutableState.asStateFlow()

    private val timeline = EventTimeline(scope, reduceMotion, show = ::show, finished = ::finishTimeline)
    private val undoStack = ArrayDeque<GameState>()

    // Advanced once per effect so two blends in one turn never lay out identically.
    private var effectSeed: ULong = 0x9E3779B97F4A7C15uL
    private var lastEffectId = 0L

    // The screen's own haptics while it is showing. Never kept past that: it holds a view.
    private var attachedHaptics: MatchHaptics? = null

    internal val hasAttachedHaptics: Boolean get() = attachedHaptics != null

    private val cpu: CpuOpponent? = (mode as? MatchMode.Computer)?.let { cpuOpponent(it.difficulty) }

    // In viewModelScope, so clearing the view model cancels it, and with it the search.
    private var cpuJob: Job? = null

    init {
        // Hidden until the settings have been read, so a returning player never sees it flash.
        viewModelScope.launch {
            val hasSeen = settings.settings.first().hasSeenTeachingOverlay
            if (!hasSeen) change { copy(isTeachingOverlayShowing = true) }
        }
        // Any match that opens is now the one to resume, replacing whatever was saved before.
        persist()
        // A link that carries the opponent's move plays it out. It is already saved: persist
        // stored the position after it, which is where this match now is.
        if (incomingEvents.isNotEmpty()) animate(incomingEvents)
        // When the human plays second, the computer opens. A resumed match whose turn
        // belongs to the computer carries on the same way.
        startCpuTurnIfNeeded()
    }

    private inline fun change(transform: MatchUiState.() -> MatchUiState) {
        mutableState.value = mutableState.value.transform()
    }

    // Lifecycle

    /**
     * Holds the timeline while nobody can see it. Left running, a move would finish off
     * screen, so the player would return to a board that had changed without them seeing how.
     *
     * A computer that is thinking carries on: its reply is applied when it comes and waits in
     * the timeline, so it plays out once the player is back.
     */
    fun onSceneInactive() = timeline.pause()

    fun onSceneActive() = timeline.resume()

    /**
     * Hands the phone's haptics to the match while the screen is showing. Call [detachHaptics]
     * when it leaves: [haptics] usually holds a view, and the view model outlives the activity.
     */
    fun attachHaptics(haptics: MatchHaptics) {
        attachedHaptics = haptics
    }

    /** Lets go of [haptics], if it is still the one attached. */
    fun detachHaptics(haptics: MatchHaptics) {
        if (attachedHaptics === haptics) attachedHaptics = null
    }

    // Interaction

    fun tap(position: Position) {
        val current = state.value
        // A self-blend waiting for its answer is a question; the board does not take another move over it.
        if (current.isInputLocked || current.pendingSelfBlend != null) return

        // A legal destination wins over re-selection. A blender may land on its owner's own
        // fruit, so those squares are both "one of my pieces" and "a square I can move to",
        // and treating them as selection would make self-blending unreachable. To pick that
        // fruit up instead, deselect first.
        val selected = current.selectedPieceId
        val origin = selected?.let { current.displayState.piece(it)?.position }
        if (selected != null && origin != null && position in current.legalDestinations) {
            commitOrConfirm(Move(selected, origin, position))
            return
        }

        // An empty spawn where a blender can be bought back.
        val lost = current.restorableBlender(position)
        if (lost != null) {
            clearSelection()
            perform(RestoreBlender(lost, position))
            return
        }

        val piece = current.displayState.pieceAt(position) ?: return
        if (piece.owner != current.displayState.activePlayer) return
        if (selected == piece.id) clearSelection() else select(piece.id)
    }

    /**
     * Blending your own fruit costs points and cannot be taken back, so it asks first. Every
     * other move commits on the tap.
     */
    private fun commitOrConfirm(move: Move) {
        val display = state.value.displayState
        val mover = display.piece(move.pieceId)
        val target = display.pieceAt(move.destination)
        if (mover == null || target == null || !target.isFruit || target.owner != mover.owner) {
            clearSelection()
            perform(move)
            return
        }
        change {
            copy(pendingSelfBlend = PendingSelfBlend(move, target.kind, blenderamaStanding(display, mover.owner)))
        }
    }

    private fun blenderamaStanding(
        display: GameState,
        player: PlayerId,
    ): BlenderamaStanding {
        if (!display.ruleSet.usesWildcards) return BlenderamaStanding.NotPlaying
        val standing = display.wildcardStanding(player)
        if (standing.isBlenderamaBlocked) return BlenderamaStanding.AlreadyOff
        val soFar = standing.ownFruitBlended
        if (soFar + 1 >= standing.blenderamaTarget) return BlenderamaStanding.CompletesIt
        return BlenderamaStanding.Counts(soFar, standing.blenderamaTarget)
    }

    fun confirmSelfBlend() {
        val pending = state.value.pendingSelfBlend ?: return
        change { copy(pendingSelfBlend = null) }
        clearSelection()
        perform(pending.move)
    }

    fun cancelSelfBlend() = change { copy(pendingSelfBlend = null) }

    private fun select(pieceId: PieceId) {
        val destinations = RulesEngine.legalMoves(pieceId, state.value.authoritativeState).map { it.destination }
        change { copy(selectedPieceId = pieceId, legalDestinations = destinations.toSet()) }
        haptics.selection()
        attachedHaptics?.selection()
    }

    fun clearSelection() = change { copy(selectedPieceId = null, legalDestinations = emptySet()) }

    fun dismissError() = change { copy(errorMessage = null) }

    /** The screen could not open the share sheet for [MatchUiState.linkToSend], so it copied the link instead. */
    fun shareSheetUnavailable() = change { copy(errorMessage = SHARE_SHEET_UNAVAILABLE) }

    /**
     * Closes the first-match introduction, by "Play" or by the back button, and records that the
     * player has seen it. It closes on the call, not when the write lands: the write is
     * best-effort and may never land.
     */
    fun dismissTeachingOverlay() {
        if (!state.value.isTeachingOverlayShowing) return
        change { copy(isTeachingOverlayShowing = false) }
        viewModelScope.launch { settings.update { it.copy(hasSeenTeachingOverlay = true) } }
    }

    // Committing a move

    /**
     * Applies [action] for whoever is to move and plays its events. Deliberately not behind
     * the input lock: that guards taps, and a move may also come from a player who is not
     * tapping this screen.
     */
    internal fun perform(action: GameAction) {
        val before = state.value.authoritativeState
        val mode = state.value.mode
        val after: GameState
        val events: List<GameEvent>
        try {
            if (mode is MatchMode.ByLink) {
                // There is no remote write to confirm, so the turn commits as soon as it is
                // prepared; the link to send is built from the same payload.
                val turn = LinkPlay.authority(mode, before).prepareTurn(action)
                after = turn.payload.state
                events = turn.events
                lastMove = turn.payload.lastMove
            } else {
                val transition = RulesEngine.apply(action, before)
                after = transition.state
                events = transition.events
            }
        } catch (error: RulesError) {
            // The board only offers legal moves, so this means the screen and the engine disagree.
            change { copy(errorMessage = error.message) }
            return
        } catch (error: MatchAuthorityError) {
            change { copy(errorMessage = error.message) }
            return
        }
        undoStack.addLast(before)
        change {
            copy(
                authoritativeState = after,
                hasUndoHistory = true,
                linkToSend = LinkPlay.linkToSend(mode, after, lastMove),
            )
        }
        // Saved as soon as the rules have decided it rather than once it has played out: a
        // player who leaves mid-animation, or a computer that answers while the app is in the
        // background, would otherwise come back to a match missing its last move.
        persist()
        animate(events)
    }

    /** Saves the match as it stands, or forgets it once it is over: a finished match has nothing to resume. */
    private fun persist() {
        val current = state.value
        val match = current.authoritativeState
        if (match.status.isOver) snapshots.clear() else snapshots.save(MatchSnapshot(current.mode, match))
    }

    /**
     * Queues the engine's events for the board. The rules and the scores were already
     * decided; the timeline only performs them.
     */
    private fun animate(events: List<GameEvent>) {
        change { copy(isResolving = true) }
        timeline.play(events)
    }

    private fun show(
        event: GameEvent,
        previous: GameEvent?,
    ) {
        val current = state.value
        // Read before the event is applied: a smoothie's blenders have left the board after it.
        val before = current.displayState
        val bursts = bursts(event, before)
        val splatter = splatter(event, bursts)
        change {
            copy(
                displayState = before.applying(event),
                flash = if (flashSurvives(event, previous)) flash else boardFlash(event, before) ?: flash,
                blendBursts = blendBursts + bursts,
                splatter = splatter ?: this.splatter,
            )
        }
        haptics.play(event)
        attachedHaptics?.play(event)
        // Retired here as well as by the view, which may leave before its animation ends.
        for (burst in bursts) retireLater(BlendBurst.LIFETIME_MILLIS) { retireBurst(burst.id) }
        splatter?.let { retireLater(Splatter.LIFETIME_MILLIS) { retireSplatter(it.id) } }
    }

    private fun retireLater(
        millis: Long,
        retire: () -> Unit,
    ) {
        viewModelScope.launch {
            delay(millis)
            retire()
        }
    }

    private fun finishTimeline() {
        change { copy(displayState = authoritativeState, isResolving = false, flash = null) }
        startCpuTurnIfNeeded()
    }

    // The computer

    /**
     * Starts the computer's search if it is the computer's turn and the board is still. The
     * search runs on [cpuDispatcher] against a snapshot, and its answer comes back here to
     * go through [perform] like any other move.
     */
    private fun startCpuTurnIfNeeded() {
        val current = state.value
        val mode = current.mode as? MatchMode.Computer ?: return
        val cpu = cpu ?: return
        val snapshot = current.authoritativeState
        if (snapshot.status.isOver || snapshot.activePlayer == mode.humanPlays || current.isResolving) return

        cpuJob?.cancel()
        change { copy(isOpponentThinking = true) }
        cpuJob =
            viewModelScope.launch {
                // Stops the search, not just the wait for it, when this job is cancelled.
                val action = withContext(cpuDispatcher) { cpu.selectAction(snapshot) { !coroutineContext.isActive } }
                // A little thinking time even when the answer was instant, so the turn reads
                // as a turn rather than a flicker.
                delay(MINIMUM_THINKING_MILLIS)
                change { copy(isOpponentThinking = false) }
                // By identity: anything that replaced the position since, a restart, an undo or
                // another move, makes this answer one to a question nobody is asking now.
                if (state.value.authoritativeState !== snapshot) return@launch
                // No action means the engine has already passed or ended the turn.
                if (action != null) perform(action)
            }
    }

    /**
     * Whether an event should leave the label already on screen alone.
     *
     * A battle emits `BlenderBattle` and then immediately `BlenderDestroyed`, and the second
     * one's "Blender destroyed" would replace the arithmetic barely after it appeared, which
     * is exactly when the player wants to read it. After a battle the totals stay up: they
     * already say a blender was destroyed, and whose.
     */
    private fun flashSurvives(
        event: GameEvent,
        previous: GameEvent?,
    ): Boolean = event is GameEvent.BlenderDestroyed && previous is GameEvent.BlenderBattle

    // Blend effects

    /**
     * Decorative only. The roll happens here, on the device watching, and never touches the
     * rules engine: two devices replaying the same match may splatter differently and both
     * are correct.
     */
    private fun bursts(
        event: GameEvent,
        before: GameState,
    ): List<BlendBurst> =
        when (event) {
            is GameEvent.Blended -> {
                listOf(burst(event.kind, event.at))
            }

            // A blender coming apart throws glass, not pulp.
            is GameEvent.BlenderDestroyed -> {
                listOf(burst(PieceKind.Blender, event.at))
            }

            // A burst wherever each blender was standing when it went up.
            is GameEvent.Smoothie -> {
                event.pieceIds.mapNotNull { before.piece(it)?.position }.map { burst(PieceKind.Blender, it) }
            }

            else -> {
                emptyList()
            }
        }

    // Only fruit splatters; that stays its own signature.
    private fun splatter(
        event: GameEvent,
        bursts: List<BlendBurst>,
    ): Splatter? {
        val burst = bursts.firstOrNull()
        if (event !is GameEvent.Blended || burst == null) return null
        if (blendEffects.roll() >= blendEffects.splatterChance) return null
        return Splatter(id = nextEffectId(), burstId = burst.id, kind = event.kind, seed = nextEffectSeed())
    }

    private fun burst(
        kind: PieceKind,
        position: Position,
    ) = BlendBurst(id = nextEffectId(), kind = kind, position = position, seed = nextEffectSeed())

    private fun nextEffectId(): Long = ++lastEffectId

    private fun nextEffectSeed(): ULong {
        effectSeed = effectSeed * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
        return effectSeed
    }

    /**
     * Called by the view once a burst has finished drawing. The view model retires it anyway
     * after [BlendBurst.LIFETIME_MILLIS], so a screen that leaves mid-burst leaves nothing behind.
     */
    fun retireBurst(id: Long) = change { copy(blendBursts = blendBursts.filter { it.id != id }) }

    /** Called by the view once the splatter has faded; retired anyway after [Splatter.LIFETIME_MILLIS]. */
    fun retireSplatter(id: Long) = change { if (splatter?.id == id) copy(splatter = null) else this }

    /** The label [event] puts over the board, or null when it leaves the current one alone. */
    private fun boardFlash(
        event: GameEvent,
        before: GameState,
    ): BoardFlash? {
        fun flash(
            text: String,
            position: Position,
        ) = BoardFlash(nextEffectId(), text, position)

        return when (event) {
            is GameEvent.Delivered -> {
                flash("×2  +${event.points}", event.at)
            }

            is GameEvent.Blended -> {
                if (event.fruitOwner == event.awardedTo) {
                    // A self-blend: the price is the point of the move, so say it plainly.
                    flash("Own goal!  −${abs(event.points)}", event.at)
                } else {
                    flash("+${event.points}", event.at)
                }
            }

            is GameEvent.BlenderBattle -> {
                // The whole sum, because the alternative is a blender vanishing on a dice
                // roll nobody saw. Reads from the bottom player's side of the board.
                val report = event.report
                val mine = report.attacker == state.value.bottomPlayer
                val ours = if (mine) report.attackerTotal else report.defenderTotal
                val theirs = if (mine) report.defenderTotal else report.attackerTotal
                val ourRoll = if (mine) report.attackerRoll else report.defenderRoll
                val theirRoll = if (mine) report.defenderRoll else report.attackerRoll
                flash("Battle!  $ours v $theirs  (rolled $ourRoll and $theirRoll)", event.at)
            }

            is GameEvent.BlenderDestroyed -> {
                flash("Blender destroyed", event.at)
            }

            is GameEvent.BlenderRestored -> {
                flash("Blender back · −${event.cost}", event.at)
            }

            is GameEvent.BlenderRespawned -> {
                flash("Blender back", event.at)
            }

            is GameEvent.PineappleTurnedGolden -> {
                before.piece(event.pieceId)?.position?.let { flash("Golden Pineapple!", it) }
            }

            is GameEvent.Smoothie -> {
                // Point at one of the blenders it took out.
                val square = event.pieceIds.firstNotNullOfOrNull { before.piece(it)?.position }
                flash("Tropical Smoothie!", square ?: Position(0, 0))
            }

            else -> {
                null
            }
        }
    }

    // Match controls

    /** Never in link play: a restart deals the match again under the same id, which the opponent's phone refuses as stale. */
    fun requestRestart() {
        if (state.value.canPlayAgain) change { copy(isShowingRestartConfirmation = true) }
    }

    fun cancelRestart() = change { copy(isShowingRestartConfirmation = false) }

    /**
     * The same match again, so it keeps the rule set and the board mode it started under. On
     * a random board that means a *new* scatter rather than the same one again: the point of
     * the mode is a board you have not seen.
     */
    fun restart() {
        if (!state.value.canPlayAgain) return
        timeline.stop()
        cpuJob?.cancel()
        undoStack.clear()
        val current = state.value
        val fresh =
            with(current.authoritativeState) {
                GameSetup.newGame(
                    ruleSet = ruleSet,
                    boardSetup = boardSetup,
                    boardWrap = boardWrap,
                    blenderBattles = blenderBattles,
                )
            }
        lastMove = null
        mutableState.value =
            MatchUiState(
                mode = current.mode,
                displayState = fresh,
                authoritativeState = fresh,
                errorMessage = current.errorMessage,
                isTeachingOverlayShowing = current.isTeachingOverlayShowing,
                linkToSend = LinkPlay.linkToSend(current.mode, fresh, lastMove = null),
            )
        persist()
        startCpuTurnIfNeeded()
    }

    /** Development affordance only: never shown in a release build. */
    fun undo() {
        if (!state.value.canUndo) return
        val previous = undoStack.removeLastOrNull() ?: return
        timeline.stop()
        cpuJob?.cancel()
        change {
            MatchUiState(
                mode = mode,
                displayState = previous,
                authoritativeState = previous,
                errorMessage = errorMessage,
                hasUndoHistory = undoStack.isNotEmpty(),
                isTeachingOverlayShowing = isTeachingOverlayShowing,
                // Never in link play, which has no undo; kept right should that change.
                linkToSend = LinkPlay.linkToSend(mode, previous, lastMove = null),
            )
        }
        persist()
        // Back to a position where the computer is to move, it moves again rather than sitting.
        startCpuTurnIfNeeded()
    }

    private companion object {
        /** The shortest a computer's turn ever takes. */
        const val MINIMUM_THINKING_MILLIS = 320L

        const val SHARE_SHEET_UNAVAILABLE = "Couldn't open the share sheet. The link was copied instead."
    }
}

/**
 * The computer as a match sees it: one action for a snapshot, found synchronously on whatever
 * thread calls it. An interface so tests can stand in a computer that answers on cue.
 */
fun interface CpuOpponent {
    /** @param shouldStop polled during the search; true means the answer is no longer wanted. */
    fun selectAction(
        state: GameState,
        shouldStop: () -> Boolean,
    ): GameAction?

    companion object {
        /** The engine's own player at [difficulty]. */
        fun forDifficulty(difficulty: CpuDifficulty): CpuOpponent {
            val player = CpuPlayer(difficulty)
            return CpuOpponent { state, shouldStop -> player.selectAction(state, shouldStop) }
        }
    }
}

/** A local match for previews, with the introduction already seen and nothing stored. */
internal fun previewMatchViewModel(initialState: GameState): MatchViewModel =
    MatchViewModel(
        mode = MatchMode.LocalVersus,
        initialState = initialState,
        settings = InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true)),
    )
