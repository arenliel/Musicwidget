package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Conjunto Letras-Robustez-1: pruebas de LyricsTimeline (lógica pura, sin Android).
 * Los textos de las letras son marcadores genéricos ("linea N"); lo que se prueba es la forma de
 * las marcas de tiempo, no el contenido de ninguna canción.
 */
class LyricsTimelineTest {

    private fun tag(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        val hundredths = (ms % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    private fun lrc(vararg stamps: Long): String =
        stamps.withIndex().joinToString("\n") { (i, ms) -> "${tag(ms)}linea ${i + 1}" }

    private fun entries(vararg stamps: Long): List<LyricsEntry> =
        stamps.withIndex().map { (i, ms) -> LyricsEntry(ms, "linea ${i + 1}") }

    @Test
    fun letraCoherente_seConservaCompleta() {
        val parsed = LyricsTimeline.parse(lrc(5_510, 7_430, 10_510, 13_540))
        assertEquals(4, parsed.entries.size)
        assertEquals(0, parsed.discardedCount)
        assertEquals(listOf(5_510L, 7_430L, 10_510L, 13_540L), parsed.entries.map { it.timestampMs })
    }

    @Test
    fun colaQueRetrocede_seDescarta_yLaLetraValidaSeConserva() {
        // Forma del registro real que dejó una canción "atascada": marcas crecientes y, al final,
        // líneas con marcas de 1 y 2 segundos.
        val stamps = longArrayOf(
            5_510, 7_430, 10_510, 13_540, 16_900, 18_130, 19_640, 22_660, 25_710, 29_030,
            31_360, 32_080, 34_850, 37_480, 37_980, 39_480, 42_200, 44_250, 47_320, 50_210,
            54_380, 56_500, 59_570, 1_000, 2_000
        )
        val parsed = LyricsTimeline.parse(lrc(*stamps))
        assertEquals(23, parsed.entries.size)
        assertEquals(2, parsed.discardedCount)
        assertEquals(59_570L, parsed.entries.last().timestampMs)
        assertTrue(parsed.entries.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
    }

    @Test
    fun colaLargaConMarcasRepetidas_noDesplazaALaLetraReal() {
        // Forma del registro real de LRCLIB que dejó una canción "atascada": 23 líneas buenas
        // (hasta 0:59.57) y una cola de 31 líneas cuyas marcas retroceden y se repiten (22 en
        // 0:01.00 y 9 en 0:02.00). Contadas como "no decrecientes", la cola (31) ganaría a la
        // letra real (23); la letra real debe ganar.
        val good = longArrayOf(
            5_510, 7_430, 10_510, 13_540, 16_900, 18_130, 19_640, 22_660, 25_710, 29_030,
            31_360, 32_080, 34_850, 37_480, 37_980, 39_480, 42_200, 44_250, 47_320, 50_210,
            54_380, 56_500, 59_570
        )
        val tail = LongArray(22) { 1_000L } + LongArray(9) { 2_000L }
        val parsed = LyricsTimeline.parse(lrc(*(good + tail)))
        assertEquals(23, parsed.entries.size)
        assertEquals(31, parsed.discardedCount)
        assertEquals(good.toList(), parsed.entries.map { it.timestampMs })
        // Con la letra saneada, la línea vigente a los 36 s es la 13.ª (marca 34.85 s), no una de la cola.
        assertEquals(12, LyricsTimeline.indexAt(parsed.entries, 36_000))
    }

    @Test
    fun lineasConLaMismaMarcaJuntoAUnaConservada_seConservan() {
        val parsed = LyricsTimeline.parse(lrc(1_000, 1_000, 1_000, 2_000, 2_000, 3_000))
        assertEquals(6, parsed.entries.size)
        assertEquals(0, parsed.discardedCount)
    }

    @Test
    fun lineaSueltaFueraDeOrden_enElMedio_seDescartaSinAfectarElResto() {
        val parsed = LyricsTimeline.parse(lrc(1_000, 2_000, 500, 3_000, 4_000))
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), parsed.entries.map { it.timestampMs })
        assertEquals(1, parsed.discardedCount)
    }

    @Test
    fun lineaAtipicaAlPrincipio_noArrastraAlRestoDeLaLetra() {
        val parsed = LyricsTimeline.parse(lrc(5_999_000, 1_000, 2_000, 3_000))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), parsed.entries.map { it.timestampMs })
        assertEquals(1, parsed.discardedCount)
    }

    @Test
    fun marcasIguales_seConservan() {
        val parsed = LyricsTimeline.parse(lrc(1_000, 1_000, 2_000))
        assertEquals(3, parsed.entries.size)
        assertEquals(0, parsed.discardedCount)
    }

    @Test
    fun lineasSinTextoYMetadatos_seIgnoran() {
        val raw = "[ar:Artista]\n[00:01.00]linea 1\n[00:02.00]   \n[00:03.00]linea 3\n"
        val parsed = LyricsTimeline.parse(raw)
        assertEquals(listOf(1_000L, 3_000L), parsed.entries.map { it.timestampMs })
        assertEquals(0, parsed.discardedCount)
    }

    @Test
    fun milisegundosDeTresDigitos_seInterpretanComoMilisegundos() {
        val parsed = LyricsTimeline.parse("[00:05.510]a\n[00:07.43]b")
        assertEquals(listOf(5_510L, 7_430L), parsed.entries.map { it.timestampMs })
    }

    @Test
    fun saltosDeLineaWindows_noDejanBasuraEnElTexto() {
        val parsed = LyricsTimeline.parse("[00:01.00]linea 1\r\n[00:02.00]linea 2\r\n")
        assertEquals(listOf("linea 1", "linea 2"), parsed.entries.map { it.text })
    }

    @Test
    fun textoVacio_devuelveListaVacia() {
        val parsed = LyricsTimeline.parse("")
        assertEquals(0, parsed.entries.size)
        assertEquals(0, parsed.discardedCount)
    }

    @Test
    fun indexAt_antesDeLaPrimeraLinea_esMenosUno() {
        assertEquals(-1, LyricsTimeline.indexAt(entries(5_000, 7_000), 4_999))
    }

    @Test
    fun indexAt_enLaMarcaExacta_devuelveEsaLinea() {
        assertEquals(0, LyricsTimeline.indexAt(entries(5_000, 7_000), 5_000))
        assertEquals(1, LyricsTimeline.indexAt(entries(5_000, 7_000), 7_000))
    }

    @Test
    fun indexAt_despuesDeLaUltima_devuelveLaUltima() {
        assertEquals(1, LyricsTimeline.indexAt(entries(5_000, 7_000), 999_999))
    }

    @Test
    fun indexAt_marcasIguales_devuelveLaUltimaDelGrupo() {
        assertEquals(2, LyricsTimeline.indexAt(entries(1_000, 2_000, 2_000, 3_000), 2_500))
    }

    @Test
    fun indexAt_listaVacia_esMenosUno() {
        assertEquals(-1, LyricsTimeline.indexAt(emptyList(), 10_000))
    }

    @Test
    fun indexAt_avanzaConLaPosicion_yNuncaSeQuedaEnUnaLineaDeCola() {
        val parsed = LyricsTimeline.parse(lrc(5_510, 7_430, 10_510, 59_570, 1_000, 2_000))
        // Antes del arreglo la línea vigente a los 30 s habría sido la última de la lista (la de
        // marca 2 s). Ahora es la última línea válida ya alcanzada.
        assertEquals(2, LyricsTimeline.indexAt(parsed.entries, 30_000))
        assertEquals(3, LyricsTimeline.indexAt(parsed.entries, 59_570))
    }

    @Test
    fun propiedad_resultadoEsCoherenteYConservaLaLineaDeTiempoMasLarga() {
        val random = Random(42)
        repeat(300) {
            val size = random.nextInt(40)
            val stamps = LongArray(size) { random.nextInt(60).toLong() * 1_000 }
            val parsed = LyricsTimeline.parse(lrc(*stamps))
            val result = parsed.entries.map { it.timestampMs }

            // 1. No decreciente.
            assertTrue(result.zipWithNext().all { (a, b) -> a <= b })
            // 2. Subsecuencia del original, en el mismo orden (los textos "linea N" son únicos).
            val originalOrder = parsed.entries.map { it.text.removePrefix("linea ").toInt() }
            assertTrue(originalOrder.zipWithNext().all { (a, b) -> a < b })
            // 3. Marcas distintas conservadas = subsecuencia estrictamente creciente más larga
            //    (comparada con programación dinámica O(n²)).
            val best = IntArray(size)
            var expected = 0
            for (i in 0 until size) {
                best[i] = 1
                for (j in 0 until i) if (stamps[j] < stamps[i]) best[i] = maxOf(best[i], best[j] + 1)
                expected = maxOf(expected, best[i])
            }
            assertEquals(expected, result.distinct().size)
            // 4. El conteo de descartadas cuadra y volver a interpretar el resultado no descarta nada.
            assertEquals(size - result.size, parsed.discardedCount)
            val again = LyricsTimeline.parse(lrc(*result.toLongArray()))
            assertEquals(result, again.entries.map { it.timestampMs })
            assertEquals(0, again.discardedCount)
        }
    }

    @Test
    fun propiedad_indexAtCoincideConLaBusquedaLineal() {
        val random = Random(7)
        repeat(300) {
            val size = random.nextInt(30)
            val stamps = LongArray(size) { random.nextInt(100).toLong() * 500 }.sortedArray()
            val list = entries(*stamps)
            val position = random.nextInt(60_000).toLong()
            val expected = list.indexOfLast { it.timestampMs <= position }
            assertEquals(expected, LyricsTimeline.indexAt(list, position))
        }
    }
}
