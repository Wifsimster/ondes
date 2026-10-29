package ovh.battistella.ondes.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastTheme
import ovh.battistella.ondes.ui.components.OndesEmptyState
import ovh.battistella.ondes.ui.components.PodcastArtwork
import ovh.battistella.ondes.ui.theme.OndesTheme

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenPodcast: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.openPodcast.collect(onOpenPodcast)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(OndesTheme.spacing.lg),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            state.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.feedUrl }) { result ->
                    PodcastResultRow(
                        result = result,
                        subscribed = result.feedUrl in state.subscribedFeeds,
                        onSubscribe = { viewModel.subscribe(result) },
                        onClick = { onOpenPodcast(result.feedUrl) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            state.error != null -> OndesEmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.search_no_luck_title),
                message = state.error.orEmpty(),
                actionLabel = stringResource(R.string.try_again),
                onAction = viewModel::search,
            )

            state.query.isBlank() -> BrowseByTheme(
                state = state,
                onSelectTheme = viewModel::selectTheme,
                onSubscribe = viewModel::subscribe,
                onOpenPodcast = onOpenPodcast,
            )

            else -> OndesEmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.search_no_matches_title),
                message = stringResource(R.string.search_no_matches_message),
            )
        }
    }
}

@Composable
private fun BrowseByTheme(
    state: SearchUiState,
    onSelectTheme: (PodcastTheme) -> Unit,
    onSubscribe: (PodcastSearchResult) -> Unit,
    onOpenPodcast: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.browse_by_theme),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = OndesTheme.spacing.lg,
                end = OndesTheme.spacing.lg,
                bottom = OndesTheme.spacing.sm,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OndesTheme.spacing.lg),
        ) {
            items(state.themes, key = { it.genreId }) { theme ->
                FilterChip(
                    selected = theme == state.selectedTheme,
                    onClick = { onSelectTheme(theme) },
                    label = { Text(theme.label) },
                    modifier = Modifier.padding(end = OndesTheme.spacing.sm),
                )
            }
        }

        Spacer(Modifier.height(OndesTheme.spacing.md))

        when {
            state.themeLoading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            state.themeResults.isEmpty() -> OndesEmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.nothing_to_show_title),
                message = stringResource(
                    R.string.theme_load_error,
                    state.selectedTheme?.label.orEmpty(),
                ),
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.themeResults, key = { it.feedUrl }) { result ->
                    PodcastResultRow(
                        result = result,
                        subscribed = result.feedUrl in state.subscribedFeeds,
                        onSubscribe = { onSubscribe(result) },
                        onClick = { onOpenPodcast(result.feedUrl) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastResultRow(
    result: PodcastSearchResult,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = OndesTheme.spacing.lg,
                vertical = OndesTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodcastArtwork(
            url = result.imageUrl,
            modifier = Modifier.size(56.dp),
            shape = OndesTheme.shapes.artworkSmall,
        )
        Spacer(Modifier.width(OndesTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(OndesTheme.spacing.sm))
        val subscribedCd = stringResource(R.string.subscribed_to, result.title)
        val subscribeCd = stringResource(R.string.subscribe_to, result.title)
        Button(
            onClick = onSubscribe,
            enabled = !subscribed,
            modifier = Modifier.semantics {
                contentDescription = if (subscribed) subscribedCd else subscribeCd
            },
        ) {
            Text(
                if (subscribed) stringResource(R.string.subscribed_added)
                else stringResource(R.string.subscribe)
            )
        }
    }
}
