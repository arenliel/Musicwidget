package arenliel.musicwidget

import android.graphics.*
import kotlin.math.*

object ImageUtils {

    /**
     * Crea un efecto de "máscara de píldora" robusta y rotada con ajuste exacto.
     * Devuelve un Bitmap que es exactamente el bounding box de la píldora.
     */
    fun createRotatedPillBitmap(
        source: Bitmap,
        rotationDegrees: Float,
        targetWidth: Int,
        heightRatio: Float = 0.9f
    ): Bitmap {
        val pillWidth = targetWidth.toFloat()
        val pillHeight = pillWidth * heightRatio
        
        val angleRad = Math.toRadians(rotationDegrees.toDouble())
        val cosA = abs(cos(angleRad)).toFloat()
        val sinA = abs(sin(angleRad)).toFloat()
        
        // Bounding box matemático inicial
        val bboxW = (pillWidth * cosA + pillHeight * sinA).toInt()
        val bboxH = (pillWidth * sinA + pillHeight * cosA).toInt()

        val temp = Bitmap.createBitmap(bboxW, bboxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(temp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.save()
        canvas.translate(bboxW / 2f, bboxH / 2f)
        canvas.rotate(rotationDegrees)

        val path = Path()
        val rect = RectF(-pillWidth / 2f, -pillHeight / 2f, pillWidth / 2f, pillHeight / 2f)
        val radius = pillHeight / 2f
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)

        canvas.rotate(-rotationDegrees)
        val srcRect = Rect(0, 0, source.width, source.height)
        val drawSize = pillWidth * 1.3f
        val dstRect = RectF(-drawSize / 2f, -drawSize / 2f, drawSize / 2f, drawSize / 2f)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        canvas.restore()
        
        return trimTransparency(temp)
    }

    const val REFERENCE_INK_RATIO = 0.72f

    /**
     * Normaliza un bitmap para que su peso visual coincida con la referencia maestra (Notificación).
     */
    fun normalizeIcon(
        source: Bitmap,
        isColorFallback: Boolean,
        targetSizePx: Int
    ): Bitmap {
        if (isColorFallback) {
            // Prioridad 3: Icono a Color - Inset calibrado y redondeo
            val normalized = applyColorFallbackNormalization(source, targetSizePx)
            return applySharpening(normalized)
        }

        // Prioridad 2: Monochrome - Recorte Square-Safe y escalado calibrado
        val rect = findAlphaBounds(source)
        if (rect.isEmpty) return source

        // 1. Square-Safe expansion: Expandimos el lado corto para hacer el recorte cuadrado
        val squareRect = makeSquareSafe(rect, source.width, source.height)
        
        val cropped = Bitmap.createBitmap(
            source,
            squareRect.left,
            squareRect.top,
            squareRect.width(),
            squareRect.height()
        )

        // 2. Escalado calibrado: El glifo debe ocupar REFERENCE_INK_RATIO del lienzo final
        val inkSize = (targetSizePx * REFERENCE_INK_RATIO).toInt()
        val scaledInk = Bitmap.createScaledBitmap(cropped, inkSize, inkSize, true)
        
        if (cropped !== scaledInk) cropped.recycle()

        // 3. Composición en lienzo final
        val output = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val offset = (targetSizePx - inkSize) / 2f
        canvas.drawBitmap(scaledInk, offset, offset, null)
        
        scaledInk.recycle()
        return output
    }

    /**
     * Aplica un refuerzo visual ligero (Sharpening/Contrast) para iconos de color (Tier 3).
     */
    private fun applySharpening(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // Simulación de nitidez mediante ajuste de saturación y contraste para iconos pequeños
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(1.15f) // Un 15% más de color para compensar el escalado
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun applyColorFallbackNormalization(source: Bitmap, targetSizePx: Int): Bitmap {
        // Los iconos a color suelen ser cuadrados sólidos. 
        // Aplicamos un inset para que su "masa" coincida con la tinta de los glifos.
        val inkSize = (targetSizePx * REFERENCE_INK_RATIO).toInt()
        val scaled = Bitmap.createScaledBitmap(source, inkSize, inkSize, true)
        
        val output = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // Redondeo leve (4dp aprox) para suavizar el impacto visual
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val offset = (targetSizePx - inkSize) / 2f
        val rect = RectF(offset, offset, offset + inkSize, offset + inkSize)
        val radius = 4f * source.density // Asumiendo 4dp
        
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, offset, offset, paint)
        
        scaled.recycle()
        return output
    }

    private fun findAlphaBounds(bitmap: Bitmap): Rect {
        var minX = bitmap.width; var minY = bitmap.height
        var maxX = -1; var maxY = -1

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < minX || maxY < minY) Rect() else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun makeSquareSafe(rect: Rect, maxWidth: Int, maxHeight: Int): Rect {
        val w = rect.width()
        val h = rect.height()
        val size = max(w, h)
        
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        
        val left = (centerX - size / 2).coerceAtLeast(0)
        val top = (centerY - size / 2).coerceAtLeast(0)
        val right = (left + size).coerceAtMost(maxWidth)
        val bottom = (top + size).coerceAtMost(maxHeight)
        
        return Rect(left, top, right, bottom)
    }

    fun trimTransparency(bitmap: Bitmap): Bitmap {
        var minX = bitmap.width; var minY = bitmap.height
        var maxX = -1; var maxY = -1

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        
        if (maxX < minX || maxY < minY) return bitmap
        
        val cropped = Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
        if (cropped != bitmap) {
            bitmap.recycle()
        }
        return cropped
    }

    /**
     * Crea una píldora horizontal (sin rotación) para el historial.
     */
    fun createHorizontalPill(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        val radius = targetHeight / 2f
        
        val path = Path()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)

        // Dibujar el bitmap centrando y escalando (Center Crop)
        val scale = max(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (targetWidth - drawW) / 2f
        val top = (targetHeight - drawH) / 2f
        
        canvas.drawBitmap(source, null, RectF(left, top, left + drawW, top + drawH), paint)
        
        return output
    }
}
