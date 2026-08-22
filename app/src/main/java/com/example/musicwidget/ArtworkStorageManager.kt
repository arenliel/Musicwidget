package arenliel.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Gestor especializado en la persistencia de recursos gráficos.
 * Garantiza la independencia de archivos entre la sesión activa y el historial.
 */
object ArtworkStorageManager {
    private const val TAG = "ArtworkStorage"

    /**
     * Guarda una portada para el historial de forma permanente y atómica.
     * @return Ruta absoluta del archivo guardado.
     */
    fun saveHistoryArtwork(context: Context, bitmap: Bitmap, trackKey: String): String {
        val historyDir = File(context.filesDir, "history")
        if (!historyDir.exists()) historyDir.mkdirs()

        val fileName = "art_${trackKey.hashCode()}.webp"
        val finalFile = File(historyDir, fileName)
        val tempFile = File(historyDir, "$fileName.tmp")

        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            FileOutputStream(tempFile).use { out ->
                if (bitmap.compress(format, 80, out)) {
                    out.flush()
                    // Movimiento atómico para evitar corrupción
                    Files.move(
                        tempFile.toPath(), 
                        finalFile.toPath(), 
                        StandardCopyOption.REPLACE_EXISTING, 
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    Log.d(TAG, "[HISTORY_ART] Guardando imagen de historial en disco para: $trackKey -> Path: ${finalFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persistiendo imagen de historial", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return finalFile.absolutePath
    }
}
