package arenliel.musicwidget

import java.text.Normalizer
import kotlin.math.abs

/**
 * Conjunto Letras-Seleccion-1: one record returned by LRCLIB's search endpoint, reduced to the
 * fields the selection needs. `syncedLyrics` is null when the record has no synced lyrics.
 */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val durationSec: Double,
    val syncedLyrics: String?
)

/**
 * Conjunto Letras-Seleccion-1: pure rules (no Android, no network, no state) that choose WHICH of
 * several records returned for a song is the right one.
 *
 * LRCLIB is crowd-sourced: for one song it can hold many records, some complete, some damaged
 * (timestamps that go backwards, a tail of garbage lines), some for another edition of the song.
 * The direct lookup returns only one of them and we do not control which. This object decides,
 * from the full list, with rules that do not depend on LRCLIB: a candidate is acceptable only if it
 * is the same song (same artist and title), has about the same duration and a clean, complete
 * timeline; among the acceptable ones the closest duration wins, then the lowest id (the oldest,
 * most established record), so the choice is deterministic.
 */
object LyricsCandidatePicker {

    /**
     * Largest accepted difference (seconds) between the requested duration and a record's duration.
     * The requested duration comes truncated to whole seconds, so this also absorbs up to 1 s of
     * truncation. Our own rule: it does not depend on how LRCLIB matches durations.
     */
    const val DURATION_TOLERANCE_SEC = 2.0

    private val DIACRITICS = Regex("\\p{M}+")

    /** Canonical form used only to COMPARE names: ignores case and diacritics (accents). */
    fun fold(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

    /**
     * The best candidate for (artist, title, durationSec), or null if none is acceptable.
     * Acceptable = same artist and same title (both ignoring case and diacritics),
     * duration within DURATION_TOLERANCE_SEC, and synced lyrics whose timeline is fully coherent
     * (no line discarded by LyricsTimeline) with at least `minTimedLines` lines.
     */
    fun pick(
        candidates: List<LyricsCandidate>,
        artist: String,
        title: String,
        durationSec: Long,
        minTimedLines: Int
    ): LyricsCandidate? {
        val wantedArtist = fold(artist)
        val wantedTitle = fold(title)
        return candidates
            .filter { fold(it.artistName) == wantedArtist && fold(it.trackName) == wantedTitle }
            .filter { abs(it.durationSec - durationSec) <= DURATION_TOLERANCE_SEC }
            .filter { candidate ->
                val lrc = candidate.syncedLyrics
                if (lrc == null) {
                    false
                } else {
                    val parsed = LyricsTimeline.parse(lrc)
                    parsed.discardedCount == 0 && parsed.entries.size >= minTimedLines
                }
            }
            .sortedWith(compareBy({ abs(it.durationSec - durationSec) }, { it.id }))
            .firstOrNull()
    }
}
