# Auditoría — Ronda 2: Cierre de Funciones Completas del Ticker de Letras

Confirmando el estado del proyecto:
```
6e5278f (HEAD -> master) Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
77ff7a9 Conjunto F.1: Andamiaje del estado Cargando (Buffering)
bf6f590 Conjunto E: Implementación del Reloj de Verdad usando señales oficiales de PlaybackState
```

A continuación se presenta el cierre de la auditoría del Ticker de Letras (Ronda 2), con el código completo y verbatim solicitado.

---

### L10. Código completo de `runLyricsShowcase`

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2623-2666)

```kotlin
    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
        val snappinessOffset = 500L

        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            // REGLA DE IDENTIDAD DUAL: Si la sesión física (Karaoke) cambió, abortamos
            if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Zombie Detector: Clave discordante. Cancelando Ticker.")
                lyricsUpdateJob?.cancel()
                break
            }
            
            // Usamos el Snapshot Lógico para el cálculo de posición real
            val snapshot = lastLogicalSnapshot ?: break
            val currentPos = snapshot.projectedPositionMs()
            
            val entry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= (currentPos + snappinessOffset) }
            
            if (entry != null) {
                updateLyricInWidget(myTrackKey, entry.text)
            }

            val entryIdx = lyricsRes.allEntries.indexOf(entry)
            val next = if (entryIdx != -1 && entryIdx < lyricsRes.allEntries.size - 1) lyricsRes.allEntries[entryIdx + 1] else null
            
            if (next != null) {
                val waitTime = (next.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                
                // Hallazgo v3.3: Silencios Inteligentes
                if (waitTime > 15000L) {
                    delay(8000L)
                    if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
                        updateLyricInWidget(myTrackKey, "")
                    }
                    delay((waitTime - 8000L).coerceAtLeast(100L))
                } else {
                    delay(waitTime)
                }
            } else {
                break
            }
        }
    }
```

---

### L11. Código completo de `runPausedLyricsCycle`

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2668-2686)

```kotlin
    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
        var showLyric = true
        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
            
            val pausedPos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
            
            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
            if (lastEntry == null && pausedPos < 5000L) {
                lastEntry = lyricsRes.allEntries.firstOrNull()
            }

            val text = if (showLyric && lastEntry != null) lastEntry.text else ""
            updateLyricInWidget(myTrackKey, text)
            
            showLyric = !showLyric
            delay(60000L)
        }
    }
```
*   **Confirmación:** No utiliza la lógica de "silencios inteligentes" de 15s; en su lugar, implementa un ciclo de parpadeo (estrofa vs artista) cada 60 segundos (`delay(60000L)`).

---

### L12. Comportamiento de `lyricsFetchJob` con la pantalla apagada

En `MusicNotificationListener.kt`, se confirma que `lyricsFetchJob` **solo se cancela explícitamente cuando hay un cambio de track**, no cuando la pantalla se apaga.

```kotlin
// Línea 2416 (Dentro de processSessionUpdate)
            if (trackChangedUI) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                
                lyricsFetchJob = serviceScope.launch {
                    // ... fetch ...
```
**Análisis:** Si la pantalla se apaga mientras una petición de red está en curso, la petición continúa. Sin embargo, al finalizar y llamar a `relaunchLyricsTicker`, esta última función abortará el inicio del ticker visual si la pantalla sigue apagada.

---

### L13. El manejador de `ACTION_SCREEN_OFF`

Existe un manejador explícito que cancela el ticker visual inmediatamente.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 399-403)

```kotlin
    private fun onDisplayBecameUnavailable() {
        InternalLogger.d(applicationContext, "[GATING] Display unavailable. Closing gate.")
        InternalLogger.log(applicationContext, "GATING: Pantalla apagada. Compuerta CERRADA.")
        lyricsUpdateJob?.cancel()
        unlockPollingJob?.cancel()
    }
```
*   **Confirmación:** Cancela `lyricsUpdateJob` en el instante del evento. No cancela `lyricsFetchJob`.

---

### L14. Ciclo de vida completo del campo `lyricsTrackKey`

Se han identificado tres puntos clave de asignación en `MusicNotificationListener.kt`:

1.  **Limpieza por hiato visual (reposo):**
```kotlin
// Línea 2299 (Al persistir metadata cuando el widget no es visible)
    val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
    val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
```

2.  **Actualización por Relevo Atómico (evento exitoso):**
```kotlin
// MusicStateProvider.kt (Línea 99)
    return current.copy(
        currentLyric = e.lyric,
        lyricsTrackKey = e.trackKey
    )
```

3.  **Purga por desincronización de Sesión (DataStore):**
```kotlin
// MusicDataStore.kt (Línea 197)
    currentLyric = "",
    lyricsTrackKey = ""
```

---
**Nota:** Este documento contiene únicamente evidencia de código extraída mediante auditoría técnica. No se han realizado cambios funcionales.
