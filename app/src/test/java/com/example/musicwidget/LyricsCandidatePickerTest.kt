package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjunto Letras-Seleccion-1: tests for LyricsCandidatePicker (pure logic, no Android, no network).
 * Texts are generic markers; what is tested is selection, not any particular song.
 */
class LyricsCandidatePickerTest {

    private fun stamp(ms: Long) = "[%02d:%02d.%02d]".format(ms / 60_000, (ms % 60_000) / 1000, (ms % 1000) / 10)

    /** A clean LRC with `lines` lines, one every `stepMs`, starting at `startMs`. */
    private fun cleanLrc(lines: Int, stepMs: Long = 3_000, startMs: Long = 5_000): String =
        (0 until lines).joinToString("\n") { "${stamp(startMs + it * stepMs)}linea ${it + 1}" }

    /**
     * Shape of a real damaged record: a good first part, then a tail of lines whose timestamps go
     * back to 0:01.00 and 0:02.00 (22 and 9 lines).
     */
    private fun damagedLrc(): String {
        val good = (0 until 23).joinToString("\n") { "${stamp(5_000L + it * 2_500)}buena ${it + 1}" }
        val tail1 = (0 until 22).joinToString("\n") { "[00:01.00]cola a ${it + 1}" }
        val tail2 = (0 until 9).joinToString("\n") { "[00:02.00]cola b ${it + 1}" }
        return good + "\n" + tail1 + "\n" + tail2
    }

    private fun cand(
        id: Long,
        durationSec: Double,
        lrc: String? = cleanLrc(42),
        artist: String = "Artista Uno",
        title: String = "Cancion Uno"
    ) = LyricsCandidate(id, title, artist, durationSec, lrc)

    private fun pick(list: List<LyricsCandidate>, title: String = "Cancion Uno", durationSec: Long = 154, artist: String = "Artista Uno") =
        LyricsCandidatePicker.pick(list, artist, title, durationSec, 3)

    // ---------- pick: the case that motivated this set ----------

    @Test
    fun registroDanado_noSeElige_yGanaElCompletoConLaMismaDuracion() {
        // Same shape as the real case: the damaged record has the closest duration (154.25) but its
        // timeline goes backwards; the complete one (154.0) wins.
        val danado = cand(21320239, 154.253061, damagedLrc())
        val completo = cand(35258159, 154.0)
        val otros = listOf(cand(36875759, 132.0), cand(34385221, 211.0), cand(35728762, 2.0), cand(33607858, 186.0))
        assertEquals(35258159L, pick(otros + danado + completo)?.id)
    }

    @Test
    fun soloHayRegistroDanado_noSeElige_ningunoSirve() {
        assertNull(pick(listOf(cand(1, 154.25, damagedLrc()))))
    }

    // ---------- pick: identity ----------

    @Test
    fun otroArtista_noSeElige() {
        assertNull(pick(listOf(cand(1, 154.0, artist = "Otro Artista"))))
    }

    @Test
    fun otraCancion_noSeElige() {
        assertNull(pick(listOf(cand(1, 154.0, title = "Otra Cancion"))))
    }

    @Test
    fun mayusculasYTildes_noImportan() {
        val records = listOf(cand(1, 154.0, artist = "ARTÍSTA UNO", title = "CANCIÓN UNO"))
        assertEquals(1L, pick(records, title = "cancion uno", artist = "artista uno")?.id)
    }

    // ---------- pick: duration ----------

    @Test
    fun duracionFueraDeTolerancia_noSeElige() {
        assertNull(pick(listOf(cand(1, 158.0)), durationSec = 154))
    }

    @Test
    fun duracionExactamenteEnElLimite_seAcepta_yUnPocoMas_no() {
        assertEquals(1L, pick(listOf(cand(1, 156.0)), durationSec = 154)?.id)
        assertNull(pick(listOf(cand(1, 156.01)), durationSec = 154))
    }

    @Test
    fun ganaLaDuracionMasCercana() {
        val records = listOf(cand(5, 155.5), cand(6, 154.2), cand(7, 152.5))
        assertEquals(6L, pick(records, durationSec = 154)?.id)
    }

    @Test
    fun empateDeDuracion_ganaElIdMasBajo_sinImportarElOrden() {
        val a = cand(30, 154.0); val b = cand(20, 154.0); val c = cand(40, 154.0)
        assertEquals(20L, pick(listOf(a, b, c))?.id)
        assertEquals(20L, pick(listOf(c, b, a))?.id)
    }

    // ---------- pick: content ----------

    @Test
    fun sinLetraSincronizada_noSeElige() {
        assertNull(pick(listOf(cand(1, 154.0, lrc = null))))
    }

    @Test
    fun letraConMuyPocasLineas_noSeElige() {
        assertNull(pick(listOf(cand(1, 154.0, lrc = cleanLrc(2)))))
        assertEquals(1L, pick(listOf(cand(1, 154.0, lrc = cleanLrc(3))))?.id)
    }

    @Test
    fun listaVacia_devuelveNull() {
        assertNull(pick(emptyList()))
    }

    @Test
    fun laLetraElegida_esLaDelRegistroElegido() {
        val limpio = cleanLrc(10)
        val chosen = pick(listOf(cand(2, 154.0, damagedLrc()), cand(3, 153.5, limpio)))
        assertEquals(limpio, chosen?.syncedLyrics)
        assertTrue(LyricsTimeline.parse(chosen!!.syncedLyrics!!).discardedCount == 0)
    }
}
