package arenliel.musicwidget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidgetLayout { STACKED, FULL_BLEED }

data class CollisionResult(
    val pillSize: Dp,
    val maxArtistLines: Int,
    val layoutType: WidgetLayout,
    val hasCollision: Boolean
)

object CollisionSensor {
    fun evaluate(
        availableHeight: Float,
        fontScale: Float,
        isPreview: Boolean,
        appearance: WidgetAppearance
    ): CollisionResult {
        // 1. BLINDAJE DE IDENTIDAD: Determinismo absoluto para la variante SMALL (Full Cover)
        // Si el widget nació como SMALL, su contrato visual es inmutable.
        if (appearance == WidgetAppearance.SMALL) {
            return CollisionResult(
                pillSize = 0.dp, 
                maxArtistLines = 1, 
                layoutType = WidgetLayout.FULL_BLEED,
                hasCollision = false
            )
        }

        // 2. LÓGICA DEL SENSOR (Solo para variantes con carátula tipo píldora: STANDARD y CONTROL)
        val isLargeLayout = availableHeight >= 180f
        val isStandardIdentity = appearance == WidgetAppearance.PILL_STANDARD

        // Constantes SSOT (Single Source of Truth) para la física de colisión
        val tSizeSp = 16f
        val aSizeSp = 12f
        val sSizeSp = 10f
        val spacersH = 6f
        val paddingH = 34f
        val safetyGap = 12f
        val lineHeight = 1.3f

        val hTitle = (tSizeSp * fontScale) * lineHeight
        val hArtist = (aSizeSp * fontScale) * lineHeight
        val hStatus = (sSizeSp * fontScale) * lineHeight

        val textH1 = hTitle + hArtist + hStatus + spacersH
        val textH2 = hTitle + (hArtist * 2) + hStatus + spacersH

        // Fase A: Reducción de líneas. Umbral Premium 110dp.
        val projectedPillTwoLines = availableHeight - paddingH - textH2 - safetyGap
        val forceSingleLineArtist = projectedPillTwoLines < 110f
        val maxArtistLines = if (forceSingleLineArtist) 1 else 2

        // Fase B: Píldora Elástica (Cálculo reactivo del tamaño del asset)
        val activeTextH = if (forceSingleLineArtist) textH1 else textH2
        val calculatedPillValue = availableHeight - paddingH - activeTextH - safetyGap
        
        val pillSizeDp = calculatedPillValue.coerceIn(80f, 110f).dp
        val hasCollision = calculatedPillValue < 80f
        
        val layoutType = when {
            isLargeLayout -> WidgetLayout.STACKED
            // Si es preview de Standard, prohibimos el salto a Full-Bleed para no romper la identidad
            isPreview && isStandardIdentity -> WidgetLayout.STACKED
            hasCollision -> WidgetLayout.FULL_BLEED
            else -> WidgetLayout.STACKED
        }

        return CollisionResult(
            pillSize = pillSizeDp,
            maxArtistLines = maxArtistLines,
            layoutType = layoutType,
            hasCollision = hasCollision
        )
    }
}
