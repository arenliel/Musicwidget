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

/**
 * Conjunto Controles-Motor-1: decisión del motor sobre los controles de reproducción de Layout4x4.
 * - showControls: si se muestran (todo o nada: FAB play/pausa + grupo prev/next + barra).
 * - artistLines: líneas de artista/letra finales (puede bajar de 2 a 1 para que quepan los controles).
 * - showProgressBar: si además hay ancho suficiente para la barra de progreso.
 */
data class ControlsResult(
    val showControls: Boolean,
    val artistLines: Int,
    val showProgressBar: Boolean
)

object CollisionSensor {

    // Conjunto Ajustes-Motor-1: escala tipográfica y separaciones (SSOT). Son públicas para que la UI
    // (TextInfo, Layout2x1) las consuma en vez de duplicarlas: el motor y lo que se dibuja no pueden divergir.
    //  - Título 14sp (Title Small de Material 3); antes 16sp.
    //  - Estado (overline) 10sp: sin cambios.
    //  - Separación estado→título 2dp: forman UN solo encabezado (principio de proximidad).
    //  - Separación título→artista/letra 6dp: el contenido de apoyo/dinámico queda como grupo aparte.
    const val TITLE_SIZE_SP = 14f
    const val ARTIST_SIZE_SP = 12f
    const val STATUS_SIZE_SP = 10f
    const val LINE_HEIGHT_FACTOR = 1.3f
    const val OVERLINE_TITLE_GAP_DP = 2f
    const val TITLE_ARTIST_GAP_DP = 6f

    // Conjunto Controles-Motor-1: geometría de los controles (Layout4x4).
    const val CONTROLS_ROW_HEIGHT_DP = 32f          // Alto de la fila prev/next + barra
    const val CONTROLS_GAP_DP = 4f                  // Separación mínima texto de artista ↔ fila de controles
    const val CONTROLS_GROUP_WIDTH_DP = 74f         // Píldora prev/next: 2 × 36dp + 2dp de separador
    const val CONTROLS_BAR_GAP_DP = 12f             // Separación barra ↔ grupo prev/next (Ajustes: 12dp = misma separación que hay entre la portada y la columna de texto)
    const val CONTROLS_BAR_MIN_WIDTH_DP = 40f       // Ancho mínimo para que la barra se muestre
    const val PLAY_BUTTON_WIDTH_DP = 64f
    const val PLAY_BUTTON_HEIGHT_DP = 48f
    private const val PLAY_BUTTON_CLEARANCE_DP = 2f // Aire entre el botón play y el encabezado del historial
    const val WIDGET_PADDING_TOTAL_DP = 34f     // 17dp arriba + 17dp abajo (R.dimen.widget_padding × 2); una prueba verifica la paridad con dimens.xml
    private const val HISTORY_SPACER_DP = 16f       // Spacer entre la fila de píldora y el historial
    private const val HISTORY_HEADER_DP = 28f       // Encabezado del historial: ícono 20dp + 8dp de padding inferior

    // Conjunto Consolidacion-Wide-1: umbrales de conmutación de layout y tamaño de la píldora (SSOT).
    // Antes eran números sueltos repetidos en evaluate() y en MusicWidgetUI. Los valores NO cambian.
    //  - PILL_MIN_DP / PILL_MAX_DP: rango de la píldora elástica de Standard. Bajo PILL_MIN_DP hay colisión
    //    (Full-Bleed); PILL_MAX_DP es además el umbral "premium" para mantener 2 líneas de artista.
    //  - WIDE_PILL_SIZE_DP: en Wide el texto va AL LADO de la portada, así que la portada no es elástica: es
    //    siempre PILL_MAX_DP. (Antes: (alto - 34).coerceIn(80, 110); como Wide solo se dibuja desde
    //    LARGE_LAYOUT_MIN_HEIGHT_DP, esa fórmula siempre daba 110: una prueba lo demuestra.)
    //  - LARGE_LAYOUT_MIN_HEIGHT_DP: desde este alto el layout es STACKED aunque el cálculo elástico dé colisión.
    //  - WIDE_MIN_WIDTH_DP: desde este ancho el widget real se dibuja como Wide (el tamaño manda, no la apariencia).
    const val PILL_MIN_DP = 80f
    const val PILL_MAX_DP = 110f
    const val WIDE_PILL_SIZE_DP = PILL_MAX_DP
    const val LARGE_LAYOUT_MIN_HEIGHT_DP = 180f
    const val WIDE_MIN_WIDTH_DP = 220f

    /** Conjunto Consolidacion-Wide-1: `true` si el widget real debe dibujarse como Wide (texto al lado de la portada). */
    fun isWideWidth(widgetWidthDp: Float): Boolean = widgetWidthDp >= WIDE_MIN_WIDTH_DP

    // Conjunto Previews-Wide-1: geometría horizontal de Wide (SSOT). Antes el "12" vivía suelto en Layout4x4
    // (padding izquierdo de la columna de texto y su cálculo de ancho) y el ancho mínimo para la barra de
    // progreso no estaba nombrado en ningún sitio.
    //  - WIDE_TEXT_COLUMN_START_PADDING_DP: separación portada → columna de texto.
    //  - WIDE_FULL_PREVIEW_SIZE_DP: lado (cuadrado) de la previsualización generada en el que Wide se ve COMPLETO:
    //    controles, barra de progreso e historial. Debe ser >= minWideWidthForProgressBarDp() (una prueba lo verifica).
    const val WIDE_TEXT_COLUMN_START_PADDING_DP = 12f
    const val WIDE_FULL_PREVIEW_SIZE_DP = 300f

    /** Ancho (dp) de la columna de texto de Wide: ancho del widget - padding lateral - píldora - separación. */
    fun wideTextColumnWidthDp(widgetWidthDp: Float, pillSizeDp: Float = WIDE_PILL_SIZE_DP): Float =
        widgetWidthDp - WIDGET_PADDING_TOTAL_DP - pillSizeDp - WIDE_TEXT_COLUMN_START_PADDING_DP

    /** Ancho mínimo (dp) del widget Wide para que se muestre la barra de progreso (columna >= grupo + separación + 40dp). */
    fun minWideWidthForProgressBarDp(): Float =
        WIDGET_PADDING_TOTAL_DP + WIDE_PILL_SIZE_DP + WIDE_TEXT_COLUMN_START_PADDING_DP +
            CONTROLS_GROUP_WIDTH_DP + CONTROLS_BAR_GAP_DP + CONTROLS_BAR_MIN_WIDTH_DP

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
        val isLargeLayout = availableHeight >= LARGE_LAYOUT_MIN_HEIGHT_DP
        val isStandardIdentity = appearance == WidgetAppearance.PILL_STANDARD

        // Constantes SSOT (Single Source of Truth) para la física de colisión
        val tSizeSp = TITLE_SIZE_SP
        val aSizeSp = ARTIST_SIZE_SP
        val sSizeSp = STATUS_SIZE_SP
        val spacersH = OVERLINE_TITLE_GAP_DP + TITLE_ARTIST_GAP_DP
        val paddingH = WIDGET_PADDING_TOTAL_DP
        val safetyGap = 12f
        val lineHeight = LINE_HEIGHT_FACTOR

        val hTitle = (tSizeSp * fontScale) * lineHeight
        val hArtist = (aSizeSp * fontScale) * lineHeight
        val hStatus = (sSizeSp * fontScale) * lineHeight

        val textH1 = hTitle + hArtist + hStatus + spacersH
        val textH2 = hTitle + (hArtist * 2) + hStatus + spacersH

        // Fase A: Reducción de líneas. Umbral Premium 110dp.
        val projectedPillTwoLines = availableHeight - paddingH - textH2 - safetyGap
        val forceSingleLineArtist = projectedPillTwoLines < PILL_MAX_DP
        val maxArtistLines = if (forceSingleLineArtist) 1 else 2

        // Fase B: Píldora Elástica (Cálculo reactivo del tamaño del asset)
        val activeTextH = if (forceSingleLineArtist) textH1 else textH2
        val calculatedPillValue = availableHeight - paddingH - activeTextH - safetyGap
        
        val pillSizeDp = calculatedPillValue.coerceIn(PILL_MIN_DP, PILL_MAX_DP).dp
        val hasCollision = calculatedPillValue < PILL_MIN_DP
        
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

    /**
     * Conjunto Controles-Motor-1 / Ajustes-Motor-1: alto (dp) del bloque superior de texto (overline + título).
     * Es el "ecuador visual" fijo: no depende del contenido, solo del fontScale.
     */
    fun topBlockHeightDp(fontScale: Float): Float =
        ((STATUS_SIZE_SP + TITLE_SIZE_SP) * fontScale * LINE_HEIGHT_FACTOR) + OVERLINE_TITLE_GAP_DP

    /**
     * Conjunto Ajustes-Motor-1: alto (dp) reservado para el texto de artista/letra con [lines] líneas.
     * Incluye la separación título→artista ([TITLE_ARTIST_GAP_DP]), que TextInfo dibuja al inicio del
     * segmento BOTTOM.
     */
    fun artistBlockHeightDp(fontScale: Float, lines: Int): Float =
        TITLE_ARTIST_GAP_DP + ARTIST_SIZE_SP * fontScale * LINE_HEIGHT_FACTOR * lines

    /**
     * Conjunto Ajustes-Motor-1: alto (dp) de la pila completa de texto (TextInfo en modo ALL).
     * [withStatus] = false quita el overline y su separación. Fuente única para Layout2x1.
     */
    fun textStackHeightDp(fontScale: Float, withStatus: Boolean, artistLines: Int): Float {
        val statusH = if (withStatus) STATUS_SIZE_SP * fontScale * LINE_HEIGHT_FACTOR + OVERLINE_TITLE_GAP_DP else 0f
        return statusH + TITLE_SIZE_SP * fontScale * LINE_HEIGHT_FACTOR + artistBlockHeightDp(fontScale, artistLines)
    }

    /**
     * Conjunto Ajustes-Motor-1: alto mínimo del widget para que el botón play/pausa (flotante, esquina
     * inferior derecha) NO se solape con la fila de controles prev/next + barra, que termina a
     * padding + [pillSizeDp] desde arriba. Criterio: el botón nunca puede tapar controles.
     */
    fun minWidgetHeightForControlsDp(pillSizeDp: Float): Float =
        WIDGET_PADDING_TOTAL_DP + pillSizeDp + CONTROLS_GAP_DP + PLAY_BUTTON_HEIGHT_DP

    /**
     * Conjunto Ajustes-Motor-1: `true` si el botón play/pausa queda sobre el encabezado del historial
     * (su botón de limpiar quedaría tapado). La UI reserva entonces ancho al final del encabezado.
     */
    fun fabOverlapsHistoryHeader(widgetHeightDp: Float, pillSizeDp: Float): Boolean =
        widgetHeightDp < WIDGET_PADDING_TOTAL_DP + pillSizeDp + HISTORY_SPACER_DP + HISTORY_HEADER_DP +
            PLAY_BUTTON_HEIGHT_DP + PLAY_BUTTON_CLEARANCE_DP

    /**
     * Conjunto Controles-Motor-1: decide si Layout4x4 muestra los controles de reproducción.
     *
     * Reglas (todo o nada — nunca se muestra un subconjunto de los botones):
     *  1. El botón play/pausa flota en la esquina inferior derecha: solo se muestra si el widget es lo
     *     bastante alto para que NO se solape con la fila de controles prev/next + barra
     *     ([minWidgetHeightForControlsDp]). Puede quedar sobre el encabezado del historial: eso lo
     *     resuelve la UI reservando ancho ([fabOverlapsHistoryHeader]), no ocultando los controles.
     *  2. Bloque superior + texto de artista + separación + fila de controles deben caber en la
     *     altura de la píldora. Se intenta con las líneas de artista que decidió evaluate(); si no
     *     caben y eran 2, se baja a 1; si con 1 tampoco caben, no se muestran controles.
     *  3. La barra de progreso solo se muestra si, junto al grupo prev/next, le quedan al menos
     *     CONTROLS_BAR_MIN_WIDTH_DP de ancho dentro de la columna de texto.
     *
     * Función pura (sin Android): verificable con pruebas unitarias.
     */
    fun evaluateControls(
        widgetHeightDp: Float,
        pillSizeDp: Float,
        fontScale: Float,
        sensorMaxArtistLines: Int,
        textColumnWidthDp: Float
    ): ControlsResult {
        if (widgetHeightDp < minWidgetHeightForControlsDp(pillSizeDp)) {
            return ControlsResult(showControls = false, artistLines = sensorMaxArtistLines, showProgressBar = false)
        }

        val top = topBlockHeightDp(fontScale)
        fun needed(lines: Int): Float =
            top + artistBlockHeightDp(fontScale, lines) + CONTROLS_GAP_DP + CONTROLS_ROW_HEIGHT_DP

        var lines = sensorMaxArtistLines
        if (lines >= 2 && needed(2) > pillSizeDp) lines = 1
        if (needed(lines) > pillSizeDp) {
            return ControlsResult(showControls = false, artistLines = sensorMaxArtistLines, showProgressBar = false)
        }

        val barWidth = textColumnWidthDp - CONTROLS_GROUP_WIDTH_DP - CONTROLS_BAR_GAP_DP
        return ControlsResult(
            showControls = true,
            artistLines = lines,
            showProgressBar = barWidth >= CONTROLS_BAR_MIN_WIDTH_DP
        )
    }
}
