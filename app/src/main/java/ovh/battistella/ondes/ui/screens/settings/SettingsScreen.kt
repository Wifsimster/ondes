package ovh.battistella.ondes.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.BuildConfig
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastRefreshAt by viewModel.lastRefreshAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsAllowed = rememberNotificationsAllowed(context)
    val batteryUnrestricted = rememberBatteryUnrestricted(context)

    // Surface one-shot results of data operations (export/import) as toasts.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Storage Access Framework pickers — files stay under the user's control.
    val exportOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri -> uri?.let(viewModel::exportOpml) }
    val importOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importOpml) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
    ) {
        SectionHeader(stringResource(R.string.settings_playback))
        ChoiceRow(
            title = stringResource(R.string.skip_back),
            current = secondsLabel(context, settings.skipBackMs),
            options = SKIP_BACK_OPTIONS.map { secondsLabel(context, it) },
            onSelect = { viewModel.setSkipBack(SKIP_BACK_OPTIONS[it]) },
        )
        ChoiceRow(
            title = stringResource(R.string.skip_forward),
            current = secondsLabel(context, settings.skipForwardMs),
            options = SKIP_FORWARD_OPTIONS.map { secondsLabel(context, it) },
            onSelect = { viewModel.setSkipForward(SKIP_FORWARD_OPTIONS[it]) },
        )
        SwitchRow(
            title = stringResource(R.string.autoplay_next_title),
            subtitle = stringResource(R.string.autoplay_next_subtitle),
            checked = settings.autoAdvance,
            onCheckedChange = viewModel::setAutoAdvance,
        )
        SwitchRow(
            title = stringResource(R.string.skip_silence_title),
            subtitle = stringResource(R.string.skip_silence_subtitle),
            checked = settings.skipSilence,
            onCheckedChange = viewModel::setSkipSilence,
        )
        SwitchRow(
            title = stringResource(R.string.boost_volume_title),
            subtitle = stringResource(R.string.boost_volume_subtitle),
            checked = settings.boostVolume,
            onCheckedChange = viewModel::setBoostVolume,
        )

        SectionHeader(stringResource(R.string.settings_downloads))
        SwitchRow(
            title = stringResource(R.string.wifi_only_title),
            subtitle = stringResource(R.string.wifi_only_subtitle),
            checked = settings.wifiOnlyDownloads,
            onCheckedChange = viewModel::setWifiOnlyDownloads,
        )
        SwitchRow(
            title = stringResource(R.string.delete_finished_title),
            subtitle = stringResource(R.string.delete_finished_subtitle),
            checked = settings.autoDeleteFinished,
            onCheckedChange = viewModel::setAutoDeleteFinished,
        )

        SectionHeader(stringResource(R.string.settings_updates))
        SwitchRow(
            title = stringResource(R.string.bg_refresh_title),
            subtitle = stringResource(R.string.bg_refresh_subtitle),
            checked = settings.backgroundRefresh,
            onCheckedChange = viewModel::setBackgroundRefresh,
        )
        if (settings.backgroundRefresh) {
            // A background refresh the system has quietly stopped running looks
            // exactly like one that is working — until you notice the app only
            // ever finds new episodes while you are looking at it.
            InfoRow(
                title = stringResource(R.string.last_checked_title),
                value = lastCheckedLabel(context, lastRefreshAt),
            )
            // Doze and the OEM battery managers are the usual reason nothing
            // arrives until the app is opened: a job from an app you haven't
            // touched gets a handful of windows a day, or none at all.
            if (!batteryUnrestricted) {
                ActionRow(
                    title = stringResource(R.string.battery_limited_title),
                    subtitle = stringResource(R.string.battery_limited_subtitle),
                    onClick = { openBatterySettings(context) },
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.new_episode_notifs_title),
            subtitle = stringResource(R.string.new_episode_notifs_subtitle),
            checked = settings.newEpisodeNotifications,
            onCheckedChange = viewModel::setNewEpisodeNotifications,
        )
        // The switch above only records a preference: if the OS permission was
        // denied, nothing is ever posted and the switch quietly lied about it
        // (issue P2). Say so, and offer the one place that can fix it.
        if (settings.newEpisodeNotifications && !notificationsAllowed) {
            ActionRow(
                title = stringResource(R.string.notifs_blocked_title),
                subtitle = stringResource(R.string.notifs_blocked_subtitle),
                onClick = { context.startActivity(appNotificationSettingsIntent(context)) },
            )
        }

        SectionHeader(stringResource(R.string.settings_data))
        ActionRow(
            title = stringResource(R.string.export_opml_title),
            subtitle = stringResource(R.string.export_opml_subtitle),
            onClick = { exportOpmlLauncher.launch("ondes-subscriptions.opml") },
        )
        ActionRow(
            title = stringResource(R.string.import_opml_title),
            subtitle = stringResource(R.string.import_opml_subtitle),
            onClick = { importOpmlLauncher.launch(arrayOf("*/*")) },
        )
        ActionRow(
            title = stringResource(R.string.export_backup_title),
            subtitle = stringResource(R.string.export_backup_subtitle),
            onClick = { exportBackupLauncher.launch("ondes-backup.json") },
        )
        ActionRow(
            title = stringResource(R.string.import_backup_title),
            subtitle = stringResource(R.string.import_backup_subtitle),
            onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
        )

        SectionHeader(stringResource(R.string.settings_appearance))
        ChoiceRow(
            title = stringResource(R.string.theme),
            current = themeLabel(context, settings.themeMode),
            options = ThemeMode.entries.map { themeLabel(context, it) },
            onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) },
        )
        SwitchRow(
            title = stringResource(R.string.dynamic_color_title),
            subtitle = stringResource(R.string.dynamic_color_subtitle),
            checked = settings.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )

        SectionHeader(stringResource(R.string.settings_about))
        InfoRow(
            title = stringResource(R.string.version),
            value = BuildConfig.VERSION_NAME,
        )

        Spacer(Modifier.padding(16.dp))
    }
    }
}

/**
 * Whether the app may actually post notifications, re-checked whenever Settings
 * comes back to the foreground — the user may have just changed it in the
 * system settings we sent them to.
 */
@Composable
private fun rememberNotificationsAllowed(context: android.content.Context): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

/**
 * Whether the app is exempt from battery optimisation, re-checked when Settings
 * comes back to the foreground so a change made in the system screen shows up
 * immediately.
 *
 * Only the exemption is readable. The OEM "app sleep" lists layered on top of it
 * are not, so a `true` here means "Android itself is not holding the refresh
 * back" rather than "the refresh is guaranteed to run".
 */
@Composable
private fun rememberBatteryUnrestricted(context: android.content.Context): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return unrestricted
}

private fun isBatteryUnrestricted(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

/**
 * Send the user to the system list of battery-optimised apps, falling back to
 * this app's details screen (which every device has) if that list isn't offered.
 *
 * Deliberately *not* the one-tap `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * dialog: that needs a permission Play restricts to a short list of app
 * categories a podcast player is not on. Neither intent here needs a permission.
 * Both are tried rather than resolved first — package-visibility filtering can
 * hide the Settings app from resolveActivity even where the screen exists.
 */
private fun openBatterySettings(context: android.content.Context) {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val batteryList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in listOf(batteryList, appDetails)) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

/** "Never", or how long ago the last successful background refresh finished. */
private fun lastCheckedLabel(context: android.content.Context, at: Long): String =
    if (at <= 0L) {
        context.getString(R.string.last_checked_never)
    } else {
        DateUtils.getRelativeTimeSpanString(
            at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

/** The system screen where app notifications can be re-enabled. */
private fun appNotificationSettingsIntent(context: android.content.Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun InfoRow(
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun ChoiceRow(
    title: String,
    current: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { open = true }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSelect(index); open = false },
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

private val SKIP_BACK_OPTIONS = listOf(5_000L, 10_000L, 15_000L, 30_000L)
private val SKIP_FORWARD_OPTIONS = listOf(10_000L, 15_000L, 30_000L, 45_000L, 60_000L)

private fun secondsLabel(context: android.content.Context, ms: Long): String =
    context.getString(R.string.seconds_short, (ms / 1000).toInt())

private fun themeLabel(context: android.content.Context, mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> context.getString(R.string.theme_system)
    ThemeMode.LIGHT -> context.getString(R.string.theme_light)
    ThemeMode.DARK -> context.getString(R.string.theme_dark)
}
