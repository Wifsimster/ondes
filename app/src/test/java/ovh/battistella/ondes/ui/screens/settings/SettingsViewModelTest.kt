package ovh.battistella.ondes.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.data.backup.BackupManager
import ovh.battistella.ondes.data.opml.OpmlManager
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.data.settings.ThemeMode
import ovh.battistella.ondes.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val opmlManager = mockk<OpmlManager>(relaxed = true)
    private val backupManager = mockk<BackupManager>(relaxed = true)

    private fun build(
        settings: OndesSettings = OndesSettings(),
        lastRefreshAt: Long = 0L,
    ): SettingsViewModel {
        every { settingsRepository.settings } returns flowOf(settings)
        every { settingsRepository.lastRefreshAt } returns flowOf(lastRefreshAt)
        return SettingsViewModel(context, settingsRepository, opmlManager, backupManager)
    }

    @Test
    fun `settings flow is surfaced to the UI`() = runTest(mainDispatcher.dispatcher) {
        val vm = build(OndesSettings(autoAdvance = false))
        backgroundScope.launch { vm.settings.collect {} }
        advanceUntilIdle()
        assertFalse(vm.settings.value.autoAdvance)
    }

    @Test
    fun `the last successful refresh is surfaced to the UI`() = runTest(mainDispatcher.dispatcher) {
        val vm = build(lastRefreshAt = 1_700_000_000_000L)
        backgroundScope.launch { vm.lastRefreshAt.collect {} }
        advanceUntilIdle()
        assertEquals(1_700_000_000_000L, vm.lastRefreshAt.value)
    }

    @Test
    fun `toggles persist through the settings repository`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()

        vm.setAutoAdvance(true)
        vm.setSkipSilence(true)
        vm.setBoostVolume(true)
        vm.setWifiOnlyDownloads(true)
        vm.setAutoDeleteFinished(true)
        vm.setNewEpisodeNotifications(false)
        vm.setThemeMode(ThemeMode.DARK)
        vm.setDynamicColor(false)
        vm.setSkipBack(5_000)
        vm.setSkipForward(20_000)
        advanceUntilIdle()

        coVerify { settingsRepository.setAutoAdvance(true) }
        coVerify { settingsRepository.setSkipSilence(true) }
        coVerify { settingsRepository.setBoostVolume(true) }
        coVerify { settingsRepository.setWifiOnlyDownloads(true) }
        coVerify { settingsRepository.setAutoDeleteFinished(true) }
        coVerify { settingsRepository.setNewEpisodeNotifications(false) }
        coVerify { settingsRepository.setThemeMode(ThemeMode.DARK) }
        coVerify { settingsRepository.setDynamicColor(false) }
        coVerify { settingsRepository.setSkipBackMs(5_000) }
        coVerify { settingsRepository.setSkipForwardMs(20_000) }
    }
}
