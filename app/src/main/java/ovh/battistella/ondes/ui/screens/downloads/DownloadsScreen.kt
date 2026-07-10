package ovh.battistella.ondes.ui.screens.downloads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.R
import ovh.battistella.ondes.ui.components.OndesEmptyState
import ovh.battistella.ondes.ui.components.EpisodeRow
import ovh.battistella.ondes.ui.components.EpisodeRowDividerStartInset

@Composable
fun DownloadsScreen(
    onOpenPlayer: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()

    if (downloads.isEmpty()) {
        OndesEmptyState(
            icon = Icons.Rounded.DownloadForOffline,
            title = stringResource(R.string.downloads_empty_title),
            message = stringResource(R.string.downloads_empty_message),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(downloads, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                isCurrent = nowPlaying.episodeId == episode.id,
                isPlaying = nowPlaying.isPlaying,
                onPlayToggle = { viewModel.playToggle(episode) },
                onClick = { viewModel.open(episode); onOpenPlayer() },
                onDownload = {},
                onDeleteDownload = { viewModel.deleteDownload(episode) },
                onSetPlayed = { played -> viewModel.markPlayed(episode, played) },
                onPlayNext = { viewModel.playNext(episode) },
                onAddToQueue = { viewModel.addToQueue(episode) },
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider(Modifier.padding(start = EpisodeRowDividerStartInset))
        }
    }
}
