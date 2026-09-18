# Auditoría de Evidencia — Ronda 1 (Post Identidad-Final)

## Sección 1 — Carga inicial de letras

### L1. Grep de términos relacionados con letras
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:17: class LyricsRepository(private val context: Context) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:63: private lateinit var lyricsRepository: LyricsRepository
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:589: lyricsRepository = LyricsRepository(applicationContext)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2455: val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2472: currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2671: val lyricsRes = lyricsRepository.getLyrics(
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2654: private fun relaunchLyricsTicker(reason: String) {
```

### L2. Función de consulta de letras al arrancar (parte de `processSnapshot`)
```kotlin
// Archivo: MusicNotificationListener.kt (Líneas 2445-2465)
            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (songChangedForLyrics) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                
                lyricsFetchJob = serviceScope.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)
                    
                    val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(snapshot.trackKey, "")
                    }
                }
```

### L3. Lógica "Network re-fetch guard"
```kotlin
// Archivo: MusicNotificationListener.kt (Líneas 2465-2485)
            } else if (trackChangedUI && currentLyrics != null) {
                // La canción de negocio es la misma (solo se afinó trackKey, ej. duración tardía).
                // Reutilizamos la letra ya cargada en vez de re-buscarla en red (Conjunto Letras-3).
                relaunchLyricsTicker("metadata_refined")
            } else {
                // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
                if (currentLyrics == null) {
                    currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                }

                val effectivePos = previousLogical?.projectedPositionMs() ?: 0L
                val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
                
                // Hard-Sync: Solo si el desvío es mayor a 1s o cambió el estado
                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true

                if (shouldResync && currentLyrics != null) {
                    relaunchLyricsTicker("state_sync")
                }
            }
```

### L4. Grep de `duration` en `LyricsRepository.kt`
```text
line 20: suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
line 32: return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
line 37: val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
line 51: return@withContext parseLrc(trackKey, networkResult, durationMs)
line 71: private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
line 76: val durationParam = if (durationSec > 0) "&duration=$durationSec" else ""
line 98: private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
line 102: fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
```

### L5. Mecanismo de re-intento por corrección de duración
**No existe tal mecanismo.** Si `songChangedForLyrics` es falso (porque es la misma canción de negocio), el sistema entra en la rama `else if (trackChangedUI && currentLyrics != null)` o en el `else` final. Si `currentLyrics` es null porque falló la primera carga con `-1ms`, solo intentará re-cargar en la rama `else` mediante `currentLyrics = lyricsRepository.getLyrics(...)`, pero esto solo ocurre si `songChangedForLyrics` es falso y no se entra en el `metadata_refined`. Sin embargo, `lyricsRepository` tiene un TTL de 1 hora para re-intentos de fallos (`notFound`), por lo que peticiones inmediatas tras una falla fallarán por caché de Room.

---

## Sección 2 — Sincronía de portada (keyEnDisco / "Mirror")

### A1. Ubicación de `keyEnDisco`
**Archivo:** [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
**Línea 232 (en el log):** Corresponde a la lectura de `ALB_KEY_FILE`.
**Línea 123 (definición):** `private const val ALB_KEY_FILE = "album_art.key"`

### A2. Asignaciones de `ALB_KEY_FILE`
```text
MusicNotificationListener.kt:2521: saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
MusicNotificationListener.kt:2536: saveTextToFile("", ALBUM_ART_KEY_FILE)
```

### A3. Función "Chequeo de sincronía"
```kotlin
// Archivo: MusicWidget.kt (Líneas 231-232)
            val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim() && displayedInfo.artworkKey.isNotBlank()
            InternalLogger.d(context, "[ART_TRACE] Chequeo de sincronía: resultado=$isArtworkSynchronized, keyActual=${displayedInfo.artworkKey}, keyEnDisco=${readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim()}")
```

### A4. Decisión de repintado de portada
```kotlin
// Archivo: MusicWidget.kt (Líneas 234-250)
            val albumArtBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.artworkKey, displayedInfo.sessionUUID, needsPillAsset, isArtworkSynchronized) {
                if (isArtworkSynchronized) {
                    val cacheKey = "${displayedInfo.artworkKey}_${if(needsPillAsset) "pill" else "raw"}"
                    bitmapCache.get(cacheKey)?.also { value = it } ?: withContext(Dispatchers.IO) {
                        val decoded = decodeBitmap(
                            File(context.filesDir, if (needsPillAsset) ALBUM_ART_FILE else ALB_RAW_FILE),
                            reqWidth = if (needsPillAsset) 400 else 800, // RAW a mayor resolución (P1)
                            reqHeight = if (needsPillAsset) 400 else 800
                        )
                        decoded?.also { bitmapCache.put(cacheKey, it); value = it }
                    }
                } else {
                    // PIPELINE DE FALLBACK IDEMPOTENTE (v5.2): Prioridad Imagen sobre Llave
                    if (!displayedInfo.isEmpty) {
                        // ... (Lógica de fallback por UUID)
                    }
                }
            }
```

---

## Sección 3 — Errores "No app widget info for X"

### E1. Grep de "No app widget info"
El error proviene de la librería Jetpack Glance:
`androidx.glance.appwidget.AppWidgetSession.processEmittableTree$suspendImpl(AppWidgetSession.kt:180)`

### E2. Call sites de actualización
```text
MusicNotificationListener.kt:652: MusicWidget.updateAll(applicationContext)
MusicWidget.kt:115: MusicWidget.updateAll(context)
MusicWidget.kt:156: runCatching { appearance.updateAll(context) }
```

### E3. Funciones de actualización verbatim
```kotlin
// Archivo: MusicWidget.kt (Líneas 151-158)
        suspend fun updateAll(context: Context) {
            InternalLogger.init(context)
            InternalLogger.log(context, "UPDATE: Disparando actualización en cascada (Global).")
            // Actualización determinista basada en la enumeración de identidades (v1.6.3)
            WidgetAppearance.values().forEach { appearance ->
                runCatching { appearance.updateAll(context) }
            }
        }

// Archivo: WidgetAppearance enum (Líneas 92-98)
    suspend fun updateAll(context: Context) {
        when (this) {
            SMALL -> SmallMusicWidget().updateAll(context)
            PILL_STANDARD -> StandardMusicWidget().updateAll(context)
            PILL_CONTROL -> LargeMusicWidget().updateAll(context)
        }
    }
```

### E4. Validación de IDs existentes
**No existe código en el proyecto** que use `getAppWidgetIds` o `AppWidgetManager` para filtrar widgets inválidos antes de llamar a `updateAll()`. El sistema confía en el `runCatching` (Línea 156 de `MusicWidget.kt`) para absorber los fallos de Glance.

### E5. Reacción a re-registro
El proyecto contiene receptores para `BOOT_COMPLETED` y `ACTION_APPWIDGET_UPDATE` (implícitos en `GlanceAppWidgetReceiver`), pero no se observa código personalizado para forzar un re-registro manual de los IDs fuera de lo que Glance maneja internamente.

### E6. Marcas de tiempo (Comparativa Pausa)
- **Primer error 1111/1113:** `[03:12:24]` (Durante la pausa nocturna).
- **Último error registrado:** `[11:01:22]` (Justo antes de reanudar).
- **Fin de la pausa (Azealia Banks):** `~11:18`.
*Observación:* Los errores cesaron o disminuyeron drásticamente una vez que la sesión volvió a estar activa y la pantalla se encendió de forma sostenida.

---

## Sección 4 — Verificación de estado del repositorio

### Git Log
```text
b00b254 (HEAD -> master) Conjunto Identidad-Final (Parte 2): Unificación de porteros de letras a identidad de negocio
ccc3816 Conjunto Identidad-Final (Parte 1): Migración a sessionIdentity con instrumentación de traza
3e85a7a Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

### Git Show --stat (b00b254)
```text
 app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt | 3 ++-
 app/src/main/java/com/example/musicwidget/MusicStateProvider.kt        | 2 +-
 2 files changed, 3 insertions(+), 2 deletions(-)
```
