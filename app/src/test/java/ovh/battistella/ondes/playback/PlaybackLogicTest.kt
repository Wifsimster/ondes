package ovh.battistella.ondes.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ovh.battistella.ondes.data.local.EpisodeEntity

class PlaybackLogicTest {

    @Test
    fun resumesInProgressEpisodeAtSavedPosition() {
        assertEquals(90_000L, PlaybackTransitions.resumeTargetMs(90_000L, isFinished = false))
    }

    @Test
    fun freshEpisodeStartsFromBeginning() {
        assertNull(PlaybackTransitions.resumeTargetMs(0L, isFinished = false))
    }

    @Test
    fun finishedEpisodeStartsFromBeginning() {
        assertNull(PlaybackTransitions.resumeTargetMs(120_000L, isFinished = true))
    }

    private fun episode(n: Int, played: Boolean = false, finished: Boolean = false) = EpisodeEntity(
        id = "ep$n",
        feedUrl = "feed",
        title = "Episode $n",
        description = "",
        audioUrl = "https://example.com/$n.mp3",
        imageUrl = "",
        pubDate = n * 1_000L,
        durationMs = 0,
        isPlayed = played,
        isFinished = finished,
    )

    private fun newestFirst(vararg episodes: EpisodeEntity) = episodes.sortedByDescending { it.pubDate }

    @Test
    fun followOnContinuesIntoNewerEpisodesOldestFirst() {
        val list = newestFirst(episode(1), episode(2), episode(3), episode(4), episode(5))
        assertEquals(
            listOf("ep3", "ep4", "ep5"),
            PlaybackTransitions.followOn(episode(2), list).map { it.id },
        )
    }

    @Test
    fun followOnSkipsAlreadyListenedEpisodes() {
        val list = newestFirst(
            episode(1),
            episode(2),
            episode(3, played = true),
            episode(4, finished = true),
            episode(5),
        )
        assertEquals(listOf("ep5"), PlaybackTransitions.followOn(episode(2), list).map { it.id })
    }

    @Test
    fun followOnFromNewestEpisodeIsEmpty() {
        val list = newestFirst(episode(1), episode(2), episode(3))
        assertTrue(PlaybackTransitions.followOn(episode(3), list).isEmpty())
    }

    @Test
    fun followOnForEpisodeMissingFromListIsEmpty() {
        val list = newestFirst(episode(1), episode(2))
        assertTrue(PlaybackTransitions.followOn(episode(9), list).isEmpty())
    }

    @Test
    fun thresholdIsClampedToMinimumAtNormalSpeed() {
        assertEquals(SleepTimerLogic.MIN_END_THRESHOLD_MS, SleepTimerLogic.endThresholdMs(1f))
    }

    @Test
    fun thresholdGrowsWithSpeed() {
        assertTrue(SleepTimerLogic.endThresholdMs(3f) > SleepTimerLogic.MIN_END_THRESHOLD_MS)
    }

    @Test
    fun thresholdIsClampedToMaximum() {
        assertEquals(SleepTimerLogic.MAX_END_THRESHOLD_MS, SleepTimerLogic.endThresholdMs(10f))
    }

    @Test
    fun notNearEndEarlyInEpisode() {
        assertFalse(SleepTimerLogic.isNearEnd(positionMs = 10_000, durationMs = 3_600_000, speed = 1f))
    }

    @Test
    fun nearEndWithinThreshold() {
        val duration = 3_600_000L
        val pos = duration - SleepTimerLogic.MIN_END_THRESHOLD_MS + 1
        assertTrue(SleepTimerLogic.isNearEnd(pos, duration, speed = 1f))
    }

    @Test
    fun highSpeedCatchesTheEndOneTickBeforeTheBoundary() {
        val duration = 3_600_000L
        val pos = duration - 1_600
        assertTrue(SleepTimerLogic.isNearEnd(pos, duration, speed = 3f))
    }

    @Test
    fun unknownDurationIsNeverNearEnd() {
        assertFalse(SleepTimerLogic.isNearEnd(positionMs = 5_000, durationMs = 0, speed = 1f))
    }

    @Test
    fun switchingAwayNearTheEndIsAnAutoAdvance() {
        assertTrue(SleepTimerLogic.wasAutoAdvance(lastPositionMs = 3_595_000, lastDurationMs = 3_600_000))
    }

    @Test
    fun switchingAwayMidEpisodeIsTheUsersChoice() {
        assertFalse(SleepTimerLogic.wasAutoAdvance(lastPositionMs = 600_000, lastDurationMs = 3_600_000))
    }

    @Test
    fun unknownDurationIsNeverAnAutoAdvance() {
        assertFalse(SleepTimerLogic.wasAutoAdvance(lastPositionMs = 0, lastDurationMs = 0))
    }
}
