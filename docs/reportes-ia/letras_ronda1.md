# Auditoría — Ronda 1: Subsistema de Letras (Sincronización, Identidad, y Consumo de Recursos)

## L1. Nombre y código real del "ticker" de letras
El ticker se gestiona mediante `relaunchLyricsTicker` y se ejecuta en `runLyricsShowcase`.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2591-2666)

```kotlin
    private fun relaunchLyricsTicker(reason: String) {
        if (!isWidgetPotentiallyVisible()) {
            lyricsUpdateJob?.cancel()
            return
        }

        val currentInfo = MusicStateProvider.current()
        if (currentInfo.isEmpty || !currentInfo.isSessionActive) {
            lyricsUpdateJob?.cancel()
            return
        }

        InternalLogger.d(applicationContext, "[LYRICS_TRACE] relaunchLyricsTicker: Reason=$reason | Track=${currentInfo.title}")
        lyricsUpdateJob?.cancel()
        
        // Hallazgo v3.8: Ticker Stateless (Claude). Lee identidad y estado directo de la RAM.
        lyricsUpdateJob = serviceScope.launch(Dispatchers.IO) {
            val lyricsRes = lyricsRepository.getLyrics(
                currentInfo.trackKey, 
                currentInfo.artist, 
                currentInfo.title, 
                currentInfo.durationMs
            ) ?: return@launch

            if (currentInfo.isPlaying) {
                runLyricsShowcase(currentInfo.trackKey, lyricsRes)
            } else {
                runPausedLyricsCycle(currentInfo.trackKey, lyricsRes)
            }
        }
    }
```

## L2. Cálculo de progreso/drift usado por el ticker
El ticker utiliza el "Oráculo de Tiempo Puro" centralizado en el snapshot lógico.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2636-2640)

```kotlin
            // Usamos el Snapshot Lógico para el cálculo de posición real
            val snapshot = lastLogicalSnapshot ?: break
            val currentPos = snapshot.projectedPositionMs()
            
            val entry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= (currentPos + snappinessOffset) }
```

La función `projectedPositionMs` está definida en el `MediaSnapshot` (Líneas 536-544):
```kotlin
        fun projectedPositionMs(
            nowRealtime: Long = SystemClock.elapsedRealtime()
        ): Long {
            if (playbackState != PlaybackState.STATE_PLAYING) return positionMs
            val delta = nowRealtime - positionUpdatedAtRealtime
            val projected = positionMs + (delta * playbackSpeed).toLong()
            return if (durationMs > 0) projected.coerceIn(0L, durationMs)
            else projected.coerceAtLeast(0L)
        }
```

## L3. El "Self-Check" / "Zombie Detector"
El bloque compara el `trackKey` de la RAM actual contra el `trackKey` con el que nació el ticker.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2629-2633)

```kotlin
            val currentRAM = MusicStateProvider.current()
            // REGLA DE IDENTIDAD DUAL: Si la sesión física (Karaoke) cambió, abortamos
            if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Zombie Detector: Clave discordante. Cancelando Ticker.")
                lyricsUpdateJob?.cancel()
                break
            }
```

## L4. `LyricsRepository.getLyrics()` completo
Contiene la lógica de caché en Room y la API de LRCLIB. La caché negativa (`notFound`) tiene un TTL de **1 hora**.

**Archivo:** [LyricsRepository.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt)

```kotlin
    suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Intentar desde Room
        val cached = lyricsDao.getLyrics(trackKey)
        if (cached != null) {
            val now = System.currentTimeMillis()
            if (cached.notFound) {
                // TTL 1h para re-intentos de letras (v3.0)
                if (now - cached.timestampFetched < 1 * 60 * 60 * 1000L) {
                    return@withContext null
                }
            } else {
                lyricsDao.updateLastAccessed(trackKey, now)
                return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
            }
        }

        // 2. Si no hay o TTL expiró, ir a red
        val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
        val now = System.currentTimeMillis()
        
        if (networkResult != null) {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = networkResult,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = false
                )
            )
            return@withContext parseLrc(trackKey, networkResult, durationMs)
        } else {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = null,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = true
                )
            )
            return@withContext null
        }
    }
```

## L5. Confirmación de la infraestructura de persistencia
Se confirman los tres archivos en el paquete `arenliel.musicwidget`:
- [LyricsEntity.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsEntity.kt)
- [LyricsDao.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt)
- [LyricsDatabase.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDatabase.kt)

## L6. El Composable que dibuja la letra en el widget
Ubicado en `MusicWidget.kt`, dentro de la función `TextInfo`.

**Archivo:** [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt) (Líneas 770-775)

```kotlin
                val artistText = when {
                    info.isEmpty -> info.artist
                    info.title == context.getString(R.string.widget_empty_title) -> info.artist
                    isSnapshot && !isStatusLabelVisible -> { val time = formatRelativeTime(context, info.lastUpdateEpoch); if (time.isEmpty()) info.artist else "${info.artist} • $time" }
                    info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> "“${info.currentLyric}”"
                    else -> info.artist
                }
```

**Lógica de alternancia (Silencio Inteligente):** Existe en el ticker (`runLyricsShowcase`). Si la espera hasta el siguiente verso es mayor a **15 segundos**, se limpia la letra tras **8 segundos** de mostrar la actual.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2654-2660)
```kotlin
                // Hallazgo v3.3: Silencios Inteligentes
                if (waitTime > 15000L) {
                    delay(8000L)
                    if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
                        updateLyricInWidget(myTrackKey, "")
                    }
                    delay((waitTime - 8000L).coerceAtLeast(100L))
                }
```

## L7. El filtro "Glance Guard" para letras
La función `updateLyricInWidget` emite a través de `MusicStateProvider`.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2689-2695)

```kotlin
    private fun updateLyricInWidget(trackKey: String, lyric: String) {
        // Relevo Atómico (v4.0)
        serviceScope.launch {
            if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
        }
    }
```

## L8. Comportamiento con pantalla apagada y al despertar

**a) Cancelación por pantalla:**
La línea `lyricsUpdateJob?.cancel()` se ejecuta al inicio de `relaunchLyricsTicker` si la pantalla no es visible.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2591-2595)
```kotlin
    private fun relaunchLyricsTicker(reason: String) {
        if (!isWidgetPotentiallyVisible()) {
            lyricsUpdateJob?.cancel()
            return
        }
```

**b) Reinicio por "screen_wake":**
Se dispara desde el `BroadcastReceiver` de la pantalla.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 640-645)
```kotlin
                Intent.ACTION_SCREEN_ON -> {
                    InternalLogger.d(applicationContext, "SCREEN_EVENT: ON")
                    relaunchLyricsTicker("screen_wake")
                }
```

## L9. Mapa de uso de `trackKey` específico al contexto de letras
Se confirma que el subsistema de letras utiliza el `trackKey` canónico del sistema.

1.  **Obtención:** `lyricsRepository.getLyrics(currentInfo.trackKey, ...)`
2.  **Identidad del Ticker:** `runLyricsShowcase(currentInfo.trackKey, ...)`
3.  **Zombie Detector:** `if (currentRAM.trackKey != myTrackKey ...)`
4.  **Actualización UI:** `updateLyricInWidget(myTrackKey, entry.text)`
5.  **DataStore / RAM:** `MusicUpdateEvent.LyricTick(lyric, trackKey)`

---
**Nota:** Este documento contiene únicamente evidencia de código extraída mediante auditoría técnica. No se han realizado cambios funcionales.
