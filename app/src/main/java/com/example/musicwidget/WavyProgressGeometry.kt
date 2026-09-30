package arenliel.musicwidget

import kotlin.math.PI
import kotlin.math.sin

/**
 * Conjunto Ajustes-BarraOnda-1: geometría PURA (sin Android) de la barra de progreso ondulada.
 * Medidas del componente "wavy progress indicator" de Material 3 Expressive: trazo 4dp, amplitud 3dp,
 * longitud de onda 40dp, alto del contenedor 10dp (= 2 x amplitud + trazo), hueco 4dp entre el tramo
 * recorrido y la pista, punto final de 4dp. La amplitud es 0 (línea recta) por debajo del 10 % y por
 * encima del 95 % de progreso, y siempre que no se esté reproduciendo.
 * Todas las coordenadas x están en dp, con el origen en el borde izquierdo de la barra.
 */
object WavyProgressGeometry {
    const val CONTAINER_HEIGHT_DP = 10f
    const val STROKE_DP = 4f
    const val AMPLITUDE_DP = 3f
    const val WAVELENGTH_DP = 40f
    // Conjunto Previews-Ajustes-2: WAVELENGTH_DP (40dp) queda como REFERENCIA del componente de Material 3 Expressive
    // (barras de 200dp o más) y como valor por defecto de waveY. En el widget la barra mide entre ~40 y ~90dp y con
    // 40dp la onda se sentía separada, así que la longitud real se adapta al ancho de la barra (ver wavelengthDp),
    // siempre entre MIN_WAVELENGTH_DP y MAX_WAVELENGTH_DP.
    const val MIN_WAVELENGTH_DP = 20f
    const val MAX_WAVELENGTH_DP = 24f
    const val WAVELENGTH_BAR_FRACTION = 0.4f
    const val GAP_DP = 4f
    const val STOP_SIZE_DP = 4f
    private const val WAVE_MIN_PROGRESS = 0.1f
    private const val WAVE_MAX_PROGRESS = 0.95f

    /** Trazos a dibujar. x son CENTROS de los extremos redondeados (el trazo sobresale STROKE_DP / 2). */
    data class BarLayout(
        val hasActive: Boolean,
        val activeStartX: Float,
        val activeEndX: Float,
        val hasTrack: Boolean,
        val trackStartX: Float,
        val trackEndX: Float,
        val showStop: Boolean,
        val stopCenterX: Float
    )

    fun amplitudeDp(progress: Float, isPlaying: Boolean): Float =
        if (isPlaying && progress > WAVE_MIN_PROGRESS && progress < WAVE_MAX_PROGRESS) AMPLITUDE_DP else 0f

    fun layout(widthDp: Float, progress: Float): BarLayout {
        val half = STROKE_DP / 2f
        val p = progress.coerceIn(0f, 1f)
        val hasActive = p > 0f
        val activeStart = half
        val activeEnd = (p * widthDp - GAP_DP / 2f).coerceIn(half, widthDp - half)
        val trackStart = if (hasActive) activeEnd + STROKE_DP + GAP_DP else half
        val trackEnd = widthDp - half
        val hasTrack = trackEnd > trackStart
        return BarLayout(
            hasActive = hasActive,
            activeStartX = activeStart,
            activeEndX = activeEnd,
            hasTrack = hasTrack,
            trackStartX = trackStart,
            trackEndX = trackEnd,
            showStop = hasTrack,
            stopCenterX = widthDp - STOP_SIZE_DP / 2f
        )
    }

    /** Longitud de onda (dp) para una barra de [barWidthDp]: 40 % del ancho, entre MIN_WAVELENGTH_DP y MAX_WAVELENGTH_DP. */
    fun wavelengthDp(barWidthDp: Float): Float =
        (barWidthDp * WAVELENGTH_BAR_FRACTION).coerceIn(MIN_WAVELENGTH_DP, MAX_WAVELENGTH_DP)

    /**
     * Conjunto Previews-Ajustes-2: ancho (dp) de la imagen que contiene SOLO el tramo recorrido (desde el borde izquierdo
     * de la barra hasta el borde derecho de su extremo redondeado). Lo usan por igual el renderizador (tamaño del bitmap)
     * y la UI (tamaño de la imagen), para que coincidan exactamente y no haya deformación.
     */
    fun activeSegmentWidthDp(barWidthDp: Float, progress: Float): Float {
        val w = barWidthDp.coerceAtLeast(STROKE_DP * 2f)
        return (layout(w, progress).activeEndX + STROKE_DP / 2f).coerceAtLeast(STROKE_DP)
    }

    /** Coordenada y (dp, desde arriba del contenedor) de la onda en [x]. Con amplitud 0 es la línea central. */
    fun waveY(x: Float, amplitudeDp: Float, wavelength: Float = WAVELENGTH_DP): Float {
        val phase = 2.0 * PI * (x - STROKE_DP / 2f) / wavelength
        return CONTAINER_HEIGHT_DP / 2f + amplitudeDp * sin(phase).toFloat()
    }
}
