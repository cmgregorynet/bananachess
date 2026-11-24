package com.charlesbarton.bananachess.link

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.charlesbarton.bananachess.art.PaperButton

object LinkTags {
    const val SEND = "sendLink"
}

/**
 * In link play the share sheet is the whole transport, so sending is a real control of its own
 * rather than a corner icon: there is nothing else the player can do until the link has gone.
 * Never shows the link itself, which runs to thousands of characters.
 */
@Composable
fun LinkHandoffButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(LinkTags.SEND),
        isPrimary = true,
    ) {
        Text(text = title, fontWeight = FontWeight.SemiBold)
    }
}

/** Handing a link to whichever app the player sends it with. */
object LinkShare {
    /** The system share sheet for [link], with [message] above it. */
    fun intent(
        link: String,
        message: String,
    ): Intent {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "$message\n$link")
        return Intent.createChooser(send, null)
    }

    /**
     * Opens the share sheet from [context], which should be the activity's. When nothing can
     * show it the link goes on the clipboard instead, so it can still be pasted somewhere.
     *
     * @return false when the share sheet could not be opened and the link was copied.
     */
    fun send(
        context: Context,
        link: String,
        message: String,
    ): Boolean =
        try {
            context.startActivity(intent(link, message))
            true
        } catch (_: ActivityNotFoundException) {
            context
                .getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, link))
            false
        }

    private const val CLIP_LABEL = "Banana Chess match"
}
