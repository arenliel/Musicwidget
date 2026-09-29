package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjuntos Controles-Motor-1 / Ajustes-Motor-1: pruebas de la decisión del motor sobre los controles de
 * Layout4x4. Valores esperados calculados a mano con las constantes del motor: interlineado 1.3,
 * título 14sp / artista 12sp / estado 10sp, separaciones 2dp (estado→título) y 6dp (título→artista),
 * píldora 110dp, controles 32dp + 4dp de separación.
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
    fun fuente140_sigueMostrandoControles() {
        // Necesita 109.52dp de los 110dp de la píldora.
        val r = eval(height = 250f, fontScale = 1.4f, lines = 2, columnWidth = 144f)
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
    fun alturaMinimaDeControles_conPildoraMaxima_esExactamente196dp() {
        // 34 (padding) + 110 (píldora) + 4 (separación) + 48 (botón play) = 196dp
        assertEquals(196f, CollisionSensor.minWidgetHeightForControlsDp(110f), 0.001f)
        assertFalse(eval(height = 195.9f, fontScale = 1.0f, lines = 2, columnWidth = 144f).showControls)
        assertTrue(eval(height = 196f, fontScale = 1.0f, lines = 2, columnWidth = 144f).showControls)
    }

    @Test
    fun alturaMinimaDeControles_dependeDeLaPildora() {
        // Píldora 100dp: 34 + 100 + 4 + 48 = 186dp. Con 2 líneas no caben (106.4 > 100): baja a 1 (90.8).
        assertFalse(eval(height = 185.9f, fontScale = 1.0f, lines = 2, columnWidth = 144f, pill = 100f).showControls)
        val r = eval(height = 186f, fontScale = 1.0f, lines = 2, columnWidth = 144f, pill = 100f)
        assertTrue(r.showControls)
        assertEquals(1, r.artistLines)
    }

    @Test
    fun columnaAngosta_ocultaSoloLaBarra() {
        val r = eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 94f)
        assertTrue(r.showControls)
        assertFalse(r.showProgressBar)
    }

    @Test
    fun umbralDeAnchoDeLaBarra_esExactamente40dp() {
        // barra = columna - 74 (grupo prev/next) - 12 (separación)
        assertTrue(eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 126f).showProgressBar)
        assertFalse(eval(height = 250f, fontScale = 1.0f, lines = 2, columnWidth = 125.9f).showProgressBar)
    }

    @Test
    fun ecuadorFijo_noDependeDelContenido() {
        // (10 + 14) * 1.3 + 2 = 33.2
        assertEquals(33.2f, CollisionSensor.topBlockHeightDp(1.0f), 0.01f)
        // 6 + 12 * 1.3 * lineas
        assertEquals(21.6f, CollisionSensor.artistBlockHeightDp(1.0f, 1), 0.01f)
        assertEquals(37.2f, CollisionSensor.artistBlockHeightDp(1.0f, 2), 0.01f)
    }

    @Test
    fun alturaDeLaPilaDeTexto() {
        // con estado, 1 línea: (10 + 14 + 12) * 1.3 + 2 + 6 = 54.8
        assertEquals(54.8f, CollisionSensor.textStackHeightDp(1.0f, withStatus = true, artistLines = 1), 0.01f)
        // sin estado, 1 línea: (14 + 12) * 1.3 + 6 = 39.8
        assertEquals(39.8f, CollisionSensor.textStackHeightDp(1.0f, withStatus = false, artistLines = 1), 0.01f)
        // con estado, 2 líneas: 54.8 + 15.6 = 70.4
        assertEquals(70.4f, CollisionSensor.textStackHeightDp(1.0f, withStatus = true, artistLines = 2), 0.01f)
    }

    @Test
    fun botonPlaySolapaElEncabezadoDelHistorial_soloBajo238dp() {
        // 34 + 110 + 16 (espacio) + 28 (encabezado) + 48 (botón) + 2 (aire) = 238dp
        assertTrue(CollisionSensor.fabOverlapsHistoryHeader(237.9f, 110f))
        assertFalse(CollisionSensor.fabOverlapsHistoryHeader(238f, 110f))
    }

    @Test
    fun evaluate_cambiaAUnaLineaBajoLos226punto4dp() {
        // Texto con 2 líneas: (14 + 24 + 10) * 1.3 + 8 = 70.4; umbral: 110 + 34 + 70.4 + 12 = 226.4dp
        assertEquals(1, CollisionSensor.evaluate(226.3f, 1.0f, false, WidgetAppearance.PILL_CONTROL).maxArtistLines)
        assertEquals(2, CollisionSensor.evaluate(226.6f, 1.0f, false, WidgetAppearance.PILL_CONTROL).maxArtistLines)
    }

    @Test
    fun integracion_losControlesAparecenDesdeLos196dp() {
        // En Wide la píldora es fija (WIDE_PILL_SIZE_DP = 110dp), así que la altura mínima de los controles es
        // 34 + 110 + 4 + 48 = 196dp. Con 1 línea (alto < 226.4dp) texto + controles necesitan 90.8dp de los 110.
        fun controlsAt(h: Float): Boolean {
            val s = CollisionSensor.evaluate(h, 1.0f, false, WidgetAppearance.PILL_CONTROL)
            return CollisionSensor.evaluateControls(h, CollisionSensor.WIDE_PILL_SIZE_DP, 1.0f, s.maxArtistLines, 144f).showControls
        }
        assertFalse(controlsAt(195.9f))
        assertTrue(controlsAt(196f))
    }

    @Test
    fun constantesTipograficas_sonLasAcordadas() {
        assertEquals(14f, CollisionSensor.TITLE_SIZE_SP, 0f)
        assertEquals(12f, CollisionSensor.ARTIST_SIZE_SP, 0f)
        assertEquals(10f, CollisionSensor.STATUS_SIZE_SP, 0f)
        assertEquals(2f, CollisionSensor.OVERLINE_TITLE_GAP_DP, 0f)
        assertEquals(6f, CollisionSensor.TITLE_ARTIST_GAP_DP, 0f)
        assertEquals(12f, CollisionSensor.CONTROLS_BAR_GAP_DP, 0f)
    }
}
