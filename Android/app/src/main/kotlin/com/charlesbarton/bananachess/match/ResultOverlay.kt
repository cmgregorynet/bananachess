package com.charlesbarton.bananachess.match

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.WashiPanel
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * How the match ended, over a scrim that fills its parent and keeps touches off the board and
 * the controls underneath. Anything the player must still be able to reach once the match is
 * over goes in [notice], which sits above the panel.
 */
@Composable
fun ResultOverlay(
    state: MatchUiState,
    onPlayAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    notice: @Composable () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawBehind { drawRect(Palette.ink.copy(alpha = SCRIM_ALPHA)) }
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            notice()
            WashiPanel(
                modifier = Modifier.weight(1f, fill = false).testTag(MatchTags.RESULT),
                cornerRadius = 22.dp,
                isAccented = true,
            ) {
                // Scrolls, so both buttons can be reached at any font size.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(26.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Headline(state = state)
                    FinalScores(state = state)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.canPlayAgain) {
                            PaperButton(
                                text = "Play again",
                                onClick = onPlayAgain,
                                isPrimary = true,
                            )
                        }
                        PaperButton(text = "Done", onClick = onDone)
                    }
                }
            }
        }
    }
}

@Composable
private fun Headline(state: MatchUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.resultText.orEmpty(),
            style = MatchType.resultTitle,
            textAlign = TextAlign.Center,
            // Polite, so TalkBack says how the match ended when the panel appears.
            modifier =
                Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        state.resultDetail?.let { Text(text = it, style = MatchType.resultDetail, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun FinalScores(state: MatchUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        for (player in listOf(state.bottomPlayer, state.topPlayer)) {
            val scoreboard = state.scoreboard(player)
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = scoreboard.name, style = MatchType.resultName)
                Text(text = scoreboard.score.toString(), style = MatchType.resultScore)
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.28f

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ResultOverlayPreview() {
    val finished =
        GameState(
            pieces = GameSetup.newGame().pieces,
            status = GameStatus.Ended(Outcome.Win(PlayerId.One, WinReason.BananaSplit)),
        )
    PaperTheme {
        PaperBackground {
            ResultOverlay(
                state = MatchUiState(MatchMode.LocalVersus, displayState = finished, authoritativeState = finished),
                onPlayAgain = {},
                onDone = {},
            )
        }
    }
}
