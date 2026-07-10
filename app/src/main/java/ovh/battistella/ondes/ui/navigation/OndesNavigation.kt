package ovh.battistella.ondes.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ovh.battistella.ondes.ui.components.MiniPlayer
import ovh.battistella.ondes.ui.screens.home.HomeScreen
import ovh.battistella.ondes.ui.screens.library.LibraryScreen
import ovh.battistella.ondes.ui.screens.player.PlayerScreen
import ovh.battistella.ondes.ui.screens.player.PlayerViewModel
import ovh.battistella.ondes.ui.screens.podcast.PodcastScreen
import ovh.battistella.ondes.ui.screens.queue.QueueScreen
import ovh.battistella.ondes.ui.screens.search.SearchScreen
import ovh.battistella.ondes.ui.screens.settings.SettingsScreen

@Composable
fun OndesRoot(
    deepLinkFeedUrl: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Open the show page when launched from a "new episode" notification.
    LaunchedEffect(deepLinkFeedUrl) {
        val feedUrl = deepLinkFeedUrl ?: return@LaunchedEffect
        navController.navigate(Routes.podcast(feedUrl)) { launchSingleTop = true }
        onDeepLinkConsumed()
    }

    val isPlayer = currentRoute == Routes.PLAYER
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    // A single PlayerViewModel scoped to the root drives the mini-player.
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.playerState.collectAsStateWithLifecycle()

    // A single shared snackbar host renders transient messages — including the
    // "Undo" actions behind reversible deletes — from anywhere in the app.
    val rootViewModel: OndesRootViewModel = hiltViewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        rootViewModel.snackbar.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                // Undo prompts stay long enough to actually be tapped.
                duration = if (message.isUndo) SnackbarDuration.Long else SnackbarDuration.Short,
                withDismissAction = message.actionLabel == null,
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }

    // Switch to the Search tab (used by empty-state "browse" CTAs), reusing the
    // same back-stack behaviour as a bottom-bar tap.
    val openSearch: () -> Unit = {
        navController.navigate(Routes.SEARCH) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // Also clear a display cutout so list content isn't drawn under a side
        // notch when the phone is held in landscape (edge-to-edge is on).
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(visible = !isPlayer) {
                Column {
                    // When the bottom bar is hidden (a pushed screen like Settings),
                    // the mini-player itself sits at the very bottom and must clear
                    // the system navigation bar; the NavigationBar handles that inset
                    // otherwise.
                    MiniPlayer(
                        state = playerState,
                        onPlayPause = playerViewModel::playPause,
                        onForward = playerViewModel::seekForward,
                        onClick = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                        onStop = playerViewModel::stop,
                        modifier = if (isTopLevel) Modifier else Modifier.navigationBarsPadding(),
                    )
                    if (isTopLevel) {
                        BottomBar(navController, backStackEntry?.destination)
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            // Quiet cross-fade between the main destinations; the spring-based
            // defaults pick up the expressive motion scheme from the theme.
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    onBrowse = openSearch,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    contentPadding = innerPadding,
                )
            }
            composable(Routes.QUEUE) {
                QueueScreen(
                    onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    onBrowse = openSearch,
                    contentPadding = innerPadding,
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenPodcast = { navController.navigate(Routes.podcast(it)) { launchSingleTop = true } },
                    onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    onBrowse = openSearch,
                    contentPadding = innerPadding,
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    contentPadding = innerPadding,
                    onOpenPodcast = { navController.navigate(Routes.podcast(it)) { launchSingleTop = true } },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    contentPadding = innerPadding,
                )
            }
            composable(Routes.PODCAST) {
                PodcastScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    // The root's inset (mini-player + nav bar) must reach the list
                    // or the last episode rows sit hidden behind the mini-player.
                    contentPadding = innerPadding,
                )
            }
            // The player is a "now playing" surface — slide it up from the bottom.
            composable(
                Routes.PLAYER,
                enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
            ) {
                PlayerScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomBar(
    navController: androidx.navigation.NavHostController,
    currentDestination: androidx.navigation.NavDestination?,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { dest ->
            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                // Label is shown as text below; null avoids TalkBack reading it twice.
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(stringResource(dest.labelRes)) },
            )
        }
    }
}
