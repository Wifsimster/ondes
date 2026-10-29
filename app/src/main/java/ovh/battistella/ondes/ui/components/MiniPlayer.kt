package ovh.battistella.ondes.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ovh.battistella.ondes.R
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.ui.theme.OndesTheme

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onClick: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.currentEpisodeId == null) return
    val progress = if (state.durationMs > 0)
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                color = OndesTheme.colors.brand,
                trackColor = OndesTheme.colors.brand.copy(alpha = 0.18f),
                drawStopIndicator = {},
                gapSize = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clearAndSetSemantics {},
            )
            Row(
                modifier = Modifier
                    .clickable(onClick = onClick, onClickLabel = stringResource(R.string.open_player))
                    .padding(horizontal = OndesTheme.spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PodcastArtwork(
                    url = state.artworkUri,
                    modifier = Modifier.size(44.dp),
                    shape = OndesTheme.shapes.artworkSmall,
                )
                Spacer(Modifier.width(OndesTheme.spacing.md))
                val playingLabel = stringResource(R.string.playing)
                val pausedLabel = stringResource(R.string.paused)
                val playPauseLabel = stringResource(R.string.play_or_pause)
                Box(Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifEmpty { playingLabel },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.semantics {
                        contentDescription = playPauseLabel
                        stateDescription = if (state.isPlaying) playingLabel else pausedLabel
                    },
                ) {
                    PlayPauseIcon(
                        isPlaying = state.isPlaying,
                        playIcon = Icons.Rounded.PlayArrow,
                        pauseIcon = Icons.Rounded.Pause,
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = onForward) {
                    Icon(
                        Icons.Rounded.Forward30,
                        contentDescription = stringResource(R.string.forward_30),
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.stop_playback),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
