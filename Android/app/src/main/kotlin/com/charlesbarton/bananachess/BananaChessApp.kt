package com.charlesbarton.bananachess

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.art.isReducedMotion
import com.charlesbarton.bananachess.home.HomeScreen
import com.charlesbarton.bananachess.home.HomeViewModel
import com.charlesbarton.bananachess.match.MatchScreen
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.match.MatchStart
import com.charlesbarton.bananachess.match.MatchViewModel
import com.charlesbarton.bananachess.rules.RulesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Where the app can be. Each route carries what its screen needs, so it outlives the process. */
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data class Match(
        val source: MatchSource,
    ) : Route {
        companion object {
            /** How the [source] argument is written into the back stack. */
            val typeMap: Map<KType, NavType<*>> = mapOf(typeOf<MatchSource>() to MatchSourceNavType)
        }
    }

    @Serializable
    data object Rules : Route

    /** Where "Invite by Link" lands until link play is wired in. */
    @Serializable
    data object Invite : Route
}

/** A [MatchSource] as a navigation argument: JSON in the saved back stack, URL-encoded in the route. */
internal object MatchSourceNavType : NavType<MatchSource>(isNullableAllowed = false) {
    override fun put(
        bundle: Bundle,
        key: String,
        value: MatchSource,
    ) = bundle.putString(key, Json.encodeToString(value))

    override fun get(
        bundle: Bundle,
        key: String,
    ): MatchSource? = bundle.getString(key)?.let(::parseValue)

    // Navigation decodes the route before it parses an argument, so this sees plain JSON.
    override fun parseValue(value: String): MatchSource = Json.decodeFromString(value)

    override fun serializeAsValue(value: MatchSource): String = Uri.encode(Json.encodeToString(value))
}

/**
 * The match view model as the app makes it. Everything a match needs from outside the screen
 * comes from the [container]: S19 passes its CPU player factory and background dispatcher here.
 */
fun newMatchViewModel(
    container: AppContainer,
    start: MatchStart,
    reduceMotion: () -> Boolean,
): MatchViewModel =
    MatchViewModel(
        mode = start.mode,
        initialState = start.state,
        settings = container.settings,
        reduceMotion = reduceMotion,
        scope = CoroutineScope(SupervisorJob() + container.dispatchers.main),
    )

/**
 * The match a [MatchSource] opens, or null when there is none to open. Called once per visit to
 * the Match route, including the visit restored after the process was restarted under it.
 */
private fun matchStartOf(source: MatchSource): MatchStart? =
    when (source) {
        is MatchSource.New -> source.deal()

        // S20 loads the snapshot store's match here (and has a restored New prefer it).
        MatchSource.Saved -> null
    }

/**
 * @param createMatch makes the view model for a match that is starting. Called once per visit
 *   to the Match route; `reduceMotion` reads the system setting each time it is asked.
 */
@Composable
fun BananaChessApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    createMatch: (start: MatchStart, reduceMotion: () -> Boolean) -> MatchViewModel = { start, reduceMotion ->
        newMatchViewModel(container, start, reduceMotion)
    },
) {
    NavHost(navController = navController, startDestination = Route.Home, modifier = modifier) {
        composable<Route.Home> {
            val homeViewModel: HomeViewModel =
                viewModel(factory = viewModelFactory { initializer { HomeViewModel(container.settings) } })
            HomeScreen(
                viewModel = homeViewModel,
                onStartMatch = { source -> navController.navigate(Route.Match(source)) { launchSingleTop = true } },
                onInvite = { navController.navigate(Route.Invite) { launchSingleTop = true } },
                onShowRules = { navController.navigate(Route.Rules) { launchSingleTop = true } },
            )
        }
        composable<Route.Match>(typeMap = Route.Match.typeMap) { entry ->
            val source = remember(entry) { entry.toRoute<Route.Match>().source }
            val start = remember(source) { matchStartOf(source) }
            if (start == null) {
                // Nothing to open. Go back to where a match can be started.
                LaunchedEffect(Unit) { navController.popBackStack<Route.Match>(inclusive = true) }
            } else {
                val context = LocalContext.current.applicationContext
                // Scoped to this back-stack entry: a rotation keeps the match, leaving ends it.
                val matchViewModel: MatchViewModel =
                    viewModel(
                        factory = viewModelFactory { initializer { createMatch(start) { isReducedMotion(context) } } },
                    )
                MatchScreen(
                    viewModel = matchViewModel,
                    // By type, so a second press during the way out cannot pop Home too.
                    onExit = { navController.popBackStack<Route.Match>(inclusive = true) },
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
