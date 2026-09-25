# AUDITORÍA — runLyricsShowcase y helpers de posición de letra (RONDA 1)

## Paso 0 — Confirmación de HEAD
```text
4126e4a6569102550b917185594c7ff8e0c9d2fa
```

## Paso 1 — Localización (`grep -n "fun runLyricsShowcase" -r .`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2884:    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
old_mnl.kt:2626:    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
```

## Paso 2 — Cuerpo verbatim completo de `runLyricsShowcase`
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
            } else if (lyricsRes.allEntries.isNotEmpty()) {
                // Todavía no llega ninguna línea (intro largo, currentPos está antes de la
                // primera marca de tiempo). En vez de rendirse, programamos la espera exacta
                // hasta que llegue, en trozos del mismo umbral de silencio ya establecido, para
                // seguir revisando la identidad de la sesión mientras tanto (Conjunto
                // Letras-Atomicas-4).
                val firstEntry = lyricsRes.allEntries.first()
                val waitTime = (firstEntry.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                delay(waitTime.coerceAtMost(LYRICS_SILENCE_THRESHOLD_MS))
            } else {
                break
            }
        }
    }
```

## Paso 3 — Búsqueda de fallback de "primera entrada" (`grep -n "allEntries" -r .` y `grep -n "firstOrNull" -r .`)
```text
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:15: data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:122: val allEntries = mutableListOf<LyricsEntry>()
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:135: if (text.isNotBlank()) allEntries.add(LyricsEntry(totalMs, text))
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:139: return LyricsResult(trackKey, allEntries)
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2903:            val entry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= (currentPos + snappinessOffset) }
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2909:            val entryIdx = lyricsRes.allEntries.indexOf(entry)
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2910:            val next = if (entryIdx != -1 && entryIdx < lyricsRes.allEntries.size - 1) lyricsRes.allEntries[entryIdx + 1] else null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2941:            } else if (lyricsRes.allEntries.isNotEmpty()) {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2947:            val firstEntry = lyricsRes.allEntries.first()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2965:            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2969:            lastEntry = lyricsRes.allEntries.firstOrNull()
```
(`firstOrNull` matches):
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1684: .firstOrNull {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1726: .firstOrNull {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1742: .firstOrNull()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1744: .firstOrNull()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2969: lastEntry = lyricsRes.allEntries.firstOrNull()
```

## Paso 4 — Cuerpo verbatim de función auxiliar
No existe una función auxiliar separada; la lógica de selección de entradas de letra se encuentra embebida directamente dentro de `runLyricsShowcase` y `runPausedLyricsCycle` en `MusicNotificationListener.kt`.
