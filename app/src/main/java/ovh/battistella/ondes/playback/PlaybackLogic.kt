package ovh.battistella.ondes.playback

/**
 * Pure, Android-free decision rules for playback position handling. Kept
 * separate from the service/connection so the tricky resume-and-persist logic
 * can be pinned by fast host unit tests without a device or Robolectric.
 */
object PlaybackTransitions {

    /**
     * The position an *incoming* episode should resume at when it becomes the
     * current item (auto-advance or a manual skip), or `null` to start it from
     * the beginning.
     *
     * Media3 playlists don't carry a per-item start position, so follow-on
     * queue items would otherwise always begin at 0:00 (issue P0-3). A finished
     * episode, or one with no saved progress, starts fresh.
     */
    fun resumeTargetMs(savedPositionMs: Long, isFinished: Boolean): Long? =
        if (savedPositionMs > 0 && !isFinished) savedPositionMs else null
}

/**
 * Pure timing rules for the "stop at end of episode" sleep timer. The threshold
 * scales with playback speed: at fast speeds the position jumps further between
 * ticks, so a fixed window can be stepped over entirely (issue P1-4), leaving
 * the next episode to start audibly.
 */
object SleepTimerLogic {

    /** How often the position ticker refreshes, in ms. */
    const val TICK_MS = 550L

    /** Never fire earlier than this before the true end (keeps the cut tight at 1×). */
    const val MIN_END_THRESHOLD_MS = 750L

    /** Never fire more than this early, however fast playback runs. */
    const val MAX_END_THRESHOLD_MS = 2_500L

    /**
     * How far before the real end the timer treats the episode as "at its end".
     * One tick's worth of progress at the current [speed], clamped so it neither
     * misses the window at high speed nor cuts off audibly early at 1×.
     */
    fun endThresholdMs(speed: Float): Long =
        (TICK_MS * speed.coerceAtLeast(1f)).toLong()
            .coerceIn(MIN_END_THRESHOLD_MS, MAX_END_THRESHOLD_MS)

    /** Whether the episode is close enough to its end to stop now. */
    fun isNearEnd(positionMs: Long, durationMs: Long, speed: Float): Boolean =
        durationMs > 0 && positionMs >= durationMs - endThresholdMs(speed)
}
