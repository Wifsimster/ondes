package ovh.battistella.ondes.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.util.httpUrlOrEmpty
import ovh.battistella.ondes.util.isHttpUrl
import java.io.File

sealed interface AudioSource {
    data class Local(val file: File) : AudioSource
    data class Remote(val url: String) : AudioSource
}

object MediaItems {

    fun resolveAudioSource(localFilePath: String?, audioUrl: String): AudioSource? {
        localFilePath?.let { path ->
            File(path).takeIf(File::exists)?.let { return AudioSource.Local(it) }
        }
        return audioUrl.takeIf { isHttpUrl(it) }?.let { AudioSource.Remote(it) }
    }

    fun audioUri(episode: EpisodeEntity): Uri? =
        when (val source = resolveAudioSource(episode.localFilePath, episode.audioUrl)) {
            is AudioSource.Local -> Uri.fromFile(source.file)
            is AudioSource.Remote -> source.url.toUri()
            null -> null
        }

    fun playable(episode: EpisodeEntity): MediaItem? {
        val uri = audioUri(episode) ?: return null
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMediaMetadata(episodeMetadata(episode))
            .build()
    }

    fun browsable(episode: EpisodeEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(episode.id)
            .setMediaMetadata(episodeMetadata(episode))
            .build()

    fun folder(mediaId: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    fun podcast(mediaId: String, title: String, imageUrl: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtworkUri(httpUrlOrEmpty(imageUrl).takeIf { it.isNotBlank() }?.toUri())
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    private fun episodeMetadata(episode: EpisodeEntity): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtworkUri(httpUrlOrEmpty(episode.imageUrl).takeIf { it.isNotBlank() }?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
}
