package com.charlesbarton.bananachess

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.charlesbarton.bananachess.art.PaperTheme

class MainActivity : ComponentActivity() {
    private val graph: AppGraph by viewModels { AppGraph.factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The paper runs under the system bars. It is always light, so the bar icons are always dark.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        setContent {
            PaperTheme {
                BananaChessApp(graph = graph)
            }
        }
    }
}
