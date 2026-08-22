package ovh.battistella.ondes.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.ui.theme.OndesTheme

/**
 * Start inset that aligns a list divider with an [EpisodeRow]'s text column,
 * clearing the leading artwork: row start padding + artwork width + gap.
 */
val EpisodeRowDividerStartInset = 84.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    episode: EpisodeEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val playOrPauseLabel = stringResource(R.string.play_or_pause_episode)
    val playingLabel = stringResource(R.string.playing)
    val pausedLabel = stringResource(R.string.paused)

    // Surface the long-press menu actions to TalkBack via the item's actions menu,
    // so screen-reader and motor-impaired users can reach them too (#10).
    val markPlayedLabel = stringResource(R.string.mark_as_played)
    val markUnplayedLabel = stringResource(R.string.mark_as_unplayed)
    val playNextLabel = stringResource(R.string.play_next)
    val addToQueueLabel = stringResource(R.string.add_to_queue)
    val downloadLabel = stringResource(R.string.download)
    val deleteDownloadLabel = stringResource(R.string.delete_download)
    val rowActions = buildList {
        if (!episode.isFinished) {
            add(CustomAccessibilityAction(markPlayedLabel) { onSetPlayed(true); true })
        }
        if (episode.isFinished || episode.positionMs > 0) {
            add(CustomAccessibilityAction(markUnplayedLabel) { onSetPlayed(false); true })
        }
        if (onPlayNext != null) add(CustomAccessibilityAction(playNextLabel) { onPlayNext(); true })
        if (onAddToQueue != null) {
            add(CustomAccessibilityAction(addToQueueLabel) { onAddToQueue(); true })
        }
        when (episode.downloadState) {
            DownloadState.DOWNLOADED ->
                add(CustomAccessibilityAction(deleteDownloadLabel) { onDeleteDownload(); true })
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> Unit
            else -> add(CustomAccessibilityAction(downloadLabel) { onDownload(); true })
        }
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                )
                .semantics { customActions = rowActions }
                .padding(horizontal = OndesTheme.spacing.lg, vertical = OndesTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
        if (showArtwork) {
            PodcastArtwork(
                url = episode.imageUrl,
                modifier = Modifier.size(56.dp),
                shape = OndesTheme.shapes.artworkSmall,
            )
            Spacer(Modifier.width(OndesTheme.spacing.md))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val inProgress = episode.positionMs > 0 && !episode.isFinished && episode.durationMs > 0
            val timeLeft = if (inProgress) {
                stringResource(
                    R.string.time_left,
                    durationLabel(episode.durationMs - episode.positionMs),
                )
            } else ""
            // Resolved before buildString: the label is localised, so it is read
            // from resources rather than assembled from hardcoded unit letters.
            val duration = durationLabel(episode.durationMs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (episode.isFinished) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.played),
                        tint = OndesTheme.colors.played,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = buildString {
                        append(formatDate(episode.pubDate))
                        if (inProgress) {
                            append("  ·  $timeLeft")
                        } else if (duration.isNotEmpty()) {
                            append("  ·  $duration")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (episode.positionMs > 0 && !episode.isFinished && episode.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(OndesTheme.spacing.sm))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    modifier = Modifier.size(36.dp),
                )
            }
            DownloadAffordance(episode, onDownload, onDeleteDownload)
        }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            // "Mark as played" when there's something left to finish; "mark as
            // unplayed" when it's finished or part-way through (so an in-progress
            // episode can be reset). A fresh, untouched episode shows only the former.
            if (!episode.isFinished) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_as_played)) },
                    leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                    onClick = {
                        onSetPlayed(true)
                        menuExpanded = false
                    },
                )
            }
            if (episode.isFinished || episode.positionMs > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_as_unplayed)) },
                    leadingIcon = { Icon(Icons.Rounded.RemoveDone, contentDescription = null) },
                    onClick = {
                        onSetPlayed(false)
                        menuExpanded = false
                    },
                )
            }
            if (onPlayNext != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.play_next)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.PlaylistPlay, contentDescription = null)
                    },
                    onClick = {
                        onPlayNext()
                        menuExpanded = false
                    },
                )
            }
            if (onAddToQueue != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_to_queue)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = null)
                    },
                    onClick = {
                        onAddToQueue()
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadAffordance(
    episode: EpisodeEntity,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    when (episode.downloadState) {
        DownloadState.DOWNLOADED -> IconButton(onClick = onDeleteDownload) {
            Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = stringResource(R.string.downloaded_tap_delete),
                tint = OndesTheme.colors.downloaded,
                modifier = Modifier.size(20.dp),
            )
        }
        // Expressive activity indicator while the episode is fetching.
        DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
            val downloadingLabel = stringResource(R.string.downloading)
            IconButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.semantics { contentDescription = downloadingLabel },
            ) {
                LoadingIndicator(
                    color = OndesTheme.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // A failed download must read differently from "never downloaded" and
        // offer a retry — tapping re-enqueues it.
        DownloadState.FAILED -> IconButton(onClick = onDownload) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.download_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
        else -> IconButton(onClick = onDownload) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = stringResource(R.string.download),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
