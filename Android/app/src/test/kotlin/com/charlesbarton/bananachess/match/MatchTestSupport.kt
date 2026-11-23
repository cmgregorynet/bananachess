package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.milliseconds

/** Remembers what the view model asked the phone to do, and lets a test look at the screen at that moment. */
internal class RecordingHaptics : MatchHaptics {
    val events = mutableListOf<GameEvent>()
    var selections = 0
    var onPlay: (GameEvent) -> Unit = {}

    override fun selection() {
        selections += 1
    }

    override fun play(event: GameEvent) {
        events += event
        onPlay(event)
    }
}

/** A view model whose timeline runs on the test's virtual clock. */
internal fun TestScope.matchViewModel(
    state: GameState = GameSetup.newGame(),
    mode: MatchMode = MatchMode.LocalVersus,
    blendEffects: BlendEffectPolicy = BlendEffectPolicy.Never,
    haptics: MatchHaptics = MatchHaptics.None,
    reduceMotion: () -> Boolean = { false },
): MatchViewModel =
    MatchViewModel(
        mode = mode,
        initialState = state,
        blendEffects = blendEffects,
        haptics = haptics,
        reduceMotion = reduceMotion,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

/** Moves virtual time on by [millis], including whatever falls due exactly at the end of it. */
internal fun TestScope.advance(millis: Long) {
    testScheduler.advanceTimeBy(millis.milliseconds)
    testScheduler.runCurrent()
}

internal fun TestScope.settle() = testScheduler.advanceUntilIdle()

internal val MatchViewModel.ui: MatchUiState get() = state.value

internal fun square(
    row: Int,
    column: Int,
) = Position(row, column)

internal fun fruit(
    owner: PlayerId,
    kind: PieceKind,
    row: Int,
    column: Int,
    index: Int = 0,
) = Piece(
    id = PieceId("${owner.rawValue}.${kind.rawValue}.$index"),
    owner = owner,
    kind = kind,
    position = Position(row, column),
)

/** A blender; [at] is null when it is off the board. Homes default to the standard spawns. */
internal fun blender(
    owner: PlayerId,
    index: Int,
    at: Position?,
    home: Position = Position(owner.backRow, if (index == 0) 1 else 4),
    respawnTurn: Int? = null,
) = Piece(
    id = PieceId("${owner.rawValue}.blender.$index"),
    owner = owner,
    kind = PieceKind.Blender,
    position = at,
    home = home,
    respawnTurn = respawnTurn,
)

/** Enough fruit standing well apart on both sides that one blend or delivery never ends the match. */
internal val spareFruit: List<Piece> =
    listOf(
        fruit(PlayerId.One, PieceKind.Strawberry, row = 4, column = 5, index = 8),
        fruit(PlayerId.One, PieceKind.Pineapple, row = 4, column = 3, index = 9),
        fruit(PlayerId.Two, PieceKind.Strawberry, row = 1, column = 5, index = 8),
        fruit(PlayerId.Two, PieceKind.Pineapple, row = 1, column = 3, index = 9),
    )
