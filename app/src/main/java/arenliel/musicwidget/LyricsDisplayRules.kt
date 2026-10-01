package arenliel.musicwidget

/**
 * Conjunto Letras-Motor-Puro-1: what the widget shows for lyrics at one moment, and how long it
 * can sleep before that answer changes. `text` is the lyric line to show; an empty `text` means
 * "no lyric": the widget shows the artist name instead. `recheckInMs` is the time until the answer
 * will change; null means it will never change again (the loop can stop).
 */
data class LyricsFrame(val text: String, val recheckInMs: Long?)

/**
 * Pure rules (no Android, no clock, no state) that decide what lyric the widget shows.
 *
 * The answer depends ONLY on the lyrics and the playback position, never on what was shown before.
 * That is what makes seeks, restarts ("previous"), long instrumentals and screen wake-ups behave
 * the same way: each one is just "ask again with the new position".
 *
 * `entries` must be a coherent timeline (non-decreasing timestamps), as returned by
 * `LyricsTimeline.parse`.
 */
object LyricsDisplayRules {

    /** The widget shows a line this long before its timestamp, so it feels in sync. */
    const val LEAD_MS = 500L

    /** A line stays on screen at most this long when the next line is farther away than this. */
    const val SILENCE_AFTER_MS = 10_000L

    /** Never ask for a re-check sooner than this (protects against tight loops). */
    const val MIN_RECHECK_MS = 100L

    /** Paused within this window of a song that really played: show its first line. */
    const val PAUSED_FIRST_LINE_WINDOW_MS = 5_000L

    /**
     * Frame for a song that is PLAYING at `positionMs`.
     *
     *  - Before the first line: no lyric (artist), until the first line arrives.
     *  - On a line: that line; if the next line is more than SILENCE_AFTER_MS later (or there is
     *    no next line), the line is kept SILENCE_AFTER_MS after its own timestamp, then no lyric.
     *  - After the last line: same fade rule, then no lyric for good.
     */
    fun playing(entries: List<LyricsEntry>, positionMs: Long): LyricsFrame {
        if (entries.isEmpty()) return LyricsFrame("", null)

        val effective = positionMs + LEAD_MS
        val index = LyricsTimeline.indexAt(entries, effective)

        if (index == -1) {
            return LyricsFrame("", untilMs(entries.first().timestampMs, effective))
        }

        val line = entries[index]
        val next = entries.getOrNull(index + 1)

        if (next != null && next.timestampMs - line.timestampMs <= SILENCE_AFTER_MS) {
            // The next line comes soon: the line simply stays until then.
            return LyricsFrame(line.text, untilMs(next.timestampMs, effective))
        }

        val silenceAt = line.timestampMs + SILENCE_AFTER_MS
        if (effective < silenceAt) {
            return LyricsFrame(line.text, untilMs(silenceAt, effective))
        }
        return LyricsFrame("", next?.let { untilMs(it.timestampMs, effective) })
    }

    /**
     * Text for a song that is PAUSED at `positionMs`. While paused the widget alternates between
     * the frozen line (`showLyric` = true) and the artist name (`showLyric` = false).
     * If no line has been reached yet but the song really played and we are within the first
     * PAUSED_FIRST_LINE_WINDOW_MS, the frozen line is the song's first line.
     * `hasConfirmedPlayback` is false while the song is only loading, so nothing is shown early.
     */
    fun paused(
        entries: List<LyricsEntry>,
        positionMs: Long,
        hasConfirmedPlayback: Boolean,
        showLyric: Boolean
    ): String {
        val index = LyricsTimeline.indexAt(entries, positionMs)
        val line = when {
            index != -1 -> entries[index]
            positionMs < PAUSED_FIRST_LINE_WINDOW_MS && hasConfirmedPlayback -> entries.firstOrNull()
            else -> null
        }
        return if (showLyric && line != null) line.text else ""
    }

    private fun untilMs(targetMs: Long, nowMs: Long): Long =
        (targetMs - nowMs).coerceAtLeast(MIN_RECHECK_MS)
}
