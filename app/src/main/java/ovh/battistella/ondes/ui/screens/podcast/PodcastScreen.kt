package ovh.battistella.ondes.ui.screens.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.R
import ovh.battistella.ondes.ui.components.EpisodeRow
import ovh.battistella.ondes.ui.components.HtmlText
import ovh.battistella.ondes.ui.components.PodcastArtwork
import ovh.battistella.ondes.ui.theme.OndesTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val filteredEpisodes by viewModel.filteredEpisodes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val unplayedOnly by viewModel.unplayedOnly.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    Scaffold(
        // Clear a landscape side-notch so the episode list isn't drawn under it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        podcast?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(
                top = padding.calculateTopPadding(),
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection),
            ),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                // Clear whichever is taller: this screen's own bottom inset or the
                // root's (which includes the mini-player). Both already count the
                // nav bar, so take the max rather than summing them (issue P1-16).
                bottom = maxOf(
                    padding.calculateBottomPadding(),
                    contentPadding.calculateBottomPadding(),
                ),
            ),
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        PodcastArtwork(
                            url = podcast?.imageUrl,
                            modifier = Modifier.size(110.dp),
                            shape = OndesTheme.shapes.artworkMedium,
                            contentDescription = podcast?.title
                                ?.let { stringResource(R.string.artwork_for, it) },
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                podcast?.title.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                podcast?.author.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            val subscribed = podcast?.subscribed == true
                            val subscribeLabel = stringResource(R.string.subscribe)
                            val subscribedState = stringResource(R.string.subscribed_label)
                            val notSubscribedState = stringResource(R.string.not_subscribed)
                            val subscribeSemantics = Modifier.semantics {
                                contentDescription = subscribeLabel
                                stateDescription =
                                    if (subscribed) subscribedState else notSubscribedState
                            }
                            if (subscribed) {
                                OutlinedButton(
                                    onClick = viewModel::toggleSubscribe,
                                    modifier = subscribeSemantics,
                                ) {
                                    Text(stringResource(R.string.subscribed_label))
                                }
                            } else {
                                Button(
                                    onClick = viewModel::toggleSubscribe,
                                    modifier = subscribeSemantics,
                                ) {
                                    Text(stringResource(R.string.subscribe))
                                }
                            }
                        }
                    }
                    podcast?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(Modifier.height(16.dp))
                        var descExpanded by remember { mutableStateOf(false) }
                        HtmlText(
                            html = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descExpanded = !descExpanded },
                        )
                        Text(
                            text = stringResource(
                                if (descExpanded) R.string.show_less else R.string.show_more,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { descExpanded = !descExpanded },
                        )
                    }
                    if (podcast?.subscribed == true) {
                        Spacer(Modifier.height(8.dp))
                        SpeedRow(current = podcast?.overrideSpeed, onSelect = viewModel::setSpeed)
                        AutoDownloadRow(
                            checked = podcast?.autoDownload == true,
                            onChange = viewModel::setAutoDownload,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.episodes_count, episodes.size, episodes.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = unplayedOnly,
                            onClick = viewModel::toggleUnplayedOnly,
                            label = { Text(stringResource(R.string.filter_unplayed_only)) },
                        )
                    }
                    if (episodes.size > 8) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.filter_episodes)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                        )
                    }
                }
                HorizontalDivider()
            }

            items(filteredEpisodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    isCurrent = nowPlaying.episodeId == episode.id,
                    isPlaying = nowPlaying.isPlaying,
                    onPlayToggle = { viewModel.playToggle(episode) },
                    onClick = { viewModel.open(episode); onOpenPlayer() },
                    onDownload = { viewModel.download(episode) },
                    onDeleteDownload = { viewModel.deleteDownload(episode) },
                    onSetPlayed = { played -> viewModel.markPlayed(episode, played) },
                    onPlayNext = { viewModel.playNext(episode) },
                    onAddToQueue = { viewModel.addToQueue(episode) },
                    showArtwork = false,
                    modifier = Modifier.animateItem(),
                )
                HorizontalDivider(Modifier.padding(start = OndesTheme.spacing.lg))
            }
        }
        }
    }
}

@Composable
private fun SpeedRow(current: Float?, onSelect: (Float?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options: List<Float?> = listOf(null, 0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 3.0f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.playback_speed), style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { open = true }) { Text(speedLabel(current)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(speedLabel(speed)) },
                        onClick = { onSelect(speed); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoDownloadRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.auto_download_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun speedLabel(speed: Float?): String =
    if (speed == null) {
        stringResource(R.string.speed_default)
    } else {
        val n = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString()
        "${n}×"
    }
