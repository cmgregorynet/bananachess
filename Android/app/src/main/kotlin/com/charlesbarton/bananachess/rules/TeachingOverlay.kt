package com.charlesbarton.bananachess.rules

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.art.WashiPanel
import com.charlesbarton.bananachess.art.isReducedMotion
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One page of the first-match introduction. */
data class TeachingPage(
    val title: String,
    val body: String,
    val kind: PieceKind,
)

/**
 * What a new player is told before their first move. It teaches that blending cuts both ways but
 * deliberately does not point out the self-blend tactic: the game wants to see whether players
 * find it.
 */
val TeachingPages: List<TeachingPage> =
    listOf(
        TeachingPage(
            title = "Move one piece",
            body = "Fruit steps one square straight. Blenders step one square diagonally.",
            kind = PieceKind.Banana,
        ),
        TeachingPage(
            title = "Reach the far edge",
            body = "Fruit that reaches the opposite edge scores double its value and leaves the board.",
            kind = PieceKind.Strawberry,
        ),
        TeachingPage(
            title = "Blending cuts both ways",
            body = "A blender can take any fruit. Theirs pays you its value. Yours costs you the same.",
            kind = PieceKind.Blender,
        ),
        // Not the iOS wording, which still describes a rule the game no longer has. This agrees
        // with "Blender combat" on the Rules screen.
        TeachingPage(
            title = "Blenders knock out",
            body =
                "Run a blender into another and it destroys that one outright. A lost blender never " +
                    "comes back on its own: buying it back costs a turn and three points.",
            kind = PieceKind.Pineapple,
        ),
    )

/** Which page of the introduction is showing. */
@Stable
class TeachingOverlayState(
    initialPage: Int = 0,
    val pages: List<TeachingPage> = TeachingPages,
) {
    var pageIndex: Int by mutableIntStateOf(initialPage.coerceIn(pages.indices))
        private set

    val page: TeachingPage get() = pages[pageIndex]

    val isLastPage: Boolean get() = pageIndex == pages.lastIndex

    /** "Next" on every page but the last, where it is "Play". */
    val actionLabel: String get() = if (isLastPage) "Play" else "Next"

    /** Turns to the next page. Returns false on the last page, where there is none to turn to. */
    fun advance(): Boolean {
        if (isLastPage) return false
        pageIndex += 1
        return true
    }

    companion object {
        val Saver: Saver<TeachingOverlayState, Int> =
            Saver(save = { it.pageIndex }, restore = { TeachingOverlayState(initialPage = it) })
    }
}

/** A [TeachingOverlayState] that keeps its page across a rotation. */
@Composable
fun rememberTeachingOverlayState(): TeachingOverlayState =
    rememberSaveable(saver = TeachingOverlayState.Saver) { TeachingOverlayState() }

/**
 * The introduction as the Match screen shows it: only to a player who has never seen it, and
 * never again once they press "Play". Place it last in the screen's root box, over the board.
 *
 * Shows nothing until the settings have been read, so a returning player never sees it flash.
 *
 * It is drawn in the host's tree, not in a window of its own, so it does two things a dialog
 * would get for free: the system back button closes it, exactly as "Play" does, and
 * [onShowingChange] tells the host when to hide what is underneath from TalkBack.
 */
@Composable
fun FirstMatchTeachingOverlay(
    settings: SettingsStore,
    modifier: Modifier = Modifier,
    onShowingChange: (Boolean) -> Unit = {},
) {
    val hasSeen by remember(settings) { settings.settings.map { it.hasSeenTeachingOverlay } }
        .collectAsStateWithLifecycle(initialValue = true)
    // Closes on the press, not when the write lands: the write is best-effort and may never land.
    var isDismissed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isShowing = !hasSeen && !isDismissed

    fun finish() {
        isDismissed = true
        scope.launch { settings.update { it.copy(hasSeenTeachingOverlay = true) } }
    }

    val currentOnShowingChange by rememberUpdatedState(onShowingChange)
    LaunchedEffect(isShowing) { currentOnShowingChange(isShowing) }

    if (isShowing) {
        BackHandler(onBack = ::finish)
        TeachingOverlay(onFinished = ::finish, modifier = modifier)
    }
}

/**
 * The four-page introduction, over a scrim that fills its parent and keeps touches off whatever
 * is underneath. [onFinished] is called once, by "Play" on the last page; it is the caller's cue
 * to record that the introduction has been seen and to stop showing it.
 */
@Composable
fun TeachingOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    state: TeachingOverlayState = rememberTeachingOverlayState(),
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawBehind { drawRect(Palette.ink.copy(alpha = SCRIM_ALPHA)) }
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        WashiPanel(cornerRadius = 22.dp, isAccented = true) {
            Column(
                modifier = Modifier.padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The page scrolls and the button does not, so "Next" is on screen at any font size.
                PageContent(
                    state = state,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                PageDots(count = state.pages.size, current = state.pageIndex)
                PaperButton(
                    text = state.actionLabel,
                    onClick = { if (!state.advance()) onFinished() },
                    isPrimary = true,
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    state: TeachingOverlayState,
    modifier: Modifier = Modifier,
) {
    val turn = if (isReducedMotion(LocalContext.current)) snap() else tween<Float>(durationMillis = PAGE_TURN_MILLIS)
    Crossfade(targetState = state.page, modifier = modifier, animationSpec = turn, label = "teachingPage") { page ->
        Column(
            // Polite, so TalkBack reads the new page when "Next" turns to it.
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PieceArtwork(kind = page.kind, seat = PlayerSeat.Bottom, modifier = Modifier.size(76.dp))
            Text(
                text = page.title,
                style = RulesType.heading,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = page.body, style = RulesType.detail, textAlign = TextAlign.Center)
        }
    }
}

// Drawn, not clipped circles: one node that says where the player is, instead of four that say nothing.
@Composable
private fun PageDots(
    count: Int,
    current: Int,
) {
    val dot = 7.dp
    val gap = 6.dp
    Box(
        modifier =
            Modifier
                .size(width = dot * count + gap * (count - 1), height = dot)
                .semantics { contentDescription = "Page ${current + 1} of $count" }
                .drawBehind {
                    val radius = dot.toPx() / 2f
                    for (index in 0 until count) {
                        drawCircle(
                            color = if (index == current) Palette.amber else Palette.inkFaint,
                            radius = radius,
                            center = Offset(radius + index * (dot + gap).toPx(), radius),
                        )
                    }
                },
    )
}

private const val SCRIM_ALPHA = 0.3f
private const val PAGE_TURN_MILLIS = 200

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun TeachingOverlayPreview() {
    PaperTheme {
        PaperBackground {
            TeachingOverlay(onFinished = {})
        }
    }
}
