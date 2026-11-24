package com.charlesbarton.bananachess.match

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperButton

/** Asked before the board and both scores are thrown away. Back, or a tap outside, keeps playing. */
@Composable
fun RestartDialog(
    onRestart: () -> Unit,
    onKeepPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperAlertDialog(
        title = "Restart this match?",
        message = "The current board and both scores will be cleared.",
        confirmLabel = "Restart",
        onConfirm = onRestart,
        onDismiss = onKeepPlaying,
        modifier = modifier,
        dismissLabel = "Keep playing",
    )
}

/** Blending your own fruit costs points and cannot be taken back, so it asks first. */
@Composable
fun SelfBlendDialog(
    pending: PendingSelfBlend,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperAlertDialog(
        title = pending.prompt,
        message = pending.detail,
        confirmLabel = "Blend it",
        onConfirm = onConfirm,
        onDismiss = onCancel,
        modifier = modifier,
        dismissLabel = "Pick something else",
    )
}

/**
 * The app's one alert: a question on cream paper with paper buttons. [onDismiss] is also what
 * back and a tap outside do. Without a [dismissLabel] it is a notice with a single button.
 */
@Composable
internal fun PaperAlertDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PaperButton(text = confirmLabel, onClick = onConfirm, isPrimary = true) },
        modifier = modifier,
        dismissButton = dismissLabel?.let { label -> { PaperButton(text = label, onClick = onDismiss) } },
        title = { Text(text = title, style = MatchType.dialogTitle) },
        text = { Text(text = message, style = MatchType.dialogMessage) },
        containerColor = Palette.cream,
        titleContentColor = Palette.ink,
        textContentColor = Palette.ink,
    )
}
