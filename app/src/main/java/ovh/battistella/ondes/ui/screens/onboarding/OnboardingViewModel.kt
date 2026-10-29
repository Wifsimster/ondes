package ovh.battistella.ondes.ui.screens.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastTheme
import ovh.battistella.ondes.data.remote.PodcastThemes
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { THEMES, PROPOSALS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.THEMES,
    val selectedThemes: Set<Int> = emptySet(),
    val proposalsLoading: Boolean = false,
    val proposals: List<PodcastSearchResult> = emptyList(),
    val chosenFeeds: Set<String> = emptySet(),
    val proposalsError: Boolean = false,
    val subscribing: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val settingsRepository: SettingsRepository,
    private val snackbar: SnackbarController,
) : ViewModel() {

    val themes: List<PodcastTheme> = PodcastThemes.all

    private val _state = MutableStateFlow(OnboardingUiState())
    val state = _state.asStateFlow()

    fun toggle(theme: PodcastTheme) {
        _state.value = _state.value.copy(
            selectedThemes = _state.value.selectedThemes.toMutableSet().apply {
                if (!add(theme.genreId)) remove(theme.genreId)
            },
        )
    }

    fun proceedToProposals() {
        if (_state.value.selectedThemes.isEmpty()) return
        _state.value = _state.value.copy(
            step = OnboardingStep.PROPOSALS,
            proposalsLoading = true,
            proposals = emptyList(),
            chosenFeeds = emptySet(),
            proposalsError = false,
        )
        loadProposals()
    }

    fun retryProposals() {
        _state.value = _state.value.copy(proposalsLoading = true, proposalsError = false)
        loadProposals()
    }

    private fun loadProposals() {
        viewModelScope.launch {
            val picked = themes.filter { it.genreId in _state.value.selectedThemes }
            val aggregated = coroutineScope {
                picked
                    .map { theme ->
                        async { repository.topPodcasts(theme.genreId, limit = PER_THEME_LIMIT) }
                    }
                    .awaitAll()
                    .flatten()
            }
            val deduped = aggregated.distinctBy { it.feedUrl }
            _state.value = _state.value.copy(
                proposalsLoading = false,
                proposals = deduped,
                proposalsError = deduped.isEmpty(),
            )
        }
    }

    fun toggleProposal(result: PodcastSearchResult) {
        _state.value = _state.value.copy(
            chosenFeeds = _state.value.chosenFeeds.toMutableSet().apply {
                if (!add(result.feedUrl)) remove(result.feedUrl)
            },
        )
    }

    fun back() {
        _state.value = _state.value.copy(step = OnboardingStep.THEMES)
    }

    fun subscribeSelectedAndFinish() {
        viewModelScope.launch {
            _state.value = _state.value.copy(subscribing = true)
            val chosen = _state.value.chosenFeeds.toList()
            val added = repository.subscribeAll(chosen)
            if (added.size < chosen.size) {
                snackbar.show(
                    context.getString(R.string.onboarding_added_count, added.size, chosen.size),
                )
            }
            settingsRepository.setOnboardingDone(true)
            _state.value = _state.value.copy(subscribing = false)
        }
    }

    fun skip() {
        viewModelScope.launch { settingsRepository.setOnboardingDone(true) }
    }

    companion object {
        private const val PER_THEME_LIMIT = 10
    }
}
