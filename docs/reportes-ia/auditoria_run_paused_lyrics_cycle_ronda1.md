# AUDITORÍA — runPausedLyricsCycle, cuerpo actual completo (RONDA 1)

## Paso 0 — Confirmación de HEAD
```text
ba65f344479ee9ef8c8c6a348c0ca1bf1975e744
```

## Paso 1 — Localización (`grep -n "fun runPausedLyricsCycle" -r app/src/main/java/`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2956:    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
```

## Paso 2 — Cuerpo verbatim completo y actual de `runPausedLyricsCycle`
```kotlin
    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
        var showLyric = true
        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            val currentSessionId = MusicDataStore.computeSessionIdentity(currentRAM.packageName, currentRAM.title, currentRAM.artist)
            if (currentSessionId != myTrackKey || currentRAM.isPlaying) break
            
            val pausedPos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
            
            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
            // Conjunto Letras-Atomicas-8: antes, esta regla también se disparaba durante
            // Estado=OTHER (carga), mostrando la primera línea antes de que sonara audio.
            if (lastEntry == null && pausedPos < 5000L && currentLogicalSession?.hasConfirmedPlayback == true) {
                lastEntry = lyricsRes.allEntries.firstOrNull()
            }

            val text = if (showLyric && lastEntry != null) lastEntry.text else ""
            updateLyricInWidget(myTrackKey, text)
            
            showLyric = !showLyric
            delay(150000L) // Conjunto Letras-Atomicas-1: restaurado a 2.5 min, valor original de diseño
        }
    }
```

## Paso 3 — Todas las llamadas a `relaunchLyricsTicker` con el argumento `"identity_change"` (`grep -n 'relaunchLyricsTicker("identity_change")' -r app/src/main/java/`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2439: relaunchLyricsTicker("identity_change")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2607: relaunchLyricsTicker("identity_change")
```

### Contexto (10 líneas antes y 10 líneas después) para cada resultado:

#### 1. Línea 2439
```kotlin
                val event = if (isSessionEnded) {
                    MusicUpdateEvent.NewSession(memInfo)
                } else if (isTrackContentChanged) {
                    MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying)
                } else {
                    MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType, isBuffering = false)
                }

                if (MusicStateProvider.applyEvent(event)) {
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                }
                
                if (isSessionEnded) {
                    relaunchLyricsTicker("identity_change")
                } else {
                    val stateChangedUI = currentMem.isPlaying != isPlaying
                    if (stateChangedUI) relaunchLyricsTicker("state_sync")
                }
            }
        }
        // Solo guardamos de forma anticipada si el widget NO es visible (gating activo).
```

#### 2. Línea 2607
```kotlin
                lyricsFetchJob = currentLogicalSession?.lyricsScope?.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)

                    // Conjunto Letras-Atomicas-5: releemos el estado real justo antes de
                    // consultar, en vez de usar el snapshot capturado antes del debounce —
                    // la duración pudo resolverse recién durante esta espera.
                    val freshInfo = MusicStateProvider.current()
                    val result = lyricsRepository.getLyrics(freshInfo.trackKey, freshInfo.artist, freshInfo.title, freshInfo.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        currentLyricsIdentity = MusicDataStore.computeSessionIdentity(freshInfo.packageName, freshInfo.title, freshInfo.artist)
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(MusicDataStore.computeSessionIdentity(freshInfo.packageName, freshInfo.title, freshInfo.artist), "")
                    }
                }
```
