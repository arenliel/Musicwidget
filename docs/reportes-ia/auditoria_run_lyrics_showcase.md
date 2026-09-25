# Auditoría — runLyricsShowcase

## 1. Resultado de Grep
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 2862: private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
```

---

## 2. Cuerpo completo de `runLyricsShowcase` (`MusicNotificationListener.kt`)

```kotlin
    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
        val snappinessOffset = 500L

        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            // REGLA DE IDENTIDAD DUAL: Si la sesión física (Karaoke) cambió, abortamos
            val oldZombieCheck = currentRAM.trackKey != myTrackKey
            val newZombieCheck = MusicDataStore.computeSessionIdentity(currentRAM.packageName, currentRAM.title, currentRAM.artist) != myTrackKey
            InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_zombieDetector: viejo=$oldZombieCheck, nuevo=$newZombieCheck, coincide=${oldZombieCheck == newZombieCheck}")
            if (newZombieCheck || !currentRAM.isPlaying) {
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
            
            // Hallazgo v4.1: Regla Unificada de Silencio (Conjunto Letras-2)
            // Un solo umbral decide cuándo alternar a mostrar el artista, tanto si el
            // próximo verso conocido tarda de más como si ya no queda ningún verso más.
            if (next != null) {
                val waitTime = (next.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                
                if (waitTime > LYRICS_SILENCE_THRESHOLD_MS) {
                    delay(LYRICS_SILENCE_THRESHOLD_MS)
                    val oldSilenceCheck = MusicStateProvider.current().trackKey == myTrackKey
                    val newSilenceCheck = MusicDataStore.computeSessionIdentity(MusicStateProvider.current().packageName, MusicStateProvider.current().title, MusicStateProvider.current().artist) == myTrackKey
                    InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_silencio: viejo=$oldSilenceCheck, nuevo=$newSilenceCheck, coincide=${oldSilenceCheck == newSilenceCheck}")
                    if (currentCoroutineContext().isActive && newSilenceCheck) {
                        updateLyricInWidget(myTrackKey, "")
                    }
                    delay((waitTime - LYRICS_SILENCE_THRESHOLD_MS).coerceAtLeast(100L))
                } else {
                    delay(waitTime)
                }
            } else if (entryIdx != -1) {
                // Ya no queda ningún verso más, pero la canción sigue sonando: aplicamos
                // la misma regla de silencio antes de ceder el lugar al nombre del artista.
                delay(LYRICS_SILENCE_THRESHOLD_MS)
                val oldSilenceCheck = MusicStateProvider.current().trackKey == myTrackKey
                val newSilenceCheck = MusicDataStore.computeSessionIdentity(MusicStateProvider.current().packageName, MusicStateProvider.current().title, MusicStateProvider.current().artist) == myTrackKey
                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_silencio: viejo=$oldSilenceCheck, nuevo=$newSilenceCheck, coincide=${oldSilenceCheck == newSilenceCheck}")
                if (currentCoroutineContext().isActive && newSilenceCheck) {
                    updateLyricInWidget(myTrackKey, "")
                }
                break
            } else {
                break
            }
        }
    }
```
