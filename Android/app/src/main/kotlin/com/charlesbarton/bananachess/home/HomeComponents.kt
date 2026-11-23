package com.charlesbarton.bananachess.home

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.WashiPanel

/** The start screen's type, in the platform serif. Sizes are sp, so they follow the system font size. */
internal object HomeType {
    private val serif = TextStyle(fontFamily = FontFamily.Serif, color = Palette.ink)

    val title = serif.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
    val subtitle = serif.copy(fontSize = 15.sp, lineHeight = 20.sp, color = Palette.inkSoft)
    val menuTitle = serif.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val menuDetail = serif.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.inkSoft)
    val optionTitle = serif.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val optionButton = serif.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val blurb = serif.copy(fontSize = 11.sp, lineHeight = 15.sp, color = Palette.inkSoft)
    val sheetTitle = serif.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val sheetBlurb = serif.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.inkSoft)
    val body = serif.copy(fontSize = 17.sp, lineHeight = 22.sp)
}

// A press shows as a fade, as on paper; there is no ripple anywhere in the app.
@Composable
private fun Modifier.pressFade(interactionSource: MutableInteractionSource): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) PRESSED_ALPHA else 1f,
        animationSpec = tween(durationMillis = PRESS_MILLIS, easing = EaseOut),
        label = "pressFade",
    )
    return graphicsLayer { this.alpha = alpha }
}

private const val PRESS_MILLIS = 120
private const val PRESSED_ALPHA = 0.7f

/** One way to play. TalkBack reads it as "<title>. <detail>". */
@Composable
internal fun MenuButton(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    WashiPanel(
        modifier =
            modifier
                .fillMaxWidth()
                .pressFade(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = "$title. $detail" },
        cornerRadius = 18.dp,
        isAccented = isPrimary,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = HomeType.menuTitle)
            Text(text = detail, style = HomeType.menuDetail)
        }
    }
}

/**
 * A rules opt-in. A checkbox rather than a picker: standard is the game, and each of these is the
 * game plus something, which a tick reads as.
 */
@Composable
internal fun RulesCheckbox(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    WashiPanel(
        modifier =
            modifier
                .fillMaxWidth()
                .pressFade(interactionSource)
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ).semantics { contentDescription = "$title. $detail" },
        cornerRadius = 14.dp,
        isAccented = checked,
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckboxMark(checked = checked)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = title, style = HomeType.optionTitle)
                Text(text = detail, style = HomeType.blurb)
            }
        }
    }
}

// Drawn, not clipped: a clip would put a second node in the accessibility tree.
@Composable
private fun CheckboxMark(checked: Boolean) {
    Box(
        modifier =
            Modifier.size(22.dp).drawBehind {
                val corner = CornerRadius(5.dp.toPx())
                val border = (if (checked) 2.dp else 1.4.dp).toPx()
                if (checked) drawRoundRect(color = Palette.amber.copy(alpha = 0.28f), cornerRadius = corner)
                drawRoundRect(
                    color = if (checked) Palette.amber else Palette.inkSoft,
                    topLeft = Offset(border / 2f, border / 2f),
                    size = size.copy(width = size.width - border, height = size.height - border),
                    cornerRadius = corner,
                    style = Stroke(width = border),
                )
                if (checked) {
                    val tick =
                        Path().apply {
                            moveTo(size.width * 0.27f, size.height * 0.52f)
                            lineTo(size.width * 0.44f, size.height * 0.69f)
                            lineTo(size.width * 0.74f, size.height * 0.33f)
                        }
                    drawPath(
                        path = tick,
                        color = Palette.ink,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            },
    )
}

/**
 * A one-of-several choice as a row of capsules, with a sentence under it that follows the
 * selection: "Random" and "All" mean nothing without one.
 *
 * Each capsule reads as "<name>. <description>" and reports whether it is the selected one.
 */
@Composable
internal fun <T> OptionRow(
    title: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    blurb: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = HomeType.optionTitle,
    blurbStyle: TextStyle = HomeType.blurb,
) {
    Column(modifier = modifier) {
        Text(text = title, style = titleStyle)
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in options) {
                OptionCapsule(
                    name = name(option),
                    description = blurb(option),
                    isSelected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(text = blurb(selected), style = blurbStyle)
    }
}

@Composable
private fun OptionCapsule(
    name: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .pressFade(interactionSource)
                .selectable(
                    selected = isSelected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics { contentDescription = "$name. $description" }
                // The touch target is a full 48 dp; the capsule drawn inside it is smaller.
                .minimumInteractiveComponentSize()
                .fillMaxWidth()
                .drawBehind {
                    val corner = CornerRadius(size.height / 2f)
                    val border = (if (isSelected) 2.dp else 1.dp).toPx()
                    if (isSelected) drawRoundRect(color = Palette.amber.copy(alpha = 0.28f), cornerRadius = corner)
                    drawRoundRect(
                        color = if (isSelected) Palette.amber else Palette.inkFaint,
                        topLeft = Offset(border / 2f, border / 2f),
                        size = size.copy(width = size.width - border, height = size.height - border),
                        cornerRadius = corner,
                        style = Stroke(width = border),
                    )
                }.padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = name, style = HomeType.optionButton, textAlign = TextAlign.Center, maxLines = 1)
    }
}
