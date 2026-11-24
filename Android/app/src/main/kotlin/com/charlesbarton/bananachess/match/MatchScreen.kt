package com.charlesbarton.bananachess.match

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.setup.GameSetup
import com.charlesbarton.bananachess.rules.FirstMatchTeachingOverlay
import com.charlesbarton.bananachess.settings.InMemorySettingsStore
import com.charlesbarton.bananachess.settings.Settings
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlin.math.max
import kotlin.math.min

/** Test tags of the Match screen. The squares have their own, `square_<row>_<column>`. */
object MatchTags {
    const val SCREEN = "matchScreen"
    const val TURN_INDICATOR = "turnIndicator"
    const val ERROR_BANNER = "errorBanner"
    const val RESULT = "resultOverlay"
}

/** The Match screen's type, in the platform serif. Sizes are sp, so they follow the system font size. */
internal object MatchType {
    private val serif = TextStyle(fontFamily = FontFamily.Serif, color = Palette.ink)
    private const val TABULAR_FIGURES = "tnum"

    val playerName = serif.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val fruitDetail = serif.copy(fontSize = 11.sp, lineHeight = 14.sp, color = Palette.inkSoft)
    val lastFruit = fruitDetail.copy(color = Palette.amber, fontWeight = FontWeight.SemiBold)
    val score = serif.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val turnIndicator = serif.copy(fontSize = 15.sp, lineHeight = 20.sp)
    val turnCount = fruitDetail.copy(fontFeatureSettings = TABULAR_FIGURES)
    val error = serif.copy(fontSize = 13.sp, lineHeight = 18.sp)

    val trackerLabel = serif.copy(fontSize = 9.sp, lineHeight = 12.sp, color = Palette.inkSoft)
    val trackerCount =
        serif.copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = TABULAR_FIGURES,
        )

    val resultTitle = serif.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val resultDetail = serif.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.inkSoft)
    val resultName = serif.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Palette.inkSoft)
    val resultScore = serif.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)

    val dialogTitle = serif.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val dialogMessage = serif.copy(fontSize = 15.sp, lineHeight = 20.sp)
}

private object MatchScreenSpec {
    val gap = 14.dp
    val horizontalPadding = 16.dp
    val verticalPadding = 8.dp

    // Beyond the screen padding, so the printed border and its 6 dp outset have room.
    val boardInset = 10.dp

    // Six squares of 48 dp. The board gives up height to the scoreboards down to this and no
    // further; past that the screen scrolls instead.
    val smallestBoard = 288.dp

    const val RESULT_FADE_MILLIS = 300
}

/**
 * One match on screen: the two scoreboards, whose turn it is, the board and the controls, with
 * the result and the first-match introduction over them when they apply.
 *
 * It draws what [viewModel] says and sends taps back; it decides nothing. The timeline is held
 * whenever the screen is not resumed.
 *
 * @param onExit leaves the match for the start screen.
 * @param showsDebugControls the development affordances: "Undo" and the turn count.
 */
@Composable
fun MatchScreen(
    viewModel: MatchViewModel,
    settings: SettingsStore,
    onExit: () -> Unit,
    onShowRules: () -> Unit,
    modifier: Modifier = Modifier,
    showsDebugControls: Boolean = isDebuggable(LocalContext.current),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isTeaching by remember { mutableStateOf(false) }

    // Left running, a move would finish off screen, and the player would come back to a board
    // that had changed without them seeing how.
    LifecycleResumeEffect(viewModel) {
        viewModel.onSceneActive()
        onPauseOrDispose { viewModel.onSceneInactive() }
    }

    val errorBanner: @Composable () -> Unit = {
        state.errorMessage?.let { ErrorBanner(message = it, onDismiss = viewModel::dismissError) }
    }

    PaperBackground(modifier = modifier.testTag(MatchTags.SCREEN)) {
        // The introduction is drawn in this tree, not in a window of its own, so while it is up
        // everything under it is taken away from TalkBack by hand.
        Box(modifier = Modifier.fillMaxSize().then(if (isTeaching) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopBar(onBack = onExit, onShowRules = onShowRules)
                MatchLayout(
                    modifier = Modifier.weight(1f),
                    above = {
                        ScoreboardView(
                            scoreboard = state.scoreboard(state.topPlayer),
                            seat = state.seat(state.topPlayer),
                        )
                        TurnIndicator(state = state)
                        // Once the match is over the scrim covers this spot, so the result
                        // overlay shows the banner instead.
                        if (state.outcome == null) errorBanner()
                    },
                    board = { BoardView(state = state, onSquareTap = viewModel::tap) },
                    below = {
                        ScoreboardView(
                            scoreboard = state.scoreboard(state.bottomPlayer),
                            seat = state.seat(state.bottomPlayer),
                        )
                        // Link play's "send" button belongs here, between the scoreboard and the
                        // controls. Once the match is over, anything here is under the scrim.
                        Controls(
                            state = state,
                            showsDebugControls = showsDebugControls,
                            onRestart = viewModel::requestRestart,
                            onUndo = viewModel::undo,
                        )
                    },
                )
            }
            AnimatedVisibility(
                visible = state.outcome != null,
                enter = fadeIn(tween(durationMillis = MatchScreenSpec.RESULT_FADE_MILLIS)),
                // "Play again" clears the result at once; fading it out would show a blank panel.
                exit = ExitTransition.None,
            ) {
                ResultOverlay(state = state, onPlayAgain = viewModel::restart, onDone = onExit, notice = errorBanner)
            }
            // The splatter is drawn here: over the result, under the introduction, taking no touches.
        }
        FirstMatchTeachingOverlay(settings = settings, onShowingChange = { isTeaching = it })
    }

    if (state.isShowingRestartConfirmation) {
        RestartDialog(onRestart = viewModel::restart, onKeepPlaying = viewModel::cancelRestart)
    }
    state.pendingSelfBlend?.let { pending ->
        SelfBlendDialog(
            pending = pending,
            onConfirm = viewModel::confirmSelfBlend,
            onCancel = viewModel::cancelSelfBlend,
        )
    }
}

private fun isDebuggable(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onShowRules: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperButton(text = "Back", onClick = onBack)
        PaperButton(text = "Rules", onClick = onShowRules)
    }
}

/**
 * The screen from the top scoreboard down. The board is as wide as the screen allows and gives
 * up height to its neighbours only as far as [MatchScreenSpec.smallestBoard]; when even that does
 * not fit, which is what a large font size does to the scoreboards, the whole thing scrolls and
 * the board keeps its size.
 */
@Composable
private fun MatchLayout(
    above: @Composable ColumnScope.() -> Unit,
    board: @Composable () -> Unit,
    below: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = Arrangement.spacedBy(MatchScreenSpec.gap)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewport = constraints
        Layout(
            contents =
                listOf(
                    { Column(verticalArrangement = spacing, content = above) },
                    board,
                    {
                        Column(
                            verticalArrangement = spacing,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = below,
                        )
                    },
                ),
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = MatchScreenSpec.horizontalPadding,
                        vertical = MatchScreenSpec.verticalPadding,
                    ),
        ) { (aboveBoard, theBoard, belowBoard), constraints ->
            val width = constraints.maxWidth
            val fullWidth = Constraints.fixedWidth(width)
            val top = aboveBoard.single().measure(fullWidth)
            val bottom = belowBoard.single().measure(fullWidth)
            val gap = MatchScreenSpec.gap.roundToPx()
            val widest = max(0, width - 2 * MatchScreenSpec.boardInset.roundToPx())
            val smallest = min(widest, MatchScreenSpec.smallestBoard.roundToPx())
            val neighbours = top.height + bottom.height + 2 * gap
            val visibleHeight =
                if (viewport.hasBoundedHeight) {
                    viewport.maxHeight - 2 * MatchScreenSpec.verticalPadding.roundToPx()
                } else {
                    neighbours + widest
                }
            val side = (visibleHeight - neighbours).coerceIn(smallest, widest)
            val placedBoard = theBoard.single().measure(Constraints.fixed(side, side))
            val contentHeight = neighbours + side
            val height = max(contentHeight, visibleHeight)
            layout(width, height) {
                // Centred when there is room to spare, as a tall phone has.
                val y = (height - contentHeight) / 2
                top.place(0, y)
                placedBoard.place((width - side) / 2, y + top.height + gap)
                bottom.place(0, y + top.height + gap + side + gap)
            }
        }
    }
}

@Composable
private fun TurnIndicator(
    state: MatchUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The spinner shown while the opponent is thinking goes here, before the text.
        Text(
            text = state.turnIndicatorText,
            style = MatchType.turnIndicator,
            textAlign = TextAlign.Center,
            // Polite, so TalkBack says whose turn it is each time it changes.
            modifier = Modifier.testTag(MatchTags.TURN_INDICATOR).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Something went wrong, said in a sentence, with a way to put it away. It takes room of its own
 * and never covers the board.
 */
@Composable
internal fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(MatchTags.ERROR_BANNER)
                .background(MaterialTheme.colorScheme.errorContainer, shape)
                .border(width = 1.4.dp, color = MaterialTheme.colorScheme.error, shape = shape)
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MatchType.error,
            // Assertive: the player has just done something that did not work.
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive },
        )
        PaperButton(text = "Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun Controls(
    state: MatchUiState,
    showsDebugControls: Boolean,
    onRestart: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PaperButton(text = "Restart", onClick = onRestart)
        if (showsDebugControls) {
            PaperButton(text = "Undo", onClick = onUndo, enabled = state.canUndo)
            // The turn count is the stall metric to watch during playtesting.
            Text(text = "t${state.displayState.turnNumber}", style = MatchType.turnCount)
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun MatchScreenPreview() {
    val viewModel = remember { MatchViewModel(MatchMode.LocalVersus, GameSetup.newGame(ruleSet = RuleSet.Wildcard)) }
    val settings = remember { InMemorySettingsStore(Settings(hasSeenTeachingOverlay = true)) }
    PaperTheme {
        MatchScreen(viewModel = viewModel, settings = settings, onExit = {}, onShowRules = {})
    }
}
