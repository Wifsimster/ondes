package ovh.battistella.ondes.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the resume / sleep-timer decision rules that Media3 can't express
 * declaratively — a regression here silently loses playback progress or lets
 * the next episode start on an end-of-episode sleep timer.
 */
class PlaybackLogicTest {

    // --- PlaybackTransitions.resumeTargetMs ------------------------------

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
        // A replay of a finished episode should not jump back to where it ended.
        assertNull(PlaybackTransitions.resumeTargetMs(120_000L, isFinished = true))
    }

    // --- SleepTimerLogic.endThresholdMs ---------------------------------

    @Test
    fun thresholdIsClampedToMinimumAtNormalSpeed() {
        // One 550ms tick at 1x is below the floor, so the floor wins — the cut
        // stays tight instead of chopping off a full second.
        assertEquals(SleepTimerLogic.MIN_END_THRESHOLD_MS, SleepTimerLogic.endThresholdMs(1f))
    }

    @Test
    fun thresholdGrowsWithSpeed() {
        // At 3x the playhead jumps ~1650ms per tick, so the window must widen or
        // the near-end check is stepped over entirely.
        assertTrue(SleepTimerLogic.endThresholdMs(3f) > SleepTimerLogic.MIN_END_THRESHOLD_MS)
    }

    @Test
    fun thresholdIsClampedToMaximum() {
        assertEquals(SleepTimerLogic.MAX_END_THRESHOLD_MS, SleepTimerLogic.endThresholdMs(10f))
    }

    // --- SleepTimerLogic.isNearEnd --------------------------------------

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
        // A 3x playhead lands here on its last tick before the end; the widened
        // threshold must classify it as near-end.
        val pos = duration - 1_600
        assertTrue(SleepTimerLogic.isNearEnd(pos, duration, speed = 3f))
    }

    @Test
    fun unknownDurationIsNeverNearEnd() {
        assertFalse(SleepTimerLogic.isNearEnd(positionMs = 5_000, durationMs = 0, speed = 1f))
    }
}
