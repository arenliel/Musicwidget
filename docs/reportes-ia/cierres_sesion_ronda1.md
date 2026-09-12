# Auditoría — Ronda 1: Cierres de Sesión y Cálculo de Skip

**Confirmación de Git Log:**
```
2d4125d (HEAD -> master) Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
c9c5b7c Conjunto Letras-2: Regla Unificada de Silencio (10 segundos)
12b87e4 Conjunto Letras-1: Identidad Unificada para Preservación de Letra
6e5278f Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
77ff7a9 Conjunto F.1: Andamiaje del estado Cargando (Buffering)
```

---

## R1. Función completa que produce el log `[FSM_GUARD]`

Ubicada en `processSnapshot` (dentro de `MusicNotificationListener.kt`).

```kotlin
        // DECISIÓN ESTRUCTURAL: Solo rompemos la sesión si cambió la canción, loop o reinicio manual.
        // v9.0: isManualRewind ignorado si es provisional para evitar skips al boot (Bloque D.3)
        val sessionEnded = identityChanged || isRealLoop || (isManualRewind && session?.isProvisional == false)
        
        // REGLA D.2: Monotonía de la marca de agua. Sede única: LogicalSession.
        session?.let { s ->
            if (!identityChanged) {
                s.maxPositionMs = max(s.maxPositionMs, currentProjectedPos)
            }
        }

        // INSTRUMENTACIÓN BLOQUE 3.3
        InternalLogger.d(applicationContext, "[FSM_GUARD] identityChanged=$identityChanged, " +
            "projectedPos=${currentProjectedPos}ms, rawPos=${rawSnapshot.positionMs}ms, maxPos=${session?.maxPositionMs ?: 0}ms, " +
            "delta=${currentProjectedPos - lastProjectedPos}ms, taken=${if (sessionEnded) "ENDED" else if (isCatchUpRender) "CATCHUP" else "FUSION"}")
```

**Lógica de decisión:**
- `ENDED`: Si `sessionEnded` es true (cambio de identidad, loop real o reinicio manual no provisional).
- `CATCHUP`: Si no terminó pero `isCatchUpRender` es true.
- `FUSION`: En cualquier otro caso.
- **Delta:** `currentProjectedPos - lastProjectedPos`. `currentProjectedPos` viene de `rawSnapshot.projectedPositionMs()` y `lastProjectedPos` es el valor proyectado del snapshot anterior.

---

## R2. Función completa que produce el log `[DIAG_V6] [SKIP_MATH]`

Ubicada en `commitToHistory` (dentro de `MusicNotificationListener.kt`).

```kotlin
        try {
            // ... (preparación de artwork y duración)
            
            // v9.0: Usar marca de agua rehidratada/persistente para clasificación (Bloque D)
            val finalPos = maxPositionMs
            
            // CASCADA DE DURACIÓN (v9.0: Blindaje contra valores <= 0)
            val effectiveDuration = when {
                endSnapshot.durationMs > 0 -> endSnapshot.durationMs
                startSnapshot.durationMs > 0 -> startSnapshot.durationMs
                else -> {
                    val diskDur = musicDataStore.musicInfoFlow.first().durationMs
                    if (diskDur > 0) diskDur else 0L
                }
            }

            val progressFactor = if (effectiveDuration > 0) {
                finalPos.toFloat() / effectiveDuration.toFloat()
            } else -1f // Indicador de UNKNOWN

            // FÓRMULA DE SKIP PURA (v6.5): Basada exclusivamente en el progreso del Snapshot final
            // v7.0: Blindaje contra división por cero (UNKNOWN)
            var isSkipped = progressFactor in 0.0f..0.4f
            
            InternalLogger.d(applicationContext, "[DIAG_V6] [SKIP_MATH] Track=${endSnapshot.title}, FinalPos=${finalPos}ms, Duration=${effectiveDuration}ms, Factor=$progressFactor, Verdict=$isSkipped")
            
            // ... (lógica de Blessed y Partial)
```

**Umbral exacto:** `progressFactor in 0.0f..0.4f` (Verdict=true si el progreso es entre 0% y 40%).

---

## R3. Funciones completas relacionadas con "pendiente de compromiso"

### 1. Marcado como pendiente (en `onSessionDestroyed`)
```kotlin
                        // REGLA v6.7: Cierre Diferido.
                        // Cuando la sesión muere, guardamos su estado en el DataStore marcándola como
                        // "pendiente de compromiso". Si la sesión no resucita tras Doze, la archivaremos tarde.
                        currentLogicalSession?.let { session ->
                            if (session.liveSnapshot.packageName == controller.packageName) {
                                serviceScope.launch {
                                    val currentInfo = musicDataStore.musicInfoFlow.first()
                                    val finalPos = session.liveSnapshot.projectedPositionMs()
                                    
                                    val pendingInfo = currentInfo.copy(
                                        isPendingCommit = true,
                                        lastMaxPositionMs = Math.max(currentInfo.lastMaxPositionMs, finalPos),
                                        isSessionActive = false,
                                        isPlaying = false
                                    )
                                    musicDataStore.saveMusicInfo(pendingInfo)
                                    
                                    MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))
                                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                                    
                                    InternalLogger.d(applicationContext, "[FSM] Sesión interrumpida (UUID=${session.sessionUUID}). Marcada como pendiente de compromiso.")
                                }
                            }
                        }
```

### 2. Resurrección y Cierre Retroactivo (en `processSnapshot`)
```kotlin
        // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
        if (session != null && session.isProvisional) {
            if (!identityChanged) {
                // ESCENARIO A: Resurrección tras Doze confirmada. 
                // Adoptamos la sesión rehidratada y limpiamos el flag de provisional.
                session.isProvisional = false
                InternalLogger.d(applicationContext, "[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=${session.sessionUUID}")
            } else {
                // ESCENARIO B: Cierre Real. La canción cambió mientras el widget dormía.
                // Archivamos la sesión vieja usando el watermark persistido.
                historyChannel.trySend(HistoryEvent.CommitSession(
                    sessionUUID = session.sessionUUID,
                    birthSnapshot = session.birthSnapshot,
                    finalSnapshot = session.liveSnapshot,
                    maxPositionMs = session.maxPositionMs,
                    startedAtRealtime = session.startedAtRealtime
                ))
                InternalLogger.d(applicationContext, "[HIST_BOOT] CIERRE RETROACTIVO: uuid=${session.sessionUUID}")
                currentLogicalSession = null
                // Continuamos al flujo normal de creación de sesión nueva
            }
        }
```
**Campos de posición usados:** `session.maxPositionMs`. Este valor viene de la rehidratación en `onListenerConnected` (vía `currentInfo.lastMaxPositionMs`) y se actualiza durante la vida de la sesión en `processSnapshot` (monotonía de la marca de agua).

---

## R4. Función completa que produce el log `"Display fully visible"`

```kotlin
    private fun onDisplayFullyVisible() {
        InternalLogger.d(applicationContext, "[GATING] Display fully visible. Triggering Wake-up Sync.")
        InternalLogger.log(applicationContext, "GATING: Desbloqueo detectado. Forzando reprocesamiento de sesión.")
        
        if (hasPendingUpdates) {
            InternalLogger.d(applicationContext, "[GATING] Aplicando actualizaciones postergadas a Glance.")
            hasPendingUpdates = false
            serviceScope.launch {
                MusicWidget.updateAll(applicationContext)
            }
        }

        // Sincronización de recuperación:
        // Forzamos al proceso a descargar recursos que se omitieron durante el bloqueo.
        serviceScope.launch {
            refreshBestSession(reason = "catch_up_render")
            // PASO 4: Iniciar reconciliación del historial pendiente
            reconcilePendingHistoryArtworks()
        }
    }
```

---

## R5. Función completa que maneja `"Notificación removida"`

```kotlin
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        
        if (pkg == lastObservedSnapshot?.packageName) {
            InternalLogger.d(applicationContext, "[REACTIVE] Notificación removida para $pkg. Sincronizando sesión.")
            serviceScope.launch {
                refreshBestSession(reason = "notification_removed")
            }
        }
    }
```

---

## R6. Todos los lugares donde se asigna `lastLogicalSnapshot`

### 1. En `startSeekEventProcessor`
```kotlin
    @OptIn(FlowPreview::class)
    private fun startSeekEventProcessor() {
        serviceScope.launch {
            seekEventFlow
                .debounce(400L)
                .collect { (snapshot, position, detectedAt) ->
                    val now = SystemClock.elapsedRealtime()
                    val processingLag = now - detectedAt
                    InternalLogger.d(applicationContext, "[LYRICS_TRACE] Aplicando Seek (Lag compensado: ${processingLag}ms): ${position + processingLag}ms")
                    
                    val updatedSnapshot = snapshot.copy(
                        positionMs = position + processingLag,
                        observedAtRealtime = now
                    )
                    lastLogicalSnapshot = updatedSnapshot
                    relaunchLyricsTicker("seek_event")
                }
        }
    }
```

### 2. En `startBlacklistObserver`
```kotlin
    private fun startBlacklistObserver() {
        serviceScope.launch {
            musicDataStore.musicInfoFlow.collect { info ->
                val currentPkg = lastObservedSnapshot?.packageName
                if (currentPkg != null && info.blacklist.contains(currentPkg)) {
                    InternalLogger.d(applicationContext, "[BLACKLIST_PURGE] App actual $currentPkg ha sido añadida a la lista negra. Purgando.")
                    
                    // 1. Limpieza en Disco
                    musicDataStore.clearActiveSession()
                    
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
                    lastObservedSnapshot = null
                    inFlightSnapshot = null
                    savedArtworkKey = null
                    savedAppIconKey = null
                    
                    // 3. Sincronía Atómica: El observador startRamMirror actualizará la memoria (Fast-Track)
                    
                    // 4. Forzar actualización de Glance
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    
                    // 5. Intento de relevo
                    refreshBestSession(reason = "blacklist_handover")
                }
            }
        }
    }
```

### 3. En `onListenerConnected`
```kotlin
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                // ...
            }
```

### 4. En `processSnapshot` (Fusión de estado)
```kotlin
        } else {
            // FUSIÓN DE ESTADO (v6.5): Solo actualizamos liveSnapshot y contexto
            val updatedContext = PlaybackContext(
                durationMs = rawSnapshot.durationMs,
                album = rawSnapshot.album,
                artworkKey = rawSnapshot.artworkKey
            )
            session?.let { s ->
                s.liveSnapshot = rawSnapshot
                s.playbackContext = updatedContext
            }
        }

        // ACTUALIZACIÓN DEL DIARIO LÓGICO
        lastLogicalSnapshot = rawSnapshot
        lastObservedPositionMs = currentProjectedPos
```

### 5. En `processSnapshot` (Actualización de Artwork)
```kotlin
                                // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
                                // Inyectamos el bitmap en el snapshot lógico para que la próxima 
                                // transición de historial lo lleve ya resuelto.
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(
                                    artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
                                )
```
