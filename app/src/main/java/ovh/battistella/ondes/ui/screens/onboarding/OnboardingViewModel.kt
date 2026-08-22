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

/** The two steps of first-run onboarding. */
enum class OnboardingStep { THEMES, PROPOSALS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.THEMES,
    val selectedThemes: Set<Int> = emptySet(),        // genreIds
    val proposalsLoading: Boolean = false,
    val proposals: List<PodcastSearchResult> = emptyList(), // deduped by feedUrl
    val chosenFeeds: Set<String> = emptySet(),        // feedUrls the user ticked
    val proposalsError: Boolean = false,              // load failed / nothing came back
    val subscribing: Boolean = false,                 // final subscribe in flight
)

/**
 * First-run interest picker. Step 1: pick a few themes. Step 2: we *propose* the
 * top shows for those themes and the user chooses which to subscribe to — we
 * never silently bulk-subscribe.
 */
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

    /** Leave the theme picker and load the proposed shows for the chosen themes. */
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

    /** Retry after a failed proposals load (re-uses the current theme selection). */
    fun retryProposals() {
        _state.value = _state.value.copy(proposalsLoading = true, proposalsError = false)
        loadProposals()
    }

    private fun loadProposals() {
        viewModelScope.launch {
            val picked = themes.filter { it.genreId in _state.value.selectedThemes }
            // One request per theme, all in flight together: run back to back,
            // the first screen of onboarding waited out a round-trip per theme
            // (opt. 2).
            val aggregated = coroutineScope {
                picked
                    .map { theme ->
                        async { repository.topPodcasts(theme.genreId, limit = PER_THEME_LIMIT) }
                    }
                    .awaitAll()
                    .flatten()
            }
            // Cross-theme dedupe: each call dedupes internally, but the same show
            // can chart in two themes — a duplicate feedUrl would crash the keyed list.
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

    /** Back to the theme picker, keeping selections so nothing is lost. */
    fun back() {
        _state.value = _state.value.copy(step = OnboardingStep.THEMES)
    }

    /**
     * Subscribe to only the shows the user picked, then leave onboarding.
     *
     * The feeds are fetched with bounded concurrency rather than one after
     * another (opt. 2), and a partial result is reported instead of swallowed:
     * silently dropping every failure left a first-time user staring at an empty
     * Home screen with no idea why (issue P2).
     */
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
        /** Top shows fetched per chosen theme — kept modest so the list stays digestible. */
        private const val PER_THEME_LIMIT = 10
    }
}
