package com.charlesbarton.bananachess.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.PaperTheme
import com.charlesbarton.bananachess.art.PieceArtwork
import com.charlesbarton.bananachess.art.PlayerSeat
import com.charlesbarton.bananachess.engine.model.PieceKind

/** The rules and the teaching overlay's type, in the platform serif. Sizes are sp. */
internal object RulesType {
    private val serif = TextStyle(fontFamily = FontFamily.Serif, color = Palette.ink)

    val barTitle = serif.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val heading = serif.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val body = serif.copy(fontSize = 17.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val bodySoft = body.copy(color = Palette.inkSoft)
    val detail = serif.copy(fontSize = 15.sp, lineHeight = 20.sp, color = Palette.inkSoft)
}

/** "How to play": the whole game on one scrolling page. */
@Composable
fun RulesScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(onDone = onDone)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                TheTurn()
                HowPiecesMove()
                Scoring()
                BlendingCutsBothWays()
                BlenderCombat()
                FruitIsTheClock()
                WildcardRules()
            }
        }
    }
}

@Composable
private fun TopBar(onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "How to play",
            style = RulesType.barTitle,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        PaperButton(text = "Done", onClick = onDone)
    }
}

@Composable
private fun TheTurn() {
    Section(title = "The turn") {
        Body("Move one of your pieces each turn. That is the whole turn — no quotas, no forced moves.")
    }
}

@Composable
private fun HowPiecesMove() {
    Section(title = "How pieces move") {
        RuleRow(
            kind = PieceKind.Banana,
            text = "Fruit moves one square up, down, left, or right. Forwards and backwards both count.",
        )
        RuleRow(kind = PieceKind.Blender, text = "Blenders move one square diagonally, in any direction.")
        Body("Nothing moves through another piece. Fruit never captures.")
        Body(
            "You cannot make a move that would put the board back into an arrangement it has already " +
                "been in twice. Shuffling back and forth is allowed once; the third time round, that " +
                "move is simply not offered to you.",
        )
        Body(
            "Whether the board has edges at all is set before the match. Join the sides, or every " +
                "edge, and fruit that steps off one side arrives at the other — the joined edges are " +
                "marked with a dashed line. Blenders never cross one, on any board: the shortcut " +
                "belongs to the fruit, not to the machines chasing it.",
        )
    }
}

@Composable
private fun Scoring() {
    Section(title = "Scoring") {
        ValueRow(kind = PieceKind.Banana)
        ValueRow(kind = PieceKind.Strawberry)
        ValueRow(kind = PieceKind.Pineapple)
        Body(
            "Get fruit to the opposite edge for double its value. Blending only ever pays the plain " +
                "value — never double.",
        )
    }
}

@Composable
private fun BlendingCutsBothWays() {
    Section(title = "Blending cuts both ways") {
        Body(
            "A blender can move onto any fruit at all. Blend theirs and you gain its value. Blend " +
                "your own and you lose it.",
        )
        Body(
            "Scores can go below zero. Wrecking your own fruit is sometimes still worth it — to clear " +
                "a path, or to deny a blend that was coming anyway.",
        )
    }
}

@Composable
private fun BlenderCombat() {
    Section(title = "Blender combat") {
        Body(
            "Run your blender into another and it destroys that one outright and takes the square. " +
                "Nobody scores for it, and you get nothing else out of it — no extra move. It works " +
                "on your own blenders too.",
        )
        Body(
            "A blender standing on either of its owner's spawn squares cannot be taken by anything. " +
                "Home is safe, so a blender you have just bought back cannot be knocked straight off " +
                "again — though it is only safe while it stays there.",
        )
        Body(
            "Blender battles are optional — tick the box on the start screen. With them on, a blender " +
                "running into another does not simply take it: both players count half the value of " +
                "the fruit they still have standing on the board, the attacker adds one for going in, " +
                "and each rolls a die. The higher total takes the other player's blender, so an attack " +
                "that goes wrong costs you your own. A tie goes to the defender, so the attacker has " +
                "to actually beat it. Running into one of your own blenders is never a battle.",
        )
        Body(
            "Blenders never come back on their own. Spend a whole turn and three points and a " +
                "destroyed blender returns to its spawn — tap the dashed circle where it used to be. " +
                "Your score may go below zero paying for it.",
        )
    }
}

@Composable
private fun FruitIsTheClock() {
    Section(title = "Fruit is the clock") {
        Body(
            "The match ends the moment either player runs out of fruit on the board — not when the " +
                "board is empty. Whoever is ahead at that moment wins; level scores are a draw.",
        )
        Body(
            "Fruit still standing counts for nothing. Points only exist once a fruit has actually " +
                "scored, so an unplayed pineapple is worth exactly zero.",
        )
        Body(
            "That cuts both ways. Ahead, you may want to run the clock down — even by blending your " +
                "own last fruit at a price. Behind, you need to keep enough on the board to cash in.",
        )
    }
}

// Optional, and described as optional: these only apply if the box on the start screen is ticked.
@Composable
private fun WildcardRules() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = Palette.inkFaint)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Wildcard rules",
                style = RulesType.heading,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text =
                    "Off by default. Tick the box on the start screen to play with them. They exist " +
                        "so a points lead never makes the rest of a match a formality.",
                style = RulesType.detail,
            )
        }

        WildcardRule(
            title = "Golden Pineapple",
            body =
                "You start with two. The moment you are down to one, whichever way the other one " +
                    "went — blended by either of you, or run across for points — the survivor turns " +
                    "golden: three times its value if you get it across, and double to anyone who " +
                    "blends it. Both players can be carrying one.",
        )
        WildcardRule(
            title = "Banana delivery",
            body =
                "A delivered banana scores as usual and then goes back to its own starting square, " +
                    "so it can be run across again. Deliveries add up across the match, and the " +
                    "first player to three wins outright, whatever the score. Blending one of their " +
                    "bananas is what stops it.",
        )
        WildcardRule(
            title = "Tropical Smoothie",
            body =
                "Blend at least one of every fruit type and collect three points. It does not " +
                    "matter whose fruit went in — your own count exactly the same as theirs. It pays " +
                    "once each per match, and both players can see the progress.",
        )
        WildcardRule(
            title = "Blenderama",
            body =
                "Blend every last one of your own fruit yourself and you win outright, whatever the " +
                    "score. It costs you points the whole way, and it only stands as a shutout: the " +
                    "moment your opponent scores at all — a fruit across, or one of yours through " +
                    "their blenders — your Blenderama is off for the rest of the match. Keeping them " +
                    "off the board entirely is the hard part, not the blending. The board says so " +
                    "the moment it happens.",
        )
    }
}

/** One wildcard rule. TalkBack reads the name and its explanation as one item. */
@Composable
private fun WildcardRule(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = title, style = RulesType.bodyStrong)
        Text(text = body, style = RulesType.detail)
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = RulesType.heading, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = RulesType.body)
}

@Composable
private fun RuleRow(
    kind: PieceKind,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        PieceArtwork(kind = kind, seat = PlayerSeat.Bottom, modifier = Modifier.size(40.dp))
        Text(text = text, style = RulesType.body, modifier = Modifier.weight(1f))
    }
}

/** What a fruit is worth, read by TalkBack as "<kind>, blended for X points, delivered for Y". */
@Composable
private fun ValueRow(kind: PieceKind) {
    Row(
        modifier =
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription =
                    "${kind.rawValue}, blended for ${kind.baseValue} points, delivered for ${kind.deliveryValue}"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PieceArtwork(kind = kind, seat = PlayerSeat.Bottom, modifier = Modifier.size(36.dp))
        Text(
            text = kind.rawValue.replaceFirstChar { it.uppercase() },
            style = RulesType.body,
            modifier = Modifier.weight(1f),
        )
        Text(text = "blend ${kind.baseValue} · deliver ${kind.deliveryValue}", style = RulesType.bodySoft)
    }
}

@Preview(widthDp = 360, heightDp = 2600)
@Composable
private fun RulesScreenPreview() {
    PaperTheme {
        RulesScreen(onDone = {})
    }
}
