package ovh.battistella.ondes.playback

import ovh.battistella.ondes.data.local.EpisodeEntity

object PlaybackTransitions {

    fun resumeTargetMs(savedPositionMs: Long, isFinished: Boolean): Long? =
        if (savedPositionMs > 0 && !isFinished) savedPositionMs else null

    fun followOn(episode: EpisodeEntity, list: List<EpisodeEntity>): List<EpisodeEntity> {
        val index = list.indexOfFirst { it.id == episode.id }
        if (index < 0) return emptyList()
        return list.subList(0, index)
            .asReversed()
            .filter { !it.isPlayed && !it.isFinished }
    }
}

object SleepTimerLogic {

    const val TICK_MS = 550L

    const val MIN_END_THRESHOLD_MS = 750L

    const val MAX_END_THRESHOLD_MS = 2_500L

    fun endThresholdMs(speed: Float): Long =
        (TICK_MS * speed.coerceAtLeast(1f)).toLong()
            .coerceIn(MIN_END_THRESHOLD_MS, MAX_END_THRESHOLD_MS)

    fun isNearEnd(positionMs: Long, durationMs: Long, speed: Float): Boolean =
        durationMs > 0 && positionMs >= durationMs - endThresholdMs(speed)

    const val AUTO_ADVANCE_WINDOW_MS = 15_000L

    fun wasAutoAdvance(lastPositionMs: Long, lastDurationMs: Long): Boolean =
        lastDurationMs > 0 && lastPositionMs >= lastDurationMs - AUTO_ADVANCE_WINDOW_MS
}
