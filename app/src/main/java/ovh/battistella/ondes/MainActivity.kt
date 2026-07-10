package ovh.battistella.ondes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.data.settings.ThemeMode
import ovh.battistella.ondes.sync.NewEpisodeNotifier
import ovh.battistella.ondes.ui.navigation.OndesRoot
import ovh.battistella.ondes.ui.screens.onboarding.OnboardingScreen
import ovh.battistella.ondes.ui.theme.OndesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    // Feed URL to open from a tapped "new episode" notification, if any.
    private var pendingFeedUrl by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        pendingFeedUrl = intent?.getStringExtra(NewEpisodeNotifier.EXTRA_OPEN_FEED_URL)
        // Consume the extra so a config-change recreation (rotation) doesn't
        // re-read it and push a duplicate podcast onto the back stack (issue P1-15).
        intent?.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_FEED_URL)
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val showOnboarding by mainViewModel.showOnboarding.collectAsStateWithLifecycle()
            OndesTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (showOnboarding) {
                        // null = preference still loading; show nothing briefly.
                        null -> Unit
                        true -> OnboardingScreen()
                        false -> OndesRoot(
                            deepLinkFeedUrl = pendingFeedUrl,
                            onDeepLinkConsumed = { pendingFeedUrl = null },
                        )
                    }
                }
            }
        }
    }

    // The activity is singleTop, so a notification tap while it's already running
    // arrives here rather than recreating it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(NewEpisodeNotifier.EXTRA_OPEN_FEED_URL)?.let { pendingFeedUrl = it }
        // Consumed — don't let it survive to a later recreation and re-fire.
        intent.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_FEED_URL)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
