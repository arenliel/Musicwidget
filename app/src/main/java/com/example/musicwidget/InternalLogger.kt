package arenliel.musicwidget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque

/**
 * Motor de Diagnóstico Interno v5.0.
 * Sistema híbrido de trazabilidad: Memoria (StateFlow) + Disco (File) + Logcat.
 */
object InternalLogger {
    private const val TAG = "InternalLogger"
    private const val LOG_FILE = "widget_error.log"
    private const val MAX_LOG_SIZE = 512 * 1024 // Aumentado a 512KB para auditorías largas (v7.1)
    private const val MAX_MEMORY_LOGS = 1000 // Aumentado a 1000 líneas para historial UI (v7.1)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    // Buffer en memoria para reactividad UI
    private val inMemoryLogs = ArrayDeque<String>(MAX_MEMORY_LOGS)
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()

    private var isInitialized = false

    /**
     * Inicializa el logger cargando los logs previos del disco.
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val file = File(context.filesDir, LOG_FILE)
                if (file.exists()) {
                    val lines = file.readLines().takeLast(MAX_MEMORY_LOGS)
                    inMemoryLogs.addAll(lines)
                    _logsFlow.value = inMemoryLogs.toList()
                }
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando logger", e)
            }
        }
    }

    fun d(context: Context, message: String) {
        log(context, "DEBUG", message)
    }

    fun w(context: Context, message: String) {
        log(context, "WARN ", message)
    }

    fun e(context: Context, message: String) {
        log(context, "ERROR", message)
    }

    fun log(context: Context, message: String) {
        log(context, "INFO ", message)
    }

    private fun log(context: Context, level: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $level: $message"
        
        // 1. Logcat
        when(level) {
            "ERROR" -> Log.e(TAG, message)
            "WARN " -> Log.w(TAG, message)
            else -> Log.d(TAG, message)
        }

        // 2. Memoria (Reactividad)
        synchronized(inMemoryLogs) {
            if (inMemoryLogs.size >= MAX_MEMORY_LOGS) {
                inMemoryLogs.removeFirst()
            }
            inMemoryLogs.addLast(entry)
            _logsFlow.value = inMemoryLogs.toList()
        }

        // 3. Disco (Persistencia)
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                file.writeText("[LOG ROTATED]\n")
            }
            file.appendText("$entry\n")
        } catch (e: Exception) {
            // Silently fail to avoid loops
        }
    }

    fun logError(context: Context, tag: String, throwable: Throwable) {
        val message = "[$tag]: ${throwable.message}\n${throwable.stackTraceToString()}"
        e(context, message)
    }

    fun clear(context: Context) {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _logsFlow.value = emptyList()
        }
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando logs", e)
        }
    }
}
