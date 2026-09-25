# VERIFICACIÓN — ESTADO REAL DEL CÓDIGO ACTUAL

## 1. SHA de HEAD (`git rev-parse HEAD`)
`4612cddeb35bd1b14af8fe5abc27e69ff5aec300`

---

## 2. Líneas 2855 a 2945 de `MusicNotificationListener.kt`
```kotlin
                runLyricsShowcase(targetIdentity, lyricsRes)
            } else {
                runPausedLyricsCycle(targetIdentity, lyricsRes)
            }
        }
    }

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

    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
        var showLyric = true
        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            val currentSessionId = MusicDataStore.computeSessionIdentity(currentRAM.packageName, currentRAM.title, currentRAM.artist)
            if (currentSessionId != myTrackKey || currentRAM.isPlaying) break
            
            val pausedPos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
            
            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
            if (lastEntry == null && pausedPos < 5000L) {
                lastEntry = lyricsRes.allEntries.firstOrNull()
            }

            val text = if (showLyric && lastEntry != null) lastEntry.text else ""
            updateLyricInWidget(myTrackKey, text)
```

---

## 3. Coincidencias de `lyricsRepository.getLyrics(` en `MusicNotificationListener.kt`
```text
line 2587: val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
line 2847: lyricsRepository.getLyrics(
```

---

## 4. Funciones que contienen las llamadas a `getLyrics` (con contexto de al menos 20 líneas antes y 10 después)

### A. Línea 2587: Función `processSnapshot` (Fragmento que contiene la llamada)
```kotlin
            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (songChangedForLyrics) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                currentLyricsIdentity = null

                // Conjunto Letras-Atomicas-1: se lanza en el espacio de trabajo de la sesión
                // recién creada (currentLogicalSession ya es la nueva en este punto de la
                // función) — si esta canción termina antes de que la descarga responda, muere
                // con ella, nunca puede escribir su resultado sobre la canción siguiente.
                lyricsFetchJob = currentLogicalSession?.lyricsScope?.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)

                    val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        currentLyricsIdentity = MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist), "")
                    }
                }
            } else {
```

### B. Línea 2847: Función `relaunchLyricsTicker`
```kotlin
    private fun relaunchLyricsTicker(reason: String) {
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${currentLyrics == null}")
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

        val activeSession = currentLogicalSession ?: return

        // Conjunto Letras-Atomicas-2: identidad de negocio de lo que suena AHORA. Es la única
        // llave que decide si podemos reutilizar `currentLyrics` sin tocar red/disco. Capturamos
        // ambos valores aquí (fuera de la corrutina) para no leer variables de clase que puedan
        // cambiar mientras la corrutina está suspendida.
        val targetIdentity = MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
        val cachedLyrics = currentLyrics
        val canReuse = cachedLyrics != null && currentLyricsIdentity == targetIdentity
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Decisión de datos: reused=$canReuse, targetIdentity=$targetIdentity, cachedIdentity=$currentLyricsIdentity")

        lyricsUpdateJob = activeSession.lyricsScope.launch {
            val lyricsRes = if (canReuse && cachedLyrics != null) {
                // Ya tenemos la letra correcta para esta identidad exacta: nos ahorramos el
                // viaje a disco/red. Esto es lo que vuelve inofensivo que varios disparadores
                // (Stage 1, sincronización pasiva, screen_wake, seek_event) llamen a esta
                // función casi al mismo tiempo para el mismo evento — todos convergen aquí sin
                // competir por una descarga lenta que terminan cancelándose entre sí.
                cachedLyrics
            } else {
                lyricsRepository.getLyrics(
                    currentInfo.trackKey, currentInfo.artist, currentInfo.title, currentInfo.durationMs
                )?.also {
                    currentLyrics = it
                    currentLyricsIdentity = targetIdentity
                } ?: return@launch
            }
            if (currentInfo.isPlaying) {
                runLyricsShowcase(targetIdentity, lyricsRes)
            } else {
                runPausedLyricsCycle(targetIdentity, lyricsRes)
            }
        }
    }
```

---

## 5. Procedencia de los 4 Argumentos por LLamada

### Llamada 1 (`processSnapshot`, línea 2587)
`lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)`
- `trackKey`: tomado de `snapshot.trackKey`.
  - Declaración/asignación anterior: `val snapshot = rawSnapshot.copy(...)` (línea ~2150) / `rawSnapshot` (parámetro de `processSnapshot`).
- `artist`: tomado de `snapshot.artist`.
- `title`: tomado de `snapshot.title`.
- `durationMs`: tomado de `snapshot.durationMs`.

### Llamada 2 (`relaunchLyricsTicker`, línea 2847)
`lyricsRepository.getLyrics(currentInfo.trackKey, currentInfo.artist, currentInfo.title, currentInfo.durationMs)`
- `trackKey`: tomado de `currentInfo.trackKey`.
  - Declaración/asignación anterior: `val currentInfo = MusicStateProvider.current()` (línea 2818).
- `artist`: tomado de `currentInfo.artist`.
- `title`: tomado de `currentInfo.title`.
- `durationMs`: tomado de `currentInfo.durationMs`.
