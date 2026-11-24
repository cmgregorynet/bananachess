package com.charlesbarton.bananachess

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.charlesbarton.bananachess.art.PaperTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The one activity. `singleTop`, so a match link tapped while the app is open arrives in
 * [onNewIntent] rather than stacking a second copy of the app.
 */
class MainActivity : ComponentActivity() {
    // Buffered, so the link that launched the app waits here until the screen is there to open it.
    private val incomingLinks = Channel<String>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The paper runs under the system bars. It is always light, so the bar icons are always dark.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        // Saved state means this activity has been here before, after a rotation or a process
        // restart, and the intent it carries was opened then. Opening it again would reopen an
        // old link, so only a first creation reads it.
        if (savedInstanceState == null) receive(intent)
        val container = (application as BananaChessApplication).container
        val links = incomingLinks.receiveAsFlow()
        setContent {
            PaperTheme {
                BananaChessApp(container = container, incomingLinks = links)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        intent ?: return
        // Reopened from Recents, the activity is handed the intent it was first started with.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        incomingLinks.trySend(link)
    }
}
