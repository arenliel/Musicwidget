package arenliel.musicwidget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjunto Previews-Wide-1: paridad entre lo que CALCULA el motor de colisiones (CollisionSensor) y lo que
 * DIBUJAN el esqueleto de carga de Wide y la previsualización generada. Si alguien cambia una medida del
 * motor sin actualizar el XML (o al revés), estas pruebas fallan. También verifica que la píldora del
 * esqueleto sea exactamente la misma cápsula inclinada que la de la previsualización.
 */
class WidePreviewParityTest {

    private fun resFile(relative: String): File =
        listOf("src/main/res/$relative", "app/src/main/res/$relative")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?: error("No se encontró $relative (directorio actual: ${File(".").absolutePath})")

    /** Etiqueta XML completa (de "<" a ">") del elemento que declara el id indicado. */
    private fun tagWithId(xml: String, id: String): String =
        Regex("<[A-Za-z]+[^>]*android:id=\"@\\+id/$id\"[^>]*>").find(xml)?.value
            ?: error("No existe un elemento con el id $id")

    private fun dp(tag: String, attribute: String): Float {
        val match = Regex("android:$attribute=\"([0-9.]+)dp\"").find(tag)
            ?: error("La etiqueta no declara $attribute en dp: $tag")
        return match.groupValues[1].toFloat()
    }

    private fun vectorGeometry(name: String): List<String> {
        val text = resFile("drawable/$name.xml").readText()
        fun grab(attribute: String): String =
            Regex("android:$attribute=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")?.trim()
                ?: error("$name no declara $attribute")
        return listOf("width", "height", "viewportWidth", "viewportHeight", "pivotX", "pivotY", "rotation", "pathData").map { grab(it) }
    }

    @Test
    fun esqueletoDeLaPortada_esLaMismaCapsulaInclinadaQueElPreview() {
        assertEquals(vectorGeometry("ic_preview_pill"), vectorGeometry("preview_skeleton_art"))
    }

    @Test
    fun esqueletoLarge_mideLoMismoQueElMotor() {
        val xml = resFile("layout/glance_loading_large.xml").readText()

        val art = tagWithId(xml, "loading_art_container_large")
        assertEquals(CollisionSensor.WIDE_PILL_SIZE_DP, dp(art, "layout_width"), 0f)
        assertEquals(CollisionSensor.WIDE_PILL_SIZE_DP, dp(art, "layout_height"), 0f)

        val column = tagWithId(xml, "skeleton_text_column")
        assertEquals(CollisionSensor.WIDE_TEXT_COLUMN_START_PADDING_DP, dp(column, "layout_marginStart"), 0f)

        val row = tagWithId(xml, "skeleton_controls_row")
        assertEquals(CollisionSensor.CONTROLS_ROW_HEIGHT_DP, dp(row, "layout_height"), 0f)

        val group = tagWithId(xml, "skeleton_controls_group")
        assertEquals(CollisionSensor.CONTROLS_GROUP_WIDTH_DP, dp(group, "layout_width"), 0f)
        assertEquals(CollisionSensor.CONTROLS_ROW_HEIGHT_DP, dp(group, "layout_height"), 0f)
        assertEquals(CollisionSensor.CONTROLS_BAR_GAP_DP, dp(group, "layout_marginStart"), 0f)

        val play = tagWithId(xml, "skeleton_play_button")
        assertEquals(CollisionSensor.PLAY_BUTTON_WIDTH_DP, dp(play, "layout_width"), 0f)
        assertEquals(CollisionSensor.PLAY_BUTTON_HEIGHT_DP, dp(play, "layout_height"), 0f)
    }

    @Test
    fun esqueletosConPildora_usanLaCapsulaInclinada() {
        assertTrue(resFile("layout/glance_loading_large.xml").readText().contains("@drawable/preview_skeleton_art"))
        assertTrue(resFile("layout/glance_loading_standard.xml").readText().contains("@drawable/preview_skeleton_art"))
    }

    @Test
    fun motor_anchoDeLaColumnaDeTextoYUmbralDeLaBarra() {
        assertEquals(12f, CollisionSensor.WIDE_TEXT_COLUMN_START_PADDING_DP, 0f)
        assertEquals(94f, CollisionSensor.wideTextColumnWidthDp(250f), 0.001f)
        assertEquals(144f, CollisionSensor.wideTextColumnWidthDp(300f), 0.001f)
        // 34 (padding) + 110 (píldora) + 12 (columna) + 74 (grupo prev/next) + 12 (separación) + 40 (barra mínima) = 282dp
        assertEquals(282f, CollisionSensor.minWideWidthForProgressBarDp(), 0.001f)
        fun barAt(width: Float): Boolean =
            CollisionSensor.evaluateControls(300f, 110f, 1.0f, 2, CollisionSensor.wideTextColumnWidthDp(width)).showProgressBar
        assertFalse(barAt(281.9f))
        assertTrue(barAt(282f))
    }

    @Test
    fun previewGenerada_alcanzaParaMostrarWideConControlesYBarra() {
        val size = CollisionSensor.WIDE_FULL_PREVIEW_SIZE_DP
        assertTrue(size >= CollisionSensor.minWideWidthForProgressBarDp())
        assertTrue(size >= CollisionSensor.minWidgetHeightForControlsDp(CollisionSensor.WIDE_PILL_SIZE_DP))
        assertFalse(CollisionSensor.fabOverlapsHistoryHeader(size, CollisionSensor.WIDE_PILL_SIZE_DP))
        val sensor = CollisionSensor.evaluate(size, 1.0f, true, WidgetAppearance.PILL_CONTROL)
        assertEquals(WidgetLayout.STACKED, sensor.layoutType)
        val controls = CollisionSensor.evaluateControls(
            size, CollisionSensor.WIDE_PILL_SIZE_DP, 1.0f, sensor.maxArtistLines, CollisionSensor.wideTextColumnWidthDp(size)
        )
        assertTrue(controls.showControls)
        assertTrue(controls.showProgressBar)
    }
}
