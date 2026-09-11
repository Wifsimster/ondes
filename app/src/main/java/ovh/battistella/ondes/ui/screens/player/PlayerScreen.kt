package ovh.battistella.ondes.ui.screens.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import ovh.battistella.ondes.data.local.Chapter
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.ui.components.HtmlText
import ovh.battistella.ondes.ui.components.PlayPauseIcon
import ovh.battistella.ondes.ui.components.PodcastArtwork
import ovh.battistella.ondes.ui.components.WavySliderTrack
import ovh.battistella.ondes.ui.components.formatTime
import ovh.battistella.ondes.ui.theme.OndesTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val episode by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepRemainingMs.collectAsStateWithLifecycle()
    val sleepEndOfEpisode by viewModel.sleepEndOfEpisode.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val skipBackSeconds by viewModel.skipBackSeconds.collectAsStateWithLifecycle()
    val skipForwardSeconds by viewModel.skipForwardSeconds.collectAsStateWithLifecycle()

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val artworkUrl = state.artworkUri ?: episode?.imageUrl
    val artworkDescription = stringResource(
        R.string.artwork_for,
        state.title.ifEmpty { episode?.title.orEmpty() },
    )

    @Composable
    fun controls(modifier: Modifier) = PlayerControls(
        state = state,
        episode = episode,
        chapters = chapters,
        queue = queue,
        sleepRemaining = sleepRemaining,
        sleepEndOfEpisode = sleepEndOfEpisode,
        skipBackSeconds = skipBackSeconds,
        skipForwardSeconds = skipForwardSeconds,
        viewModel = viewModel,
        modifier = modifier,
    )

    if (landscape) {
        // Side-by-side so a full-width square artwork doesn't push the controls
        // off-screen: artwork is bounded by the (short) landscape height on the
        // left, the controls scroll independently on the right.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = OndesTheme.spacing.xl),
        ) {
            NowPlayingTopBar(onClose = onClose, onStop = { viewModel.stop(); onClose() })
            // weight(1f) so the Row takes the height left below the top bar rather
            // than the full column height (which would overflow and clip).
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PodcastArtwork(
                    url = artworkUrl,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .padding(vertical = OndesTheme.spacing.sm),
                    shape = OndesTheme.shapes.artworkLarge,
                    contentDescription = artworkDescription,
                )
                Spacer(Modifier.width(OndesTheme.spacing.xl))
                controls(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OndesTheme.spacing.xl),
        ) {
            NowPlayingTopBar(onClose = onClose, onStop = { viewModel.stop(); onClose() })
            Spacer(Modifier.height(OndesTheme.spacing.sm))
            PodcastArtwork(
                url = artworkUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = OndesTheme.shapes.artworkLarge,
                contentDescription = artworkDescription,
            )
            controls(Modifier.fillMaxWidth())
        }
    }
}

/** Close / "Now playing" / Stop header, shared by both orientations. */
@Composable
private fun NowPlayingTopBar(onClose: () -> Unit, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = OndesTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = stringResource(R.string.now_playing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Stop clears the episode and dismisses both the player and mini-player.
        IconButton(onClick = onStop) {
            Icon(
                Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.stop_playback),
            )
        }
    }
}

/**
 * Title, scrubber, transport and secondary controls plus show notes — the part
 * that lives below the artwork in portrait and beside it in landscape. The
 * caller supplies the [modifier] (and thus the scroll container) so the block
 * scrolls with the artwork in portrait but independently of it in landscape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
    state: PlayerUiState,
    episode: EpisodeEntity?,
    chapters: List<Chapter>,
    queue: List<EpisodeEntity>,
    sleepRemaining: Long,
    sleepEndOfEpisode: Boolean,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    var showChapters by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Local scrub state so the thumb tracks the finger without fighting ticks.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(0)
    val maxValue = if (duration > 0) duration.toFloat() else 1f
    val sliderPosition = (if (scrubbing) scrubValue
    else if (duration > 0) state.positionMs.toFloat() else 0f).coerceIn(0f, maxValue)

    Column(modifier = modifier) {
        Spacer(Modifier.height(OndesTheme.spacing.xl))
        Text(
            text = state.title.ifEmpty { episode?.title.orEmpty() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(Modifier.height(OndesTheme.spacing.xl))
        val positionLabel = stringResource(R.string.playback_position)
        val positionState = stringResource(
            R.string.position_of_duration,
            formatTime(sliderPosition.toLong()),
            formatTime(duration),
        )
        Slider(
            value = sliderPosition,
            onValueChange = { scrubbing = true; scrubValue = it },
            onValueChangeFinished = {
                viewModel.seekTo(scrubValue.toLong())
                scrubbing = false
            },
            valueRange = 0f..maxValue,
            enabled = duration > 0,
            // The played portion ripples while playing and flattens on pause —
            // the "ondes" of the name, doubling as a playback-state cue.
            track = { sliderState ->
                WavySliderTrack(
                    sliderState = sliderState,
                    playing = state.isPlaying && !scrubbing,
                    enabled = duration > 0,
                )
            },
            modifier = Modifier.semantics {
                contentDescription = positionLabel
                stateDescription = positionState
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(sliderPosition.toLong()), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(OndesTheme.spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = viewModel::previous,
                enabled = state.hasPrevious,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    stringResource(R.string.previous_episode),
                    Modifier.size(30.dp),
                )
            }
            IconButton(onClick = viewModel::seekBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    skipBackIcon(skipBackSeconds),
                    stringResource(R.string.skip_back_secs, skipBackSeconds),
                    Modifier.size(38.dp),
                )
            }
            val playPauseLabel = stringResource(R.string.play_or_pause)
            val playingLabel = stringResource(R.string.playing)
            val pausedLabel = stringResource(R.string.paused)
            IconButton(
                onClick = viewModel::playPause,
                modifier = Modifier
                    .size(80.dp)
                    .semantics {
                        contentDescription = playPauseLabel
                        stateDescription = if (state.isPlaying) playingLabel else pausedLabel
                    },
            ) {
                PlayPauseIcon(
                    isPlaying = state.isPlaying,
                    playIcon = Icons.Rounded.PlayArrow,
                    pauseIcon = Icons.Rounded.Pause,
                    tint = OndesTheme.colors.brand,
                    modifier = Modifier.size(72.dp),
                )
            }
            IconButton(onClick = viewModel::seekForward, modifier = Modifier.size(56.dp)) {
                Icon(
                    skipForwardIcon(skipForwardSeconds),
                    stringResource(R.string.skip_forward_secs, skipForwardSeconds),
                    Modifier.size(38.dp),
                )
            }
            IconButton(
                onClick = viewModel::next,
                enabled = state.hasNext,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    stringResource(R.string.next_episode),
                    Modifier.size(30.dp),
                )
            }
        }

        Spacer(Modifier.height(OndesTheme.spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SpeedControl(current = state.speed, onSelect = viewModel::setSpeed)
            if (chapters.isNotEmpty()) {
                TextButton(onClick = { showChapters = true }) {
                    Icon(
                        Icons.Rounded.FormatListBulleted,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.chapters))
                }
            }
            if (queue.isNotEmpty()) {
                TextButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.nav_queue))
                }
            }
            SleepControl(
                remainingMs = sleepRemaining,
                endOfEpisode = sleepEndOfEpisode,
                onSelect = { viewModel.startSleepTimer(it) },
                onSelectEndOfEpisode = viewModel::startSleepAtEpisodeEnd,
                onCancel = viewModel::cancelSleepTimer,
            )
        }

        if (showChapters) {
            ChaptersSheet(
                chapters = chapters,
                currentPositionMs = state.positionMs,
                onSelect = { viewModel.seekTo(it); showChapters = false },
                onDismiss = { showChapters = false },
            )
        }

        if (showQueue) {
            QueueSheet(
                queue = queue,
                currentEpisodeId = state.currentEpisodeId,
                onSelect = { viewModel.playQueueItem(it); showQueue = false },
                onDismiss = { showQueue = false },
            )
        }

        episode?.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Spacer(Modifier.height(OndesTheme.spacing.xl))
            Text(
                stringResource(R.string.show_notes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(OndesTheme.spacing.sm))
            HtmlText(
                html = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTimestampClick = viewModel::seekTo,
            )
        }
        Spacer(Modifier.height(OndesTheme.spacing.xxl))
    }
}

@Composable
private fun SpeedControl(current: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val speeds = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 3.0f)
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text("${trimSpeed(current)}×")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${trimSpeed(speed)}×") },
                    onClick = { onSelect(speed); open = false },
                )
            }
        }
    }
}

@Composable
private fun SleepControl(
    remainingMs: Long,
    endOfEpisode: Boolean,
    onSelect: (Int) -> Unit,
    onSelectEndOfEpisode: () -> Unit,
    onCancel: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(5, 15, 30, 45, 60)
    val active = remainingMs > 0 || endOfEpisode
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                when {
                    remainingMs > 0 -> formatTime(remainingMs)
                    endOfEpisode -> stringResource(R.string.sleep_end)
                    else -> stringResource(R.string.sleep)
                }
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.minutes_short, minutes)) },
                    onClick = { onSelect(minutes); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.end_of_episode)) },
                onClick = { onSelectEndOfEpisode(); open = false },
            )
            if (active) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cancel_timer)) },
                    onClick = { onCancel(); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(
    chapters: List<Chapter>,
    currentPositionMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // The active chapter is the last one whose start time is at or before now.
    val currentIndex = chapters.indexOfLast { it.startMs <= currentPositionMs }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.chapters),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = OndesTheme.spacing.xl,
                end = OndesTheme.spacing.xl,
                bottom = OndesTheme.spacing.sm,
            ),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(chapters) { index, chapter ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(chapter.startMs) }
                        .padding(
                            horizontal = OndesTheme.spacing.xl,
                            vertical = OndesTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(chapter.startMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                    Spacer(Modifier.width(OndesTheme.spacing.sm))
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(Modifier.padding(start = OndesTheme.spacing.xl))
            }
        }
        Spacer(Modifier.height(OndesTheme.spacing.xl))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<EpisodeEntity>,
    currentEpisodeId: String?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.queue_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = OndesTheme.spacing.xl,
                end = OndesTheme.spacing.xl,
                bottom = OndesTheme.spacing.sm,
            ),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(queue, key = { _, e -> e.id }) { index, episode ->
                val isCurrent = episode.id == currentEpisodeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(
                            horizontal = OndesTheme.spacing.xl,
                            vertical = OndesTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodcastArtwork(
                        url = episode.imageUrl,
                        modifier = Modifier.size(44.dp),
                        shape = OndesTheme.shapes.artworkSmall,
                    )
                    Spacer(Modifier.width(OndesTheme.spacing.md))
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(Modifier.padding(start = OndesTheme.spacing.xl))
            }
        }
        Spacer(Modifier.height(OndesTheme.spacing.xl))
    }
}

/** Pick the closest matching rewind glyph; fall back to a plain replay icon. */
private fun skipBackIcon(seconds: Int) = when (seconds) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

/** Pick the closest matching fast-forward glyph; fall back to a plain forward icon. */
private fun skipForwardIcon(seconds: Int) = when (seconds) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

private fun trimSpeed(speed: Float): String =
    if (speed == speed.toLong().toFloat()) speed.toLong().toString()
    else speed.toString()
