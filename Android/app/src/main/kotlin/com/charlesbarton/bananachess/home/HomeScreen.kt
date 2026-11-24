package com.charlesbarton.bananachess.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.art.WashiPanel
import com.charlesbarton.bananachess.engine.model.BoardOption
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.settings.InMemorySettingsStore

/** The start screen: the ways to play, and the board and rules they are played with. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartMatch: (MatchSource) -> Unit,
    onShowRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isShowingCpuOptions by rememberSaveable { mutableStateOf(false) }
    // Each time Home is shown, including on the way back from a match.
    LaunchedEffect(viewModel) { viewModel.refreshResume() }

    PaperBackground(modifier = modifier) {
        // Scrolls, because it has to: the menu, two board choices and two rules opt-ins do not
        // fit a small phone, or any phone at a large font size.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Title(modifier = Modifier.padding(top = 12.dp))
            Menu(
                state = state,
                onPlayLocal = { onStartMatch(viewModel.startLocal()) },
                onResume = { onStartMatch(MatchSource.Saved) },
                onPlayComputer = { isShowingCpuOptions = true },
                onInvite = { onStartMatch(viewModel.invite()) },
            )
            BoardOptionsPanel(
                setup = state.boardSetup,
                wrap = state.boardWrap,
                onSetupChange = viewModel::setBoardSetup,
                onWrapChange = viewModel::setBoardWrap,
            )
            RulesCheckbox(
                title = "Wildcard rules",
                detail = "Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama.",
                checked = state.usesWildcardRules,
                onCheckedChange = viewModel::setUsesWildcardRules,
            )
            RulesCheckbox(
                title = "Blender battles",
                detail =
                    "Blenders fight for a square instead of just taking it. " +
                        "Half your standing fruit, plus one for attacking, plus a die each.",
                checked = state.blenderBattles,
                onCheckedChange = viewModel::setBlenderBattles,
            )
            PaperButton(
                text = "How to play",
                onClick = onShowRules,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (isShowingCpuOptions) {
        CpuOptionsSheet(
            difficulty = state.difficulty,
            humanPlaysFirst = state.humanPlaysFirst,
            onDifficultyChange = viewModel::setDifficulty,
            onHumanPlaysFirstChange = viewModel::setHumanPlaysFirst,
            onStart = {
                isShowingCpuOptions = false
                onStartMatch(viewModel.startComputer())
            },
            onDismiss = { isShowingCpuOptions = false },
        )
    }
}

@Composable
private fun Title(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PieceArtwork(kind = PieceKind.Banana, seat = PlayerSeat.Bottom, modifier = Modifier.size(46.dp))
            PieceArtwork(kind = PieceKind.Blender, seat = PlayerSeat.Top, modifier = Modifier.size(46.dp))
            PieceArtwork(kind = PieceKind.Strawberry, seat = PlayerSeat.Bottom, modifier = Modifier.size(46.dp))
        }
        Text(
            text = "Banana Chess",
            style = HomeType.title,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = "Race the fruit. Mind the blenders.", style = HomeType.subtitle, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Menu(
    state: HomeUiState,
    onPlayLocal: () -> Unit,
    onResume: () -> Unit,
    onPlayComputer: () -> Unit,
    onInvite: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Above everything else, and the one that stands out while it is there: a match left
        // part-way is the likeliest thing to come back for.
        state.resume?.let { resume ->
            MenuButton(title = "Resume match", detail = resume.detail, onClick = onResume, isPrimary = true)
        }
        MenuButton(
            title = "Local Two Player",
            detail = "Share one phone and take turns.",
            onClick = onPlayLocal,
            isPrimary = state.resume == null,
        )
        MenuButton(
            title = "Play Computer",
            detail = "${state.difficulty.displayName} · ${state.difficulty.blurb}",
            onClick = onPlayComputer,
        )
        MenuButton(
            title = "Invite by Link",
            detail = "Send a friend a link and play.",
            onClick = onInvite,
        )
    }
}

/**
 * The two board dimensions in one panel. Kept together because they are one decision in the
 * player's head ("what board am I playing on"), and because two panels would push the rest of
 * the start screen further down a small phone.
 */
@Composable
private fun BoardOptionsPanel(
    setup: BoardSetup,
    wrap: BoardWrap,
    onSetupChange: (BoardSetup) -> Unit,
    onWrapChange: (BoardWrap) -> Unit,
) {
    WashiPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionRow(
                title = "Board",
                options = BoardSetup.entries,
                selected = setup,
                name = BoardOption::displayName,
                blurb = BoardOption::blurb,
                onSelect = onSetupChange,
            )
            OptionRow(
                title = "Edges",
                options = BoardWrap.entries,
                selected = wrap,
                name = BoardOption::displayName,
                blurb = BoardOption::blurb,
                onSelect = onWrapChange,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 900)
@Composable
private fun HomeScreenPreview() {
    val viewModel = remember { HomeViewModel(InMemorySettingsStore()) }
    PaperTheme {
        HomeScreen(
            viewModel = viewModel,
            onStartMatch = {},
            onShowRules = {},
        )
    }
}
