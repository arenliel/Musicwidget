package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conjunto Ajustes-BarraOnda-1: pruebas de la geometría pura de la barra ondulada (sin Android). */
class WavyProgressGeometryTest {

    @Test
    fun amplitud_soloReproduciendoYEntre10y95PorCiento() {
        assertEquals(3f, WavyProgressGeometry.amplitudeDp(0.5f, true), 0f)
        assertEquals(3f, WavyProgressGeometry.amplitudeDp(0.2f, true), 0f)
        assertEquals(0f, WavyProgressGeometry.amplitudeDp(0.5f, false), 0f)
        assertEquals(0f, WavyProgressGeometry.amplitudeDp(0.1f, true), 0f)
        assertEquals(0f, WavyProgressGeometry.amplitudeDp(0.95f, true), 0f)
        assertEquals(0f, WavyProgressGeometry.amplitudeDp(0f, true), 0f)
    }

    @Test
    fun alturaDelContenedor_esDosAmplitudesMasElTrazo() {
        assertEquals(
            WavyProgressGeometry.CONTAINER_HEIGHT_DP,
            2f * WavyProgressGeometry.AMPLITUDE_DP + WavyProgressGeometry.STROKE_DP,
            0f
        )
    }

    @Test
    fun progresoCero_soloHayPistaYPuntoFinal() {
        val l = WavyProgressGeometry.layout(100f, 0f)
        assertFalse(l.hasActive)
        assertTrue(l.hasTrack)
        assertEquals(2f, l.trackStartX, 0.001f)
        assertEquals(98f, l.trackEndX, 0.001f)
        assertTrue(l.showStop)
        assertEquals(98f, l.stopCenterX, 0.001f)
    }

    @Test
    fun progresoMitad_dejaUnHuecoDe4dpEntreTramoYPista() {
        val l = WavyProgressGeometry.layout(100f, 0.5f)
        assertTrue(l.hasActive)
        assertEquals(2f, l.activeStartX, 0.001f)
        assertEquals(48f, l.activeEndX, 0.001f)
        // Hueco visible entre extremos redondeados: (56 - 2) - (48 + 2) = 4dp
        assertEquals(56f, l.trackStartX, 0.001f)
        assertEquals(98f, l.trackEndX, 0.001f)
    }

    @Test
    fun progresoCompleto_noHayPistaNiPunto() {
        val l = WavyProgressGeometry.layout(100f, 1f)
        assertTrue(l.hasActive)
        assertEquals(98f, l.activeEndX, 0.001f)
        assertFalse(l.hasTrack)
        assertFalse(l.showStop)
    }

    @Test
    fun progresoMinimo_dibujaAlMenosUnPuntoRedondo() {
        val l = WavyProgressGeometry.layout(100f, 0.01f)
        assertTrue(l.hasActive)
        assertEquals(2f, l.activeEndX, 0.001f)
        assertEquals(10f, l.trackStartX, 0.001f)
    }

    @Test
    fun progresoFueraDeRango_seAcota() {
        val bajo = WavyProgressGeometry.layout(100f, -0.5f)
        val cero = WavyProgressGeometry.layout(100f, 0f)
        assertEquals(cero, bajo)
        val alto = WavyProgressGeometry.layout(100f, 2f)
        val uno = WavyProgressGeometry.layout(100f, 1f)
        assertEquals(uno, alto)
    }

    @Test
    fun onda_empiezaEnElCentroYAlcanzaLaAmplitud() {
        // Inicio de la onda en x = 2 (borde del tramo): y = centro (5dp)
        assertEquals(5f, WavyProgressGeometry.waveY(2f, 3f), 0.001f)
        // Cuarto de longitud de onda (10dp): cresta (5 + 3) y valle (5 - 3) a los 3/4
        assertEquals(8f, WavyProgressGeometry.waveY(12f, 3f), 0.001f)
        assertEquals(2f, WavyProgressGeometry.waveY(32f, 3f), 0.001f)
    }

    @Test
    fun ondaSinAmplitud_esLineaRecta() {
        assertEquals(5f, WavyProgressGeometry.waveY(2f, 0f), 0.001f)
        assertEquals(5f, WavyProgressGeometry.waveY(37f, 0f), 0.001f)
    }
}
