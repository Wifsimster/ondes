package ovh.battistella.ondes.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.backup.BackupManager
import ovh.battistella.ondes.data.opml.OpmlManager
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.data.settings.ThemeMode
import ovh.battistella.ondes.sync.FeedRefreshScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val opmlManager: OpmlManager,
    private val backupManager: BackupManager,
) : ViewModel() {

    val settings: StateFlow<OndesSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OndesSettings())

    /** When the background refresh last completed; 0 until it ever has. */
    val lastRefreshAt: StateFlow<Long> = settingsRepository.lastRefreshAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** One-shot user feedback (e.g. "Imported 12 subscriptions"), shown as a toast. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setSkipBack(ms: Long) = update { settingsRepository.setSkipBackMs(ms) }
    fun setSkipForward(ms: Long) = update { settingsRepository.setSkipForwardMs(ms) }
    fun setAutoAdvance(value: Boolean) = update { settingsRepository.setAutoAdvance(value) }
    fun setSkipSilence(value: Boolean) = update { settingsRepository.setSkipSilence(value) }
    fun setBoostVolume(value: Boolean) = update { settingsRepository.setBoostVolume(value) }
    fun setWifiOnlyDownloads(value: Boolean) =
        update { settingsRepository.setWifiOnlyDownloads(value) }
    fun setAutoDeleteFinished(value: Boolean) =
        update { settingsRepository.setAutoDeleteFinished(value) }
    fun setNewEpisodeNotifications(value: Boolean) =
        update { settingsRepository.setNewEpisodeNotifications(value) }
    fun setThemeMode(value: ThemeMode) = update { settingsRepository.setThemeMode(value) }
    fun setDynamicColor(value: Boolean) = update { settingsRepository.setDynamicColor(value) }

    fun setBackgroundRefresh(value: Boolean) = update {
        settingsRepository.setBackgroundRefresh(value)
        FeedRefreshScheduler.apply(context, value)
    }

    // --- Data ownership: OPML & backup ---

    fun exportOpml(uri: Uri) = dataOp(R.string.data_op_failed) {
        val count = context.contentResolver.openOutputStream(uri)?.use { out ->
            opmlManager.export(out)
        } ?: error("no stream")
        context.getString(R.string.export_opml_done, count)
    }

    fun importOpml(uri: Uri) = dataOp(R.string.data_op_failed) {
        val count = context.contentResolver.openInputStream(uri)?.use { input ->
            opmlManager.import(input)
        } ?: error("no stream")
        context.getString(R.string.import_opml_done, count)
    }

    fun exportBackup(uri: Uri) = dataOp(R.string.data_op_failed) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            backupManager.export(out)
        } ?: error("no stream")
        context.getString(R.string.backup_done)
    }

    fun importBackup(uri: Uri) = dataOp(R.string.data_op_failed) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            backupManager.import(input)
        } ?: error("no stream")
        context.getString(R.string.restore_done)
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Run a file operation off the main thread, reporting success/failure as a message. */
    private fun dataOp(failureRes: Int, block: suspend () -> String) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { context.getString(failureRes) }
            }
            _messages.tryEmit(message)
        }
    }
}
