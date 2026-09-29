package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjunto Controles-Motor-1: pruebas de la decisión del motor sobre los controles de Layout4x4.
 * Valores esperados calculados a mano con las constantes del motor (interlineado 1.3, píldora 110dp,
 * controles 32dp + 4dp de separación).
 */
class CollisionSensorControlsTest {

    private fun eval(height: Float, fontScale: Float, lines: Int, columnWidth: Float, pill: Float = 110f) =
        CollisionSensor.evaluateControls(
            widgetHeightDp = height,
            pillSizeDp = pill,
            fontScale = fontScale,
            sensorMaxArtistLines = lines,
            textColumnWidthDp = columnWidth
        )

    @Test
    fun fuenteNormal_widgetAlto_muestraControlesConDosLineasYBarra() {
        val r = eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 144f)
        assertTrue(r.showControls)
        assertEquals(2, r.artistLines)
        assertTrue(r.showProgressBar)
    }

    @Test
    fun fuente115_bajaAUnaLineaPeroMantieneControles() {
        val r = eval(height = 250f, fontScale = 1.15f, lines = 2, columnWidth = 144f)
        assertTrue(r.showControls)
        assertEquals(1, r.artistLines)
    }

    @Test
    fun fuente130_unaLineaConControles() {
        val r = eval(height = 250f, fontScale = 1.3f, lines = 2, columnWidth = 144f)
        assertTrue(r.showControls)
        assertEquals(1, r.artistLines)
    }

    @Test
    fun fuente150_ocultaControles() {
        val r = eval(height = 300f, fontScale = 1.5f, lines = 2, columnWidth = 144f)
        assertFalse(r.showControls)
        assertEquals(2, r.artistLines)
        assertFalse(r.showProgressBar)
    }

    @Test
    fun alturaMenorAlMinimoDelBotonPlay_ocultaControles() {
        // Mínimo con píldora 110dp: 34 + 110 + 16 + 28 + 48 + 2 = 238dp
        assertFalse(eval(height = 237.9f, fontScale = 1.0f, lines = 2, columnWidth = 144f).showControls)
        assertTrue(eval(height = 238f, fontScale = 1.0f, lines = 2, columnWidth = 144f).showControls)
    }

    @Test
    fun columnaAngosta_ocultaSoloLaBarra() {
        val r = eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 94f)
        assertTrue(r.showControls)
        assertFalse(r.showProgressBar)
    }

    @Test
    fun umbralDeAnchoDeLaBarra_esExactamente40dp() {
        // barra = columna - 74 (grupo prev/next) - 8 (separación)
        assertTrue(eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 122f).showProgressBar)
        assertFalse(eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 121.9f).showProgressBar)
    }

    @Test
    fun ecuadorFijo_noDependeDelContenido() {
        assertEquals(37.8f, CollisionSensor.topBlockHeightDp(1.0f), 0.01f)
        assertEquals(15.6f, CollisionSensor.artistBlockHeightDp(1.0f, 1), 0.01f)
        assertEquals(31.2f, CollisionSensor.artistBlockHeightDp(1.0f, 2), 0.01f)
    }
}
