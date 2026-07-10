package ovh.battistella.ondes.ui.screens.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.ui.components.OndesEmptyState
import ovh.battistella.ondes.ui.components.PlayPauseIcon
import ovh.battistella.ondes.ui.components.PodcastArtwork
import ovh.battistella.ondes.ui.components.formatDate
import ovh.battistella.ondes.ui.components.formatDurationLabel
import ovh.battistella.ondes.ui.theme.OndesTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onOpenPlayer: () -> Unit,
    onBrowse: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_queue_title)) },
            text = { Text(stringResource(R.string.clear_queue_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); showClearDialog = false }) {
                    Text(stringResource(R.string.queue_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = { QueueTopBar(showClear = queue.isNotEmpty(), onClear = { showClearDialog = true }) },
    ) { padding ->
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                OndesEmptyState(
                    icon = Icons.Rounded.QueueMusic,
                    title = stringResource(R.string.queue_empty_title),
                    message = stringResource(R.string.queue_empty_message),
                    actionLabel = stringResource(R.string.browse_podcasts),
                    onAction = onBrowse,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            itemsIndexed(queue, key = { _, e -> e.id }) { index, episode ->
                QueueRow(
                    episode = episode,
                    index = index,
                    lastIndex = queue.lastIndex,
                    isCurrent = nowPlaying.episodeId == episode.id,
                    isPlaying = nowPlaying.isPlaying,
                    onPlayToggle = { viewModel.playToggle(episode) },
                    onClick = { viewModel.open(episode); onOpenPlayer() },
                    onMoveUp = { viewModel.moveUp(index) },
                    onMoveDown = { viewModel.moveDown(index) },
                    onRemove = { viewModel.remove(episode) },
                    modifier = Modifier.animateItem(),
                )
                HorizontalDivider(Modifier.padding(start = OndesTheme.spacing.lg))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueTopBar(showClear: Boolean, onClear: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.queue_title)) },
        actions = {
            if (showClear) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.queue_clear)) }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    episode: EpisodeEntity,
    index: Int,
    lastIndex: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playOrPauseLabel = stringResource(R.string.play_or_pause_episode)
    val playingLabel = stringResource(R.string.playing)
    val pausedLabel = stringResource(R.string.paused)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRemove)
            .padding(horizontal = OndesTheme.spacing.lg, vertical = OndesTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodcastArtwork(
            url = episode.imageUrl,
            modifier = Modifier.size(48.dp),
            shape = OndesTheme.shapes.artworkSmall,
        )
        Spacer(Modifier.width(OndesTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(formatDate(episode.pubDate))
                    val dur = formatDurationLabel(episode.durationMs)
                    if (dur.isNotEmpty()) append("  ·  $dur")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onPlayToggle,
            modifier = Modifier.semantics {
                contentDescription = playOrPauseLabel
                stateDescription = if (isCurrent && isPlaying) playingLabel else pausedLabel
            },
        ) {
            PlayPauseIcon(
                isPlaying = isCurrent && isPlaying,
                playIcon = Icons.Rounded.PlayCircle,
                pauseIcon = Icons.Rounded.PauseCircle,
                tint = OndesTheme.colors.brand,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = onMoveUp, enabled = index > 0) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                contentDescription = stringResource(R.string.move_up),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = index < lastIndex) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.move_down),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.RemoveCircleOutline,
                contentDescription = stringResource(R.string.remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
