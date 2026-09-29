package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjunto Consolidacion-Wide-1: fija los umbrales de conmutación de layout que viven en el motor y demuestra
 * que la píldora fija de Wide equivale a la fórmula anterior en todos los estados en que el widget dibuja la
 * píldora. Si alguien cambia los umbrales del motor de forma que Wide pueda dibujarse con menos de
 * 34 + 110 dp de alto, estas pruebas fallan y obligan a decidirlo conscientemente.
 */
class CollisionSensorWideTest {

    private val fontScales = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)

    @Test
    fun constantesDeConmutacion_sonLasAcordadas() {
        assertEquals(80f, CollisionSensor.PILL_MIN_DP, 0f)
        assertEquals(110f, CollisionSensor.PILL_MAX_DP, 0f)
        assertEquals(110f, CollisionSensor.WIDE_PILL_SIZE_DP, 0f)
        assertEquals(180f, CollisionSensor.LARGE_LAYOUT_MIN_HEIGHT_DP, 0f)
        assertEquals(220f, CollisionSensor.WIDE_MIN_WIDTH_DP, 0f)
    }

    @Test
    fun wide_seDibujaDesdeLos220dpDeAncho() {
        assertFalse(CollisionSensor.isWideWidth(219.9f))
        assertTrue(CollisionSensor.isWideWidth(220f))
    }

    @Test
    fun evaluate_saltaAFullBleedBajoLos180dp_conFuenteNormal() {
        for (appearance in listOf(WidgetAppearance.PILL_STANDARD, WidgetAppearance.PILL_CONTROL)) {
            assertEquals(WidgetLayout.FULL_BLEED, CollisionSensor.evaluate(179.9f, 1.0f, false, appearance).layoutType)
            assertEquals(WidgetLayout.STACKED, CollisionSensor.evaluate(180f, 1.0f, false, appearance).layoutType)
        }
    }

    @Test
    fun wide_laPildoraSiempreMide110_enTodoEstadoDondeSeDibujaLaPildora() {
        // Antes la píldora de Wide era (alto - 34).coerceIn(80, 110). Aquí se comprueba que en todo estado en que
        // el motor NO manda a Full-Bleed (alto de 40 a 600dp, fontScale de 0.85 a 2.0, real y preview) esa fórmula
        // daba 110: la constante WIDE_PILL_SIZE_DP es equivalente.
        for (fontScale in fontScales) {
            for (i in 80..1200) {
                val h = i / 2f
                for (isPreview in listOf(false, true)) {
                    val r = CollisionSensor.evaluate(h, fontScale, isPreview, WidgetAppearance.PILL_CONTROL)
                    if (r.layoutType == WidgetLayout.STACKED) {
                        val oldFormula = (h - CollisionSensor.WIDGET_PADDING_TOTAL_DP).coerceIn(80f, 110f)
                        assertEquals(
                            "alto=$h fontScale=$fontScale preview=$isPreview",
                            CollisionSensor.WIDE_PILL_SIZE_DP,
                            oldFormula,
                            0f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun enVivo_standardYControlDecidenIgual() {
        // En el widget real Standard y Control son el mismo widget: el tamaño manda, no la apariencia.
        for (fontScale in fontScales) {
            for (i in 80..1200) {
                val h = i / 2f
                assertEquals(
                    "alto=$h fontScale=$fontScale",
                    CollisionSensor.evaluate(h, fontScale, false, WidgetAppearance.PILL_STANDARD),
                    CollisionSensor.evaluate(h, fontScale, false, WidgetAppearance.PILL_CONTROL)
                )
            }
        }
    }
}
