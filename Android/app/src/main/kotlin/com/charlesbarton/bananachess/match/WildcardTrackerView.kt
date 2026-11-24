package com.charlesbarton.bananachess.match

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.WildcardStanding
import com.charlesbarton.bananachess.engine.model.wildcardStanding
import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * Blenderama appears once a player has started one: a 0/6 counter on every match would be noise.
 *
 * It does not appear merely for being countered. Under the shutout rule that is the normal state
 * within about forty turns of every match, so it would pin a dead row to the screen all game.
 * The warning lives where the decision is made: the self-blend dialog says outright when
 * Blenderama is already off.
 */
private val WildcardStanding.showsBlenderama: Boolean get() = ownFruitBlended > 0

/** The whole tracker in one sentence, which is how TalkBack reads it. */
internal fun WildcardStanding.trackerDescription(): String {
    val parts =
        mutableListOf(
            "Smoothie ${smoothie.collectedCount} of 3" + if (hasFiredSmoothie) ", already poured" else "",
            "Banana Split $bananasDelivered of $bananaSplitTarget" +
                if (isBananaSplitBlocked) ", no longer possible" else "",
        )
    if (showsBlenderama) {
        val fate =
            when {
                isBlenderamaCountered -> ", countered"
                isBlenderamaBlocked -> ", no longer possible"
                else -> ""
            }
        parts += "Blenderama $ownFruitBlended of $blenderamaTarget$fate"
    }
    return parts.joinToString(", ")
}

/**
 * The wildcard threats, kept where both players can read them. The rules only work if an
 * alternate win is something you can see coming and go and stop, so nothing here is hidden from
 * either side.
 */
@Composable
fun WildcardTrackerView(
    standing: WildcardStanding,
    seat: PlayerSeat,
    modifier: Modifier = Modifier,
) {
    val description = standing.trackerDescription()
    // Wraps, so a third track or a large font size makes a second line, not a clipped one.
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(TRACK_GAP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Track(label = "Smoothie", isComplete = standing.hasFiredSmoothie) {
            for (kind in listOf(PieceKind.Banana, PieceKind.Strawberry, PieceKind.Pineapple)) {
                TrackIcon(kind = kind, seat = seat, isCollected = standing.smoothie.has(kind))
            }
        }
        Track(
            label = "Split",
            isComplete = standing.isBananaSplitComplete,
            isBlocked = standing.isBananaSplitBlocked,
        ) {
            for (index in 0 until standing.bananaSplitTarget) {
                TrackIcon(kind = PieceKind.Banana, seat = seat, isCollected = index < standing.bananasDelivered)
            }
        }
        if (standing.showsBlenderama) {
            Track(
                label = if (standing.isBlenderamaCountered) "Countered" else "Blenderama",
                isComplete = false,
                isBlocked = standing.isBlenderamaBlocked,
            ) {
                Text(text = "${standing.ownFruitBlended}/${standing.blenderamaTarget}", style = MatchType.trackerCount)
            }
        }
    }
}

@Composable
private fun Track(
    label: String,
    isComplete: Boolean,
    isBlocked: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                // Ruled out rather than hidden: knowing a threat is dead is as useful as knowing
                // it is live, to both players.
                .alpha(if (isBlocked) BLOCKED_ALPHA else 1f)
                .background(Palette.cream.copy(alpha = if (isComplete) 0.9f else 0.45f), CircleShape)
                .border(
                    width = if (isComplete) 1.6.dp else 0.8.dp,
                    color = if (isComplete) Palette.amber else Palette.inkFaint,
                    shape = CircleShape,
                ).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MatchType.trackerLabel,
            textDecoration = if (isBlocked) TextDecoration.LineThrough else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun TrackIcon(
    kind: PieceKind,
    seat: PlayerSeat,
    isCollected: Boolean,
) {
    PieceArtwork(
        kind = kind,
        seat = seat,
        modifier = Modifier.size(15.dp).then(if (isCollected) Modifier else Modifier.greyedOut()),
        showsTray = false,
    )
}

// Not yet collected: drained of colour and nearly transparent.
private fun Modifier.greyedOut(): Modifier =
    drawWithContent {
        drawContext.canvas.saveLayer(size.toRect(), greyedOutPaint)
        drawContent()
        drawContext.canvas.restore()
    }

private val greyedOutPaint =
    Paint().apply {
        alpha = 0.22f
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

// Narrower than the 14 dp of the iOS row, so all three tracks share a line on a 360 dp phone.
private val TRACK_GAP = 10.dp
private const val BLOCKED_ALPHA = 0.42f

@Preview(widthDp = 360)
@Composable
private fun WildcardTrackerPreview() {
    val standing =
        GameSetup
            .newGame(ruleSet = RuleSet.Wildcard)
            .wildcardStanding(PlayerId.One)
            .copy(bananasDelivered = 2, ownFruitBlended = 3)
    PaperTheme {
        PaperBackground {
            WildcardTrackerView(standing = standing, seat = PlayerSeat.Bottom, modifier = Modifier.padding(16.dp))
        }
    }
}
