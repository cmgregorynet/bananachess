package com.charlesbarton.bananachess

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.charlesbarton.bananachess.art.PaperBackground
import com.charlesbarton.bananachess.art.isReducedMotion
import com.charlesbarton.bananachess.home.HomeScreen
import com.charlesbarton.bananachess.home.HomeViewModel
import com.charlesbarton.bananachess.link.LinkPlay
import com.charlesbarton.bananachess.match.MatchScreen
import com.charlesbarton.bananachess.match.MatchSnapshotStore
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.match.MatchStart
import com.charlesbarton.bananachess.match.MatchViewModel
import com.charlesbarton.bananachess.match.PaperAlertDialog
import com.charlesbarton.bananachess.rules.RulesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
 * comes from the [container], the computer and the thread it thinks on included.
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
        cpuDispatcher = container.dispatchers.default,
        cpuOpponent = container.cpuOpponent,
        snapshots = container.snapshots,
        lastMove = start.lastMove,
        animateFrom = start.animateFrom,
    )

/**
 * Decides which match a visit to the Match route opens. Made once per back-stack entry, so a
 * rotation keeps its answer; after the process was restarted under the entry it is made again
 * and decides again.
 *
 * A new match is dealt from its [MatchSource.New] the first time. Every other time, and always
 * for [MatchSource.Saved], the saved match is preferred: once a match has been played on, the
 * snapshot is where it is, and dealing the source again would put it back to its opening. A
 * restored new match with nothing saved is dealt again; "Resume match" with nothing saved opens
 * nothing.
 *
 * A link match always reads the saved match first: [LinkPlay.start] prefers it when it is the same
 * match at least as far on, and otherwise uses it to show the link's last move being played.
 */
internal class MatchOpening(
    source: MatchSource,
    handle: SavedStateHandle,
    snapshots: MatchSnapshotStore,
    scope: CoroutineScope,
) : ViewModel(scope) {
    sealed interface State {
        /** Waiting for the saved match to be read. */
        data object Loading : State

        /** Nothing to open: the route goes back to where a match can be started. */
        data object NoMatch : State

        data class Ready(
            val start: MatchStart,
        ) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = mutableState.asStateFlow()

    init {
        // Kept in the entry's saved state, which is all that outlives the process.
        val openedBefore = handle.get<Boolean>(OPENED) == true
        handle[OPENED] = true
        if (source is MatchSource.New && !openedBefore) {
            mutableState.value = State.Ready(source.deal())
        } else if (source is MatchSource.ByLink) {
            viewModelScope.launch {
                val start = LinkPlay.start(source, snapshots.load())
                mutableState.value = start?.let(State::Ready) ?: State.NoMatch
            }
        } else {
            viewModelScope.launch {
                val saved = snapshots.load()?.let { MatchStart(it.mode, it.state) }
                val start = saved ?: (source as? MatchSource.New)?.deal()
                mutableState.value = start?.let(State::Ready) ?: State.NoMatch
            }
        }
    }

    private companion object {
        const val OPENED = "opened"
    }
}

/**
 * @param incomingLinks match links handed to the app, each once, as they arrive. Opened from
 *   wherever the app is: a link that cannot be opened is explained over the current screen and
 *   leaves it alone; one that can replaces whatever is showing with its match.
 * @param createMatch makes the view model for a match that is starting. Called once per visit
 *   to the Match route; `reduceMotion` reads the system setting each time it is asked.
 */
@Composable
fun BananaChessApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    incomingLinks: Flow<String> = emptyFlow(),
    createMatch: (start: MatchStart, reduceMotion: () -> Boolean) -> MatchViewModel = { start, reduceMotion ->
        newMatchViewModel(container, start, reduceMotion)
    },
) {
    // Above the navigation rather than on Home's entry, because a link can arrive on any screen.
    // Home is the bottom of the back stack and never leaves it, so this lives exactly as long.
    val homeViewModel: HomeViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { HomeViewModel(container.settings, snapshots = container.snapshots) }
                },
        )
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(incomingLinks) { incomingLinks.collect(homeViewModel::open) }

    // An accepted link replaces whatever is showing. The match view model belongs to its
    // back-stack entry, so the old match is popped and the new one is a new entry.
    val linkToOpen = homeState.linkToOpen
    LaunchedEffect(linkToOpen) {
        if (linkToOpen == null) return@LaunchedEffect
        homeViewModel.linkOpened()
        navController.navigate(Route.Match(linkToOpen)) { popUpTo<Route.Home>() }
    }

    NavHost(navController = navController, startDestination = Route.Home, modifier = modifier) {
        composable<Route.Home> {
            HomeScreen(
                viewModel = homeViewModel,
                onStartMatch = { source -> navController.navigate(Route.Match(source)) { launchSingleTop = true } },
                onShowRules = { navController.navigate(Route.Rules) { launchSingleTop = true } },
            )
        }
        composable<Route.Match>(typeMap = Route.Match.typeMap) { entry ->
            val opening: MatchOpening =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                MatchOpening(
                                    source = entry.toRoute<Route.Match>().source,
                                    handle = createSavedStateHandle(),
                                    snapshots = container.snapshots,
                                    scope = CoroutineScope(SupervisorJob() + container.dispatchers.main),
                                )
                            }
                        },
                )
            val openingState by opening.state.collectAsStateWithLifecycle()
            when (val current = openingState) {
                // A file read: too short to say anything, so just the paper.
                MatchOpening.State.Loading -> {
                    PaperBackground(modifier = Modifier.fillMaxSize())
                }

                // Go back to where a match can be started.
                MatchOpening.State.NoMatch -> {
                    LaunchedEffect(Unit) { navController.popBackStack<Route.Match>(inclusive = true) }
                }

                is MatchOpening.State.Ready -> {
                    val context = LocalContext.current.applicationContext
                    // Scoped to this back-stack entry: a rotation keeps the match, leaving ends it.
                    val matchViewModel: MatchViewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                    initializer { createMatch(current.start) { isReducedMotion(context) } }
                                },
                        )
                    MatchScreen(
                        viewModel = matchViewModel,
                        // By type, so a second press during the way out cannot pop Home too.
                        onExit = { navController.popBackStack<Route.Match>(inclusive = true) },
                        onShowRules = { navController.navigate(Route.Rules) { launchSingleTop = true } },
                    )
                }
            }
        }
        composable<Route.Rules> {
            // Pops Rules by name, so a second press of "Done" during the way out cannot pop Home too.
            RulesScreen(onDone = { navController.popBackStack(Route.Rules, inclusive = true) })
        }
    }

    // A window of its own, so it sits over whichever screen is showing and changes nothing on it.
    homeState.linkError?.let { error ->
        PaperAlertDialog(
            title = "Can't open that match",
            message = error.message,
            confirmLabel = "OK",
            onConfirm = homeViewModel::dismissLinkError,
            onDismiss = homeViewModel::dismissLinkError,
        )
    }
}
