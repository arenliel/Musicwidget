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

    private fun trimTransparency(bitmap: Bitmap): Bitmap {
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
}
