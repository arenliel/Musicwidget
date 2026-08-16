
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## MÓDULO 2: NÚCLEO DE ARQUITECTURA Y FLUJO DE DATOS (UDF)

Este módulo describe la arquitectura de flujo de datos unidireccional (UDF), la gestión de concurrencia mediante exclusión mutua (Mutex) y los algoritmos de extrapolación de tiempo que garantizan la integridad del estado multimedia.

### 1. ESPECIFICACIONES TÉCNICAS DE ARQUITECTURA
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Patrón de Flujo** | Unidirectional Data Flow (UDF) | Centraliza el estado y evita inconsistencias entre el servicio y la UI. |
| **Control de Ráfagas** | Debounce de 150ms (UI) / 100ms (Notif) | Evita la saturación del IPC y el error "Multiple DataStores active". |
| **Reloj de Referencia** | `SystemClock.elapsedRealtime()` | Reloj monotónico inmune a cambios de hora NTP y suspensiones de CPU. |
| **Gestión de Hilos** | `Dispatchers.Default` (Cálculos) / `IO` (Disco) | Evita el bloqueo del hilo principal (Davey! logs) durante ráfagas. |
| **Persistencia de Arte** | Escritura Atómica (.tmp -> .webp) | Elimina errores de Skia y archivos de imagen corruptos por colisiones. |

### 2. DESPACHADOR CENTRALIZADO (UI UPDATE FLOW)
El sistema abandona las actualizaciones directas en favor de un flujo compartido que consolida múltiples eventos en una sola señal de renderizado para Glance.

```kotlin
// Implementación en MusicNotificationListener.kt
private val uiUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

// Inicialización del despachador en el scope del servicio
init {
    serviceScope.launch {
        uiUpdateFlow
            .debounce(150L) // Escudo contra tormentas de eventos de Spotify/YouTube
            .collect {
                try {
                    // Único punto de entrada permitido para refrescar el widget
                    MusicWidget.updateAll(context)
                } catch (e: Exception) {
                    Log.e("UDF_ENGINE", "Error en despacho atómico: \${e.message}")
                }
            }
    }
}

// Función de activación para cualquier cambio de estado
private fun triggerUiUpdate() {
    uiUpdateFlow.tryEmit(Unit)
}
````

### 3. MOTOR DE TIEMPO Y EXTRAPOLACIÓN (ANTI-AMNESIA)

Para resolver la "Amnesia de Glance" (donde el widget olvida el progreso tras entrar en reposo), se utiliza extrapolación matemática basada en el hardware.

```
/**
 * Calcula el progreso real extrapolando el tiempo transcurrido desde el último snapshot.
 * Basado en la arquitectura SystemUI de Android (AOSP).
 */
fun calculateEffectiveProgress(
    lastPositionMs: Long,
    lastUpdateTimestamp: Long,
    playbackSpeed: Float,
    isSessionActive: Boolean
): Long {
    // Si la sesión no está reproduciendo, devolvemos la última posición estática
    if (!isSessionActive || playbackSpeed <= 0f) return lastPositionMs

    // Calculamos el delta usando el reloj monotónico del hardware
    val now = SystemClock.elapsedRealtime()
    val timeDiff = now - lastUpdateTimestamp

    // Extrapolación lineal: Posición Base + (Tiempo Transcurrido * Velocidad)
    val extrapolatedPosition = lastPositionMs + (timeDiff * playbackSpeed).toLong()

    return extrapolatedPosition
}
```

### 4. GESTIÓN DE CONCURRENCIA Y ESCRITURA ATÓMICA

Se implementa un `commitMutex` para garantizar que solo una corrutina a la vez pueda realizar transacciones en el DataStore o escribir activos (portadas/iconos) en el disco.

```
private val commitMutex = Mutex()
private val fileMutex = Mutex()

private suspend fun saveMetadataAtomatic(info: MusicInfo, artwork: Bitmap?) {
    commitMutex.withLock {
        // 1. Procesamiento de imagen fuera del hilo de I/O
        val artworkKey = info.trackKey

        fileMutex.withLock {
            artwork?.let { bmp ->
                val tempFile = File(context.cacheDir, "album_art.tmp")
                val finalFile = File(context.cacheDir, "album_art.webp")

                // Escritura defensiva: Primero a temporal, luego movimiento atómico
                FileOutputStream(tempFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                }

                if (tempFile.exists()) {
                    tempFile.renameTo(finalFile) // ATOMIC_MOVE en sistemas POSIX
                }
            }
        }

        // 2. Persistencia en DataStore (Fuente de Verdad)
        musicDataStore.updateMusicInfo(info)

        // 3. Notificar al despachador UDF
        triggerUiUpdate()
    }
}
```

### 5. SISTEMA DE IDENTIDAD DUAL (SESSION VS TRACK)

Para evitar que actualizaciones menores de metadatos (ej. Spotify corrigiendo la duración a mitad de canción) rompan la racha de escucha, se separa la identidad lógica de la física.

|Capa|Composición|Propósito|
|:--|:--|:--|
|**sessionIdentity**|`pkg + title + artist`|Continuidad del progreso y lógica de letras.|
|**trackKey**|`sessionIdentity + durationMs + album`|Validación de integridad para archivos de imagen (.key).|

### 6. LISTA NEGRA DE PRÁCTICAS (ANTI-PATRONES)

- **PROHIBIDO: Polling de Tiempo.** Nunca usar un `Timer` o `Thread.sleep` para actualizar el progreso de la canción. Esto drena la batería y es impreciso. Se debe usar `calculateEffectiveProgress`.
- **PROHIBIDO: Llamadas Directas a `updateAll`.** Las funciones de negocio nunca deben llamar al refresco del widget directamente; deben emitir a `uiUpdateFlow` para permitir el debounce.
- **PROHIBIDO: `System.currentTimeMillis()`.** Prohibido para cálculos de duración o progreso. Este reloj puede saltar hacia atrás o adelante por sincronización NTP, rompiendo la lógica de historial.
- **PROHIBIDO: Mutex Globales sin Timeout.** Evitar el uso de bloqueos que no estén asociados a una corrutina con scope definido (como `serviceScope`), para evitar bloqueos eternos (deadlocks) si el servicio se reinicia.
- **PROHIBIDO: Escritura Directa en Archivos Finales.** Nunca escribir directamente en el archivo que el widget lee (ej. `album_art.webp`). Siempre usar el patrón de archivo `.tmp` para evitar que Glance lea una imagen a medio escribir (Error de Skia).

---

**FIN DEL MÓDULO 2**