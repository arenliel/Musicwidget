# Auditoría — Ronda 2: Cierre de Verbatim Pendiente (Relojes + Racha)

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```
**Nota:** El HEAD actual es `bb7b30a`, un commit posterior a `45753c8` (el cual es ahora el anterior).

---

## Sección A — Relojes desincronizados (continuación)

### A5. Valores exactos de las constantes de debounce
Ubicación: `MusicNotificationListener.kt` (Líneas 3182-3184)

```kotlin
private const val NORMAL_DEBOUNCE_MS = 150L
private const val FAST_DEBOUNCE_MS = 100L
private const val METADATA_STABILIZATION_MS = 400L
```

### A6. Ubicación real de los logs `FSM_GUARD` y `[DIAG_V5] [INTAKE]`
Ambos logs residen dentro de la función `processSnapshot` en `MusicNotificationListener.kt`.

**Coincidencia 1: `[DIAG_V5] [INTAKE]`**
```kotlin
// Función: processSnapshot (Línea 1917)
        val stateName = when(rawSnapshot.playbackState) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            else -> "OTHER(${rawSnapshot.playbackState})"
        }
        InternalLogger.d(applicationContext, "[DIAG_V5] [INTAKE] Recibido: Estado=$stateName, Track=${rawSnapshot.title}, Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}ms, Reason=$reason")

        // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---
```

**Coincidencia 2: `FSM_GUARD`**
```kotlin
// Función: processSnapshot (Línea 2068)
        session?.let { s ->
            if (!identityChanged) {
                s.maxPositionMs = max(s.maxPositionMs, currentProjectedPos)
            }
        }

        // INSTRUMENTACIÓN BLOQUE 3.3
        InternalLogger.d(applicationContext, "[FSM_GUARD] identityChanged=$identityChanged, " +
            "projectedPos=${currentProjectedPos}ms, rawPos=${rawSnapshot.positionMs}ms, maxPos=${session?.maxPositionMs ?: 0}ms, " +
            "delta=${currentProjectedPos - lastProjectedPos}ms, taken=${if (sessionEnded) "ENDED" else if (isCatchUpRender) "CATCHUP" else "FUSION"}")

        // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
```

### A7. Variable `elapsed` en `MusicNotificationListener.kt`
Grep completo de la palabra `elapsed`:

1. `MusicNotificationListener.kt:659` (dentro de `startSeekEventProcessor`):
```kotlin
    .collect { (snapshot, position, detectedAt) ->
        val now = SystemClock.elapsedRealtime()
        val processingLag = now - detectedAt
```
2. `MusicNotificationListener.kt:738` (dentro de `startHistoryWorker`):
```kotlin
    is HistoryEvent.CommitSession -> {
        val durationObserved = android.os.SystemClock.elapsedRealtime() - event.startedAtRealtime
        
        InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] EVENT_RECEIVED: ${event.finalSnapshot.title}")
```
3. `MusicNotificationListener.kt:1353` (dentro de `onPlaybackStateChanged`):
```kotlin
    if (lastSnapshot != null && lastSnapshot.packageName == controller.packageName) {
        val elapsed = SystemClock.elapsedRealtime() - lastSnapshot.observedAtRealtime
        val expectedPos = lastSnapshot.projectedPositionMs()
        val actualPos = state.position
```
**Evidencia:** En `onPlaybackStateChanged`, `elapsed` se declara en la línea 1353 pero **no se utiliza** en las líneas subsiguientes ni en el cálculo de `expectedPos` (el cual delega el cálculo del delta a `projectedPositionMs()`).

### A8. Verbatim de Snapshots con contexto

**session.liveSnapshot:**
```kotlin
// MusicNotificationListener.kt:1387
        // "pendiente de compromiso". Si la sesión no resucita tras Doze, la archivaremos tarde.
        currentLogicalSession?.let { session ->
            if (session.liveSnapshot.packageName == controller.packageName) {
                serviceScope.launch {
--
// MusicNotificationListener.kt:1391
                serviceScope.launch {
                    val currentInfo = musicDataStore.musicInfoFlow.first()
                    val finalPos = session.liveSnapshot.projectedPositionMs()
                    
                    val pendingInfo = currentInfo.copy(
--
// MusicNotificationListener.kt:2085
            sessionUUID = session.sessionUUID,
            birthSnapshot = session.birthSnapshot,
            finalSnapshot = session.liveSnapshot,
            maxPositionMs = session.maxPositionMs,
            startedAtRealtime = session.startedAtRealtime
--
// MusicNotificationListener.kt:2104
            sessionUUID = session.sessionUUID,
            birthSnapshot = session.birthSnapshot,
            finalSnapshot = session.liveSnapshot,
            maxPositionMs = max(session.maxPositionMs, session.liveSnapshot.projectedPositionMs()),
            startedAtRealtime = session.startedAtRealtime
```

**lastLogicalSnapshot:**
```kotlin
// MusicNotificationListener.kt:256
    * Permite que el historial y la deduplicación funcionen con la pantalla apagada.
    */
    private var lastLogicalSnapshot: MediaSnapshot? = null
    
    /*
--
// MusicNotificationListener.kt:667
            observedAtRealtime = now
        )
        lastLogicalSnapshot = updatedSnapshot
        relaunchLyricsTicker("seek_event")
    }
--
// MusicNotificationListener.kt:1513
            activeSessions.isEmpty()
        ) {
            lastLogicalSnapshot?.let { last ->
                // WARM-UP DE DESPERTAR (v5.2.5): Bloqueo de Placeholder. Rescatamos del escudo antes de emitir SessionEnded.
                serviceScope.launch(Dispatchers.IO) {
--
// MusicNotificationListener.kt:1944
        // Esto permite que el historial detecte cambios aunque la pantalla esté apagada.
        val previousLogical =
            lastLogicalSnapshot
            
        val sessionChanged = currentLogicalSession?.identity != TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
--
// MusicNotificationListener.kt:2183
        // ACTUALIZACIÓN DEL DIARIO LÓGICO
        lastLogicalSnapshot = rawSnapshot
        lastObservedPositionMs = currentProjectedPos
```

---

## Sección B — Punto ciego de racha (continuación)

### B4. Verbatim completo de `updateRepeatStats`
Ubicación: `MusicDataStore.kt`

```kotlin
    suspend fun updateRepeatStats(title: String, artist: String, isSkip: Boolean): Pair<Int, Int> {
        var finalPlaysToday = 0
        var finalStreakDays = 0
        context.dataStore.edit { prefs ->
            val statsMap = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty()).toMutableMap()
            val identity = "$title|$artist"
            val existing = statsMap[identity]
            val today = java.time.LocalDate.now().toEpochDay()

            val updated = if (isSkip) {
                // El skip mata la racha inmediatamente
                RepeatStats(playsToday = 0, lastPlayedEpochDay = today, streakDays = 0)
            } else if (existing == null) {
                // Primera vez que suena
                RepeatStats(playsToday = 1, lastPlayedEpochDay = today, streakDays = 1)
            } else {
                when (today - existing.lastPlayedEpochDay) {
                    0L -> existing.copy(playsToday = existing.playsToday + 1) // Mismo día
                    1L -> existing.copy(playsToday = 1, lastPlayedEpochDay = today, streakDays = existing.streakDays + 1) // Día consecutivo
                    else -> RepeatStats(playsToday = 1, lastPlayedEpochDay = today, streakDays = 1) // Hueco temporal, reset
                }
            }

            finalPlaysToday = updated.playsToday
            finalStreakDays = updated.streakDays

            if (updated.playsToday > 0 || updated.streakDays > 0) {
                statsMap[identity] = updated
            } else {
                statsMap.remove(identity)
            }

            // Limpieza LRU: Mantener solo las últimas 30 canciones con racha activa
            if (statsMap.size > 30) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 30)
                keysToRemove.forEach { statsMap.remove(it) }
            }

            val newObj = JSONObject()
            statsMap.forEach { (k, v) ->
                val inner = JSONObject()
                inner.put("pt", v.playsToday)
                inner.put("lp", v.lastPlayedEpochDay)
                inner.put("sd", v.streakDays)
                newObj.put(k, inner)
            }
            prefs[REPEAT_STATS] = newObj.toString()
        }
        return finalPlaysToday to finalStreakDays
    }
```

### B5. Sitios de llamada de `updateRepeatStats(...)`
Existe una única llamada en el pipeline principal.

**Ubicación:** `MusicNotificationListener.kt`, dentro de la función `commitToHistory` (procesamiento del canal de historial).

```kotlin
// MusicNotificationListener.kt:872
            val newStreak = musicDataStore.updateSkipStreak(startSnapshot.title, startSnapshot.artist, isSkipped)
            val repeatAnalytics = musicDataStore.updateRepeatStats(startSnapshot.title, startSnapshot.artist, isSkipped)
            if (!isSkipped && !isPartial) musicDataStore.updateArtistStats(startSnapshot.artist)
```
**Argumento:** Se pasa la variable `isSkipped` (calculada localmente en la función).

### B6. Rastreo de la variable `isSkipped`
Dentro de `commitToHistory`:

1. **Línea 852 (Declaración):**
   `var isSkipped = progressFactor in 0.0f..0.4f`
2. **Línea 855-857 (Bloque "Blessed"):**
   ```kotlin
            val currentRAM = MusicStateProvider.current()
            val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
            if (isBlessed && isSkipped) isSkipped = false
   ```
3. **Línea 872 (Consumo):**
   `val repeatAnalytics = musicDataStore.updateRepeatStats(..., isSkipped)`

**Evidencia:** La variable `isSkipped` pasada a la racha es **la misma** que el bloque "Blessed" modifica. Si una canción fue escuchada previamente (`isBlessed = true`), el skip actual se ignora antes de llamar a `updateRepeatStats`.
