package ovh.battistella.ondes.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Which color scheme to render, independent of the system setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A snapshot of every user-configurable preference in the app. */
data class OndesSettings(
    val skipBackMs: Long = 10_000L,
    val skipForwardMs: Long = 30_000L,
    val defaultSpeed: Float = 1f,
    val autoAdvance: Boolean = true,
    val skipSilence: Boolean = false,
    val boostVolume: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val autoDeleteFinished: Boolean = false,
    val backgroundRefresh: Boolean = true,
    val newEpisodeNotifications: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val onboardingDone: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ondes_settings")

/**
 * Single source of truth for user preferences, backed by Preferences DataStore.
 * Exposes the whole bundle as a [Flow] so any layer (UI, playback, downloads,
 * sync) can react to changes, plus suspend setters for the settings screen.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SKIP_BACK = longPreferencesKey("skip_back_ms")
        val SKIP_FORWARD = longPreferencesKey("skip_forward_ms")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val BOOST_VOLUME = booleanPreferencesKey("boost_volume")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val AUTO_DELETE = booleanPreferencesKey("auto_delete_finished")
        val BACKGROUND_REFRESH = booleanPreferencesKey("background_refresh")
        val NEW_EPISODE_NOTIFS = booleanPreferencesKey("new_episode_notifications")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<OndesSettings> = context.dataStore.data.map { p ->
        val defaults = OndesSettings()
        OndesSettings(
            skipBackMs = p[Keys.SKIP_BACK] ?: defaults.skipBackMs,
            skipForwardMs = p[Keys.SKIP_FORWARD] ?: defaults.skipForwardMs,
            defaultSpeed = p[Keys.DEFAULT_SPEED] ?: defaults.defaultSpeed,
            autoAdvance = p[Keys.AUTO_ADVANCE] ?: defaults.autoAdvance,
            skipSilence = p[Keys.SKIP_SILENCE] ?: defaults.skipSilence,
            boostVolume = p[Keys.BOOST_VOLUME] ?: defaults.boostVolume,
            wifiOnlyDownloads = p[Keys.WIFI_ONLY] ?: defaults.wifiOnlyDownloads,
            autoDeleteFinished = p[Keys.AUTO_DELETE] ?: defaults.autoDeleteFinished,
            backgroundRefresh = p[Keys.BACKGROUND_REFRESH] ?: defaults.backgroundRefresh,
            newEpisodeNotifications = p[Keys.NEW_EPISODE_NOTIFS] ?: defaults.newEpisodeNotifications,
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
        )
    }

    suspend fun setSkipBackMs(value: Long) = edit { it[Keys.SKIP_BACK] = value }
    suspend fun setSkipForwardMs(value: Long) = edit { it[Keys.SKIP_FORWARD] = value }
    suspend fun setDefaultSpeed(value: Float) = edit { it[Keys.DEFAULT_SPEED] = value }
    suspend fun setAutoAdvance(value: Boolean) = edit { it[Keys.AUTO_ADVANCE] = value }
    suspend fun setSkipSilence(value: Boolean) = edit { it[Keys.SKIP_SILENCE] = value }
    suspend fun setBoostVolume(value: Boolean) = edit { it[Keys.BOOST_VOLUME] = value }
    suspend fun setWifiOnlyDownloads(value: Boolean) = edit { it[Keys.WIFI_ONLY] = value }
    suspend fun setAutoDeleteFinished(value: Boolean) = edit { it[Keys.AUTO_DELETE] = value }
    suspend fun setBackgroundRefresh(value: Boolean) = edit { it[Keys.BACKGROUND_REFRESH] = value }
    suspend fun setNewEpisodeNotifications(value: Boolean) =
        edit { it[Keys.NEW_EPISODE_NOTIFS] = value }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.THEME_MODE] = value.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setOnboardingDone(value: Boolean) = edit { it[Keys.ONBOARDING_DONE] = value }

    /** A one-shot snapshot of the current settings, for backup. */
    suspend fun snapshot(): OndesSettings = settings.first()

    /** Overwrite every preference from a restored snapshot. */
    suspend fun restore(s: OndesSettings) = edit {
        it[Keys.SKIP_BACK] = s.skipBackMs
        it[Keys.SKIP_FORWARD] = s.skipForwardMs
        it[Keys.DEFAULT_SPEED] = s.defaultSpeed
        it[Keys.AUTO_ADVANCE] = s.autoAdvance
        it[Keys.SKIP_SILENCE] = s.skipSilence
        it[Keys.BOOST_VOLUME] = s.boostVolume
        it[Keys.WIFI_ONLY] = s.wifiOnlyDownloads
        it[Keys.AUTO_DELETE] = s.autoDeleteFinished
        it[Keys.BACKGROUND_REFRESH] = s.backgroundRefresh
        it[Keys.NEW_EPISODE_NOTIFS] = s.newEpisodeNotifications
        it[Keys.THEME_MODE] = s.themeMode.name
        it[Keys.DYNAMIC_COLOR] = s.dynamicColor
        it[Keys.ONBOARDING_DONE] = s.onboardingDone
    }

    private suspend fun edit(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.dataStore.edit(block)
    }
}
