package com.charlesbarton.bananachess

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.isReducedMotion
import com.charlesbarton.bananachess.home.HomeScreen
import com.charlesbarton.bananachess.home.HomeViewModel
import com.charlesbarton.bananachess.match.MatchScreen
import com.charlesbarton.bananachess.match.MatchStart
import com.charlesbarton.bananachess.match.MatchViewModel
import com.charlesbarton.bananachess.rules.RulesScreen
import com.charlesbarton.bananachess.settings.DataStoreSettingsStore
import com.charlesbarton.bananachess.settings.SettingsStore
import kotlinx.serialization.Serializable

/** Where the app can be. Routes carry no arguments: a match start travels in [AppGraph.matchStart]. */
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data object Match : Route

    @Serializable
    data object Rules : Route

    /** Where "Invite by Link" lands until link play is wired in. */
    @Serializable
    data object Invite : Route
}

/** Hands a match from the screen that starts it to the Match route, in memory. */
class MatchStartHolder {
    /** The match the Match route should open. Null after the process was restarted under it. */
    var current: MatchStart? = null
}

/**
 * The objects shared by every screen. Owned by the activity as a view model, so it outlives a
 * configuration change and nothing else.
 */
class AppGraph(
    val settings: SettingsStore,
) : ViewModel() {
    val matchStart = MatchStartHolder()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { AppGraph(settings = DataStoreSettingsStore.get(appContext)) }
            }
        }
    }
}

/**
 * The match view model as the app makes it. Everything a match needs from outside the screen
 * (haptics, the CPU, the saved match) is handed over here.
 */
fun newMatchViewModel(
    start: MatchStart,
    reduceMotion: () -> Boolean,
): MatchViewModel = MatchViewModel(mode = start.mode, initialState = start.state, reduceMotion = reduceMotion)

/**
 * @param createMatch makes the view model for a match that is starting. Called once per visit
 *   to the Match route; `reduceMotion` reads the system setting each time it is asked.
 */
@Composable
fun BananaChessApp(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    createMatch: (start: MatchStart, reduceMotion: () -> Boolean) -> MatchViewModel = ::newMatchViewModel,
) {
    NavHost(navController = navController, startDestination = Route.Home, modifier = modifier) {
        composable<Route.Home> {
            val homeViewModel: HomeViewModel =
                viewModel(factory = viewModelFactory { initializer { HomeViewModel(graph.settings) } })
            HomeScreen(
                viewModel = homeViewModel,
                onStartMatch = { start ->
                    graph.matchStart.current = start
                    navController.navigate(Route.Match) { launchSingleTop = true }
                },
                onInvite = { navController.navigate(Route.Invite) { launchSingleTop = true } },
                onShowRules = { navController.navigate(Route.Rules) { launchSingleTop = true } },
            )
        }
        composable<Route.Match> {
            val start = graph.matchStart.current
            if (start == null) {
                // The process was restarted under the match and the match went with it. There
                // is nothing to show, so go back to where one can be started.
                LaunchedEffect(Unit) { navController.popBackStack(Route.Match, inclusive = true) }
            } else {
                val context = LocalContext.current.applicationContext
                // Scoped to this back-stack entry: a rotation keeps the match, leaving ends it.
                val matchViewModel: MatchViewModel =
                    viewModel(
                        factory = viewModelFactory { initializer { createMatch(start) { isReducedMotion(context) } } },
                    )
                MatchScreen(
                    viewModel = matchViewModel,
                    settings = graph.settings,
                    // By name, so a second press during the way out cannot pop Home too.
                    onExit = { navController.popBackStack(Route.Match, inclusive = true) },
                    onShowRules = { navController.navigate(Route.Rules) { launchSingleTop = true } },
                )
            }
        }
        composable<Route.Rules> {
            // Pops Rules by name, so a second press of "Done" during the way out cannot pop Home too.
            RulesScreen(onDone = { navController.popBackStack(Route.Rules, inclusive = true) })
        }
        composable<Route.Invite> {
            PlaceholderScreen(
                title = "Invite by Link",
                detail = "Link play is on its way.",
                tag = PlaceholderTags.INVITE,
                onBack = navController::popBackStack,
            )
        }
    }
}

/** Test tags of the stand-in screens. Each goes away with the screen it stands in for. */
object PlaceholderTags {
    const val INVITE = "invitePlaceholder"
}

@Composable
private fun PlaceholderScreen(
    title: String,
    detail: String,
    tag: String,
    onBack: () -> Unit,
) {
    PaperBackground(modifier = Modifier.testTag(tag), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
            PaperButton(text = "Back", onClick = { onBack() })
        }
    }
}
