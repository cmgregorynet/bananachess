package com.charlesbarton.bananachess.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.art.WashiPanel
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * Score, remaining fruit, and whose turn it is, for one side of the board. TalkBack reads the
 * panel as one sentence, and the wildcard tracker under it as another.
 */
@Composable
fun ScoreboardView(
    scoreboard: Scoreboard,
    seat: PlayerSeat,
    modifier: Modifier = Modifier,
) {
    WashiPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        fill = Palette.panelTint(seat).copy(alpha = 0.65f),
        isAccented = scoreboard.isActive,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = scoreboard.accessibilityLabel },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = scoreboard.name, style = MatchType.playerName)
                    RemainingFruit(scoreboard = scoreboard, seat = seat)
                }
                Text(text = scoreboard.score.toString(), style = MatchType.score)
            }
            scoreboard.wildcards?.let { WildcardTrackerView(standing = it, seat = seat) }
        }
    }
}

// Wraps, so at a large font size the note drops under the fruit instead of running into the score.
@Composable
private fun RemainingFruit(
    scoreboard: Scoreboard,
    seat: PlayerSeat,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (piece in scoreboard.remainingFruit) {
            PieceArtwork(kind = piece.kind, seat = seat, modifier = Modifier.size(18.dp), showsTray = false)
        }
        Text(
            text = scoreboard.fruitDetail,
            style = if (scoreboard.isOnLastFruit) MatchType.lastFruit else MatchType.fruitDetail,
        )
    }
}

@Preview(widthDp = 360)
@Composable
private fun ScoreboardPreview() {
    val state = GameSetup.newGame(ruleSet = RuleSet.Wildcard)
    val ui = MatchUiState(mode = MatchMode.LocalVersus, displayState = state, authoritativeState = state)
    PaperTheme {
        PaperBackground {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ScoreboardView(scoreboard = ui.scoreboard(PlayerId.Two), seat = PlayerSeat.Top)
                ScoreboardView(scoreboard = ui.scoreboard(PlayerId.One), seat = PlayerSeat.Bottom)
            }
        }
    }
}
