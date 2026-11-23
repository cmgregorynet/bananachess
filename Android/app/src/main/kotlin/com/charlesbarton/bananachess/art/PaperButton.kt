package com.charlesbarton.bananachess.art

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Warm paper, ink serif text, an amber frame when it is the one thing to press. No glossy or
 * plastic controls anywhere in the app.
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
) {
    PaperButton(onClick = onClick, modifier = modifier, isPrimary = isPrimary, enabled = enabled) {
        Text(text = text)
    }
}

/** The same button with a free-form label, for a title with an icon or a second line. */
@Composable
fun PaperButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val press = tween<Float>(durationMillis = PRESS_MILLIS, easing = EaseOut)
    val restingAlpha = if (enabled) 1f else DISABLED_ALPHA
    val alpha by animateFloatAsState(if (isPressed) PRESSED_ALPHA else restingAlpha, press, label = "alpha")
    val scale by animateFloatAsState(if (isPressed) PRESSED_SCALE else 1f, press, label = "scale")

    Row(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(if (isPrimary) Palette.amber.copy(alpha = 0.35f) else Palette.cream)
                .border(
                    width = if (isPrimary) 2.dp else 1.2.dp,
                    color = if (isPrimary) Palette.amber else Palette.inkSoft,
                    shape = CircleShape,
                )
                // The press is shown by the fade and the shrink, as on paper; no ripple.
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Palette.ink,
            LocalTextStyle provides PaperTypography.labelLarge,
        ) {
            content()
        }
    }
}

private const val PRESS_MILLIS = 120
private const val PRESSED_ALPHA = 0.7f
private const val PRESSED_SCALE = 0.97f
private const val DISABLED_ALPHA = 0.45f

@Preview
@Composable
private fun PaperButtonPreview() {
    PaperTheme {
        PaperBackground(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PaperButton(text = "Play again", onClick = {}, isPrimary = true)
                PaperButton(text = "Done", onClick = {})
                PaperButton(text = "Send link", onClick = {}, enabled = false)
            }
        }
    }
}
