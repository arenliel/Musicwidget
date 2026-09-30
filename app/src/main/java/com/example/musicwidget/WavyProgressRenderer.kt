package arenliel.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conjunto Ajustes-BarraOnda-1: dibuja la barra de progreso ondulada como Bitmap (Glance no tiene lienzo).
 * El bitmap mide exactamente el ancho de la barra (dp x densidad) y 10dp de alto: se muestra con
 * ContentScale.FillBounds, sin deformación. Caché pequeña (los widgets se redibujan con poca frecuencia);
 * el progreso se cuantiza a medio dp, que es invisible y permite reutilizar bitmaps.
 */
object WavyProgressRenderer {
    private val cache = LruCache<String, Bitmap>(8)

    @Synchronized
    fun render(
        context: Context,
        widthDp: Float,
        progress: Float,
        isPlaying: Boolean,
        activeColor: Int,
        trackColor: Int,
        stopColor: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val wDp = widthDp.coerceAtLeast(WavyProgressGeometry.STROKE_DP * 2f)
        val wPx = (wDp * density).roundToInt().coerceAtLeast(1)
        val hPx = (WavyProgressGeometry.CONTAINER_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
        val bucket = (progress.coerceIn(0f, 1f) * wDp * 2f).roundToInt()
        val p = (bucket / (wDp * 2f)).coerceIn(0f, 1f)

        val key = "$wPx|$hPx|$bucket|$isPlaying|$activeColor|$trackColor|$stopColor"
        cache.get(key)?.let { return it }

        val bitmap = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Se dibuja en dp: la escala mapea exactamente dp -> píxeles del bitmap.
        canvas.scale(wPx / wDp, hPx / WavyProgressGeometry.CONTAINER_HEIGHT_DP)

        val lay = WavyProgressGeometry.layout(wDp, p)
        val amplitude = WavyProgressGeometry.amplitudeDp(p, isPlaying)
        val wavelength = WavyProgressGeometry.wavelengthDp(wDp)
        val centerY = WavyProgressGeometry.CONTAINER_HEIGHT_DP / 2f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = WavyProgressGeometry.STROKE_DP
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // 1. Pista recta (por debajo del punto final).
        if (lay.hasTrack) {
            strokePaint.color = trackColor
            canvas.drawLine(lay.trackStartX, centerY, lay.trackEndX, centerY, strokePaint)
        }

        // 2. Tramo recorrido: onda (amplitud > 0) o línea recta.
        if (lay.hasActive) {
            if (lay.activeEndX - lay.activeStartX < 0.01f) {
                fillPaint.color = activeColor
                canvas.drawCircle(lay.activeStartX, centerY, WavyProgressGeometry.STROKE_DP / 2f, fillPaint)
            } else {
                strokePaint.color = activeColor
                val path = Path()
                var x = lay.activeStartX
                path.moveTo(x, WavyProgressGeometry.waveY(x, amplitude, wavelength))
                while (x < lay.activeEndX) {
                    x = min(x + 1f, lay.activeEndX)
                    path.lineTo(x, WavyProgressGeometry.waveY(x, amplitude, wavelength))
                }
                canvas.drawPath(path, strokePaint)
            }
        }

        // 3. Punto final (stop indicator).
        if (lay.showStop) {
            fillPaint.color = stopColor
            canvas.drawCircle(lay.stopCenterX, centerY, WavyProgressGeometry.STOP_SIZE_DP / 2f, fillPaint)
        }

        cache.put(key, bitmap)
        return bitmap
    }
}
