package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Conjunto Letras-Motor-Puro-1: tests for LyricsDisplayRules (pure logic, no Android).
 * Lyric texts are generic markers ("linea N"); what is tested is timing and state, not any song.
 */
class LyricsDisplayRulesTest {

    private fun entries(vararg stamps: Long): List<LyricsEntry> =
        stamps.withIndex().map { (i, ms) -> LyricsEntry(ms, "linea ${i + 1}") }

    private fun lrc(vararg stamps: Long): String =
        stamps.withIndex().joinToString("\n") { (i, ms) ->
            "[%02d:%02d.%02d]linea %d".format(ms / 60_000, (ms % 60_000) / 1000, (ms % 1000) / 10, i + 1)
        }

    // ---------- playing: the concrete cases ----------

    @Test
    fun antesDeLaPrimeraLinea_noHayLetra_yElSiguienteCambioEsLaPrimeraLinea() {
        val frame = LyricsDisplayRules.playing(entries(5_000, 8_000), 0)
        assertEquals("", frame.text)
        assertEquals(4_500L, frame.recheckInMs)
    }

    @Test
    fun enUnaLinea_muestraEsaLinea_hastaLaSiguiente() {
        val frame = LyricsDisplayRules.playing(entries(5_000, 8_000), 5_000)
        assertEquals("linea 1", frame.text)
        assertEquals(2_500L, frame.recheckInMs)
    }

    @Test
    fun adelantoDe500ms_laLineaApareceUnPocoAntes() {
        assertEquals("", LyricsDisplayRules.playing(entries(5_000, 8_000), 4_499).text)
        assertEquals("linea 1", LyricsDisplayRules.playing(entries(5_000, 8_000), 4_500).text)
    }

    @Test
    fun huecoLargo_laLineaSeMantiene10Segundos_yLuegoSilencio() {
        val e = entries(5_000, 40_000)
        val inicio = LyricsDisplayRules.playing(e, 4_500)
        assertEquals("linea 1", inicio.text)
        assertEquals(10_000L, inicio.recheckInMs)
        val silencio = LyricsDisplayRules.playing(e, 14_500)
        assertEquals("", silencio.text)
        assertEquals(25_000L, silencio.recheckInMs)
    }

    @Test
    fun huecoDeExactamente10Segundos_laLineaNoSeApaga_yUnMilisegundoMasSi() {
        // Same rule as the previous engine: only a gap LONGER than 10 s fades to silence.
        assertEquals("linea 1", LyricsDisplayRules.playing(entries(5_000, 15_000), 14_000).text)
        assertEquals("", LyricsDisplayRules.playing(entries(5_000, 15_001), 14_500).text)
    }

    @Test
    fun huecoCorto_laLineaSeMantieneHastaLaSiguiente() {
        val frame = LyricsDisplayRules.playing(entries(5_000, 15_000), 12_000)
        assertEquals("linea 1", frame.text)
        assertEquals(2_500L, frame.recheckInMs)
    }

    @Test
    fun despuesDeLaUltima_diezSegundosYSilencioParaSiempre() {
        val e = entries(5_000, 8_000)
        val enUltima = LyricsDisplayRules.playing(e, 7_500)
        assertEquals("linea 2", enUltima.text)
        assertEquals(10_000L, enUltima.recheckInMs)
        val fin = LyricsDisplayRules.playing(e, 17_500)
        assertEquals("", fin.text)
        assertNull(fin.recheckInMs)
    }

    @Test
    fun listaVacia_sinLetraYSinNuevasRevisiones() {
        val frame = LyricsDisplayRules.playing(emptyList(), 1_000)
        assertEquals("", frame.text)
        assertNull(frame.recheckInMs)
    }

    @Test
    fun marcasIguales_seMuestraLaUltimaDelGrupo() {
        val frame = LyricsDisplayRules.playing(entries(1_000, 2_000, 2_000, 3_000), 2_000)
        assertEquals("linea 3", frame.text)
    }

    @Test
    fun saltoDentroDeUnInstrumentalLargo_muestraSilencioDeInmediato() {
        // 30 s despues de la ultima linea cantada: ya no debe quedar esa linea en pantalla.
        val frame = LyricsDisplayRules.playing(entries(5_000, 90_000), 35_000)
        assertEquals("", frame.text)
    }

    // ---------- the user's scenario: intro, middle, and seeks ----------

    @Test
    fun saltarDeLaMitadAlInicioConIntroLargo_noDejaLaLineaVieja() {
        val e = entries(20_000, 24_000, 28_000, 90_000, 94_000, 98_000)
        // Mid-song: showing a late line.
        assertEquals("linea 5", LyricsDisplayRules.playing(e, 94_000).text)
        // Seek back to the start (inside the intro): no lyric, exactly as if the song had just started.
        assertEquals("", LyricsDisplayRules.playing(e, 2_000).text)
    }

    @Test
    fun formaRealDeRomeo_tras_sanear_avanzaYTerminaEnSilencio() {
        val good = longArrayOf(
            5_510, 7_430, 10_510, 13_540, 16_900, 18_130, 19_640, 22_660, 25_710, 29_030,
            31_360, 32_080, 34_850, 37_480, 37_980, 39_480, 42_200, 44_250, 47_320, 50_210,
            54_380, 56_500, 59_570
        )
        val tail = LongArray(22) { 1_000L } + LongArray(9) { 2_000L }
        val parsed = LyricsTimeline.parse(lrc(*(good + tail)))
        assertEquals(23, parsed.entries.size)
        assertEquals("linea 13", LyricsDisplayRules.playing(parsed.entries, 36_000).text)
        val end = LyricsDisplayRules.playing(parsed.entries, 70_000)
        assertEquals("", end.text)
        assertNull(end.recheckInMs)
    }

    // ---------- paused ----------

    @Test
    fun enPausa_muestraLaUltimaLineaAlcanzada_oElArtistaSegunLaAlternancia() {
        val e = entries(5_000, 8_000, 12_000)
        assertEquals("linea 2", LyricsDisplayRules.paused(e, 9_000, true, true))
        assertEquals("", LyricsDisplayRules.paused(e, 9_000, true, false))
    }

    @Test
    fun enPausaEnLosPrimeros5Segundos_conReproduccionReal_muestraLaPrimeraLinea() {
        val e = entries(10_000, 14_000)
        assertEquals("linea 1", LyricsDisplayRules.paused(e, 2_000, true, true))
    }

    @Test
    fun enPausaSinReproduccionConfirmada_noMuestraNada() {
        val e = entries(10_000, 14_000)
        assertEquals("", LyricsDisplayRules.paused(e, 2_000, false, true))
    }

    @Test
    fun enPausaPasadosLos5SegundosAntesDeLaPrimeraLinea_muestraArtista() {
        val e = entries(20_000, 24_000)
        assertEquals("", LyricsDisplayRules.paused(e, 8_000, true, true))
    }

    // ---------- properties ----------

    // Naive reference written straight from the written rule, to check the optimized one.
    private fun referenceText(entries: List<LyricsEntry>, positionMs: Long): String {
        val effective = positionMs + 500
        val index = entries.indexOfLast { it.timestampMs <= effective }
        if (index == -1) return ""
        val line = entries[index]
        val next = entries.getOrNull(index + 1)
        val nextIsSoon = next != null && next.timestampMs - line.timestampMs <= 10_000
        return if (nextIsSoon || effective < line.timestampMs + 10_000) line.text else ""
    }

    private fun randomEntries(random: Random, firstAtLeastMs: Long = 0L): List<LyricsEntry> {
        val size = 1 + random.nextInt(8)
        var t = firstAtLeastMs + random.nextInt(25_000).toLong()
        val stamps = LongArray(size) {
            val value = t
            t += listOf(0L, 300L, 1_500L, 4_000L, 9_000L, 12_000L, 30_000L)[random.nextInt(7)]
            value
        }
        return entries(*stamps)
    }

    @Test
    fun propiedad_barridoCompleto_coincideConLaReglaEscrita() {
        val random = Random(11)
        repeat(60) {
            val e = randomEntries(random)
            val end = e.last().timestampMs + 40_000
            var p = 0L
            while (p <= end) {
                assertEquals(referenceText(e, p), LyricsDisplayRules.playing(e, p).text)
                p += 97
            }
        }
    }

    @Test
    fun propiedad_laEsperaEsExacta_nuncaSeDuermeDeMasNiSeDespiertaSinMotivo() {
        val random = Random(23)
        repeat(60) {
            val e = randomEntries(random)
            val end = e.last().timestampMs + 40_000
            var p = 0L
            while (p <= end) {
                val frame = LyricsDisplayRules.playing(e, p)
                val wait = frame.recheckInMs
                if (wait == null) {
                    // Never changes again.
                    assertEquals(frame.text, LyricsDisplayRules.playing(e, p + 500_000).text)
                } else {
                    // Brute force: the first millisecond at which the shown text changes.
                    var exactDelta = 1L
                    while (LyricsDisplayRules.playing(e, p + exactDelta).text == frame.text && exactDelta < 600_000) exactDelta++
                    // It does not change before that moment...
                    var k = 1L
                    while (k < exactDelta) {
                        assertEquals(frame.text, LyricsDisplayRules.playing(e, p + k).text)
                        k += 37
                    }
                    // ...and the wait is exactly that moment, only stretched up to the floor when the
                    // change is due in less than MIN_RECHECK_MS (never a wait that is too short or long).
                    assertEquals(maxOf(exactDelta, LyricsDisplayRules.MIN_RECHECK_MS), wait)
                }
                p += 1_231
            }
        }
    }

    @Test
    fun propiedad_conSaltosYReinicios_laPantallaSiempreEsLaCorrecta() {
        // Simulates the loop: it writes the frame, sleeps `recheckInMs`, and a seek re-evaluates
        // immediately (as relaunchLyricsTicker does). At any sampled instant the screen must equal
        // the frame computed fresh for that position.
        val random = Random(5)
        repeat(80) {
            val e = randomEntries(random)
            val duration = e.last().timestampMs + 40_000
            var position = random.nextInt(duration.toInt()).toLong()
            var screen = LyricsDisplayRules.playing(e, position)
            repeat(60) {
                // Sample some instants while the loop sleeps.
                val sleep = (screen.recheckInMs ?: 5_000L)
                val sample = if (sleep > 1) random.nextInt(sleep.toInt()).toLong() else 0L
                // The screen may lag the truth by at most MIN_RECHECK_MS (the wait floor), never more.
                assertEquals(
                    "text at position ${position + sample}",
                    LyricsDisplayRules.playing(e, position + maxOf(0L, sample - LyricsDisplayRules.MIN_RECHECK_MS)).text,
                    screen.text
                )
                if (random.nextInt(4) == 0) {
                    // Seek / restart: new position, immediate re-evaluation.
                    position = if (random.nextBoolean()) 0L else random.nextInt(duration.toInt()).toLong()
                } else {
                    // The loop wakes up at the end of its sleep.
                    position += sleep
                }
                screen = LyricsDisplayRules.playing(e, position)
            }
        }
    }

    // Port of the decision logic of the previous engine (runLyricsShowcase before this set), only
    // for natural playback without seeks. Kept as a reference to prove equivalence.
    private fun previousEngineWrites(e: List<LyricsEntry>): List<Pair<Long, String>> {
        val writes = mutableListOf<Pair<Long, String>>()
        var t = 0L
        var guard = 0
        while (guard++ < 10_000) {
            val effective = t + 500
            val index = e.indexOfLast { it.timestampMs <= effective }
            if (index != -1) writes.add(t to e[index].text)
            val next = if (index != -1 && index < e.size - 1) e[index + 1] else null
            if (next != null) {
                val wait = (next.timestampMs - effective).coerceAtLeast(100L)
                if (wait > 10_000L) {
                    t += 10_000L
                    writes.add(t to "")
                    t += (wait - 10_000L).coerceAtLeast(100L)
                } else {
                    t += wait
                }
            } else if (index != -1) {
                t += 10_000L
                writes.add(t to "")
                break
            } else if (e.isNotEmpty()) {
                val wait = (e.first().timestampMs - effective).coerceAtLeast(100L)
                t += wait.coerceAtMost(10_000L)
            } else {
                break
            }
        }
        return writes
    }

    private fun newEngineWrites(e: List<LyricsEntry>): List<Pair<Long, String>> {
        val writes = mutableListOf<Pair<Long, String>>()
        var t = 0L
        var guard = 0
        while (guard++ < 10_000) {
            val frame = LyricsDisplayRules.playing(e, t)
            writes.add(t to frame.text)
            val wait = frame.recheckInMs ?: break
            t += wait.coerceAtMost(10_000L)
        }
        return writes
    }

    private fun visibleChanges(writes: List<Pair<Long, String>>): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        var shown = ""
        for ((time, text) in writes) {
            if (text != shown) {
                result.add(time to text)
                shown = text
            }
        }
        return result
    }

    // Equivalence holds for natural playback that starts BEFORE the first line (the normal case: a
    // song starts at 0 and its first line comes later). The only intended difference is when
    // playback starts in the middle of a line: the previous engine counted its 10 s from "now",
    // the new rules count them from the line's own timestamp (see the seek tests above).
    @Test
    fun equivalencia_enReproduccionNatural_mismasLineasYMismosMomentos() {
        val random = Random(99)
        repeat(200) {
            val e = randomEntries(random, firstAtLeastMs = 1_000L)
            val before = visibleChanges(previousEngineWrites(e))
            val after = visibleChanges(newEngineWrites(e))
            assertEquals("same visible texts, in order", before.map { it.second }, after.map { it.second })
            before.zip(after).forEach { (old, new) ->
                assertTrue("moment differs by more than 150 ms: ${old.first} vs ${new.first}", Math.abs(old.first - new.first) <= 150)
            }
        }
    }
}
