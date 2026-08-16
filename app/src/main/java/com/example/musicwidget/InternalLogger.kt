package arenliel.musicwidget

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Motor de Diagnóstico Interno.
 * Permite capturar eventos críticos del sistema (Stage 1/2, Gating, Errors)
 * para que el usuario pueda visualizarlos desde el WidgetConfigActivity
 * sin necesidad de Android Studio.
 */
object InternalLogger {
    private const val TAG = "InternalLogger"
    private const val LOG_FILE = "widget_error.log"
    private const val MAX_LOG_SIZE = 50 * 1024 // 50KB limit para evitar bloating

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun log(context: Context, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message\n"
        
        // Logcat para desarrollo paralelo
        Log.d(TAG, entry.trim())

        try {
            val file = File(context.filesDir, LOG_FILE)
            
            // Gestión de tamaño: Si el archivo es muy grande, lo limpiamos para evitar consumo de disco
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                file.writeText("[LOG ROTATED - OLD ENTRIES CLEARED]\n")
            }
            
            file.appendText(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo en log interno", e)
        }
    }

    fun logError(context: Context, tag: String, throwable: Throwable) {
        val message = "ERROR [$tag]: ${throwable.message}\n${throwable.stackTraceToString()}"
        log(context, message)
    }
}
