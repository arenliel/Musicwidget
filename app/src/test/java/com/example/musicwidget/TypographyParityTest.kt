package arenliel.musicwidget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Conjunto Ajustes-Controles-1: paridad entre lo que DIBUJA el widget (res/values) y lo que el motor de
 * colisiones CALCULA (CollisionSensor). Si alguien cambia uno sin el otro, esta prueba falla: el motor
 * dejaría de ser consciente del espacio real. También impide que un recurso de tableta
 * (values-sw600dp) vuelva a desviar tamaños que el motor da por fijos.
 */
class TypographyParityTest {

    private fun resFile(relative: String): File =
        listOf("src/main/res/$relative", "app/src/main/res/$relative")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?: error("No se encontró $relative (directorio actual: ${File(".").absolutePath})")

    private fun dimenValue(name: String, unit: String): Float {
        val match = Regex("<dimen name=\"$name\">([0-9.]+)" + unit + "</dimen>").find(resFile("values/dimens.xml").readText())
            ?: error("No existe <dimen name=\"$name\"> en $unit")
        return match.groupValues[1].toFloat()
    }

    @Test
    fun tituloCoincideConElMotor() {
        assertEquals(CollisionSensor.TITLE_SIZE_SP, dimenValue("text_size_title", "sp"), 0.001f)
    }

    @Test
    fun artistaCoincideConElMotor() {
        assertEquals(CollisionSensor.ARTIST_SIZE_SP, dimenValue("text_size_artist", "sp"), 0.001f)
    }

    @Test
    fun estadoCoincideConElMotor() {
        assertEquals(CollisionSensor.STATUS_SIZE_SP, dimenValue("text_size_status", "sp"), 0.001f)
    }

    @Test
    fun estadoEnMayusculasCoincideConElMotorYEsMenorQueElNormal() {
        assertEquals(CollisionSensor.STATUS_CAPS_SIZE_SP, dimenValue("text_size_status_caps", "sp"), 0.001f)
        org.junit.Assert.assertTrue(CollisionSensor.STATUS_CAPS_SIZE_SP < CollisionSensor.STATUS_SIZE_SP)
    }

    @Test
    fun paddingDelWidgetCoincideConElMotor() {
        assertEquals(CollisionSensor.WIDGET_PADDING_TOTAL_DP, dimenValue("widget_padding", "dp") * 2f, 0.001f)
    }

    @Test
    fun tabletasNoSobreescribenLoQueElMotorDaPorFijo() {
        val tablet = listOf("src/main/res/values-sw600dp/dimens.xml", "app/src/main/res/values-sw600dp/dimens.xml")
            .map { File(it) }
            .firstOrNull { it.exists() } ?: return // sin archivo de tableta: cumple
        val text = tablet.readText()
        for (name in listOf("text_size_title", "text_size_artist", "text_size_status", "widget_padding")) {
            assertFalse("values-sw600dp no debe redefinir $name", text.contains("name=\"$name\""))
        }
    }
}
