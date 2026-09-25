# AUDITORÍA — lastLogicalSnapshot y projectedPositionMs (RONDA 1)

## Paso 0 — Confirmación de HEAD
```text
b1af893415c1a6c5281706df6088353595073701
```

## Paso 1 — Todas las lecturas y escrituras de `lastLogicalSnapshot` (`grep -n "lastLogicalSnapshot" -r app/src/main/java/`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:290: private var lastLogicalSnapshot: MediaSnapshot? = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:702: lastLogicalSnapshot = updatedSnapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:732: lastLogicalSnapshot = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1202: lastLogicalSnapshot = recoveredSnapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2058: lastLogicalSnapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2363: lastLogicalSnapshot = rawSnapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2374: val refinedSnapshot = lastLogicalSnapshot ?: return@launch
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2700: lastLogicalSnapshot = lastLogicalSnapshot?.copy(
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2700: lastLogicalSnapshot = lastLogicalSnapshot?.copy(
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2900: val snapshot = lastLogicalSnapshot ?: break
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2963: val pausedPos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
```

## Paso 2 — Contexto verbatim de cada asignación (escritura) a `lastLogicalSnapshot`

### 1. Asignación en línea 702 (`lastLogicalSnapshot = updatedSnapshot`)
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

### 2. Asignación en línea 732 (`lastLogicalSnapshot = null`)
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

### 3. Asignación en línea 1202 (`lastLogicalSnapshot = recoveredSnapshot`)
```kotlin
            InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.")
            val currentInfo = musicDataStore.musicInfoFlow.first()
            InternalLogger.d(applicationContext, "[HIST_BOOT] BOOT_DATA_READY: sessionUUID=${currentInfo.sessionUUID}")
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                
                val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
                
                currentLogicalSession = LogicalSession(
                    sessionUUID = currentInfo.sessionUUID,
                    identity = recoveredIdentity,
                    birthSnapshot = recoveredSnapshot,
                    liveSnapshot = recoveredSnapshot,
                    frozenTrackKey = currentInfo.trackKey,
                    maxPositionMs = currentInfo.lastMaxPositionMs, // v9.0: Recuperar marca de agua
                    isProvisional = true, // Marcada como provisional (BLOQUE 6.1)
                    startedAtRealtime = android.os.SystemClock.elapsedRealtime(),
                    playbackContext = PlaybackContext(
                        durationMs = currentInfo.durationMs,
                        album = currentInfo.album,
                        artworkKey = currentInfo.artworkKey,
                        confirmedArtworkKey = rehydratedArtworkKey
                    ),
                    context = this@MusicNotificationListener
                )
```

### 4. Asignación en línea 2363 (`lastLogicalSnapshot = rawSnapshot`)
```kotlin
            session?.let { s ->
                s.liveSnapshot = rawSnapshot
                s.playbackContext = updatedContext
            }
        }

        // Conjunto Letras-Atomicas-8: se aplica sea cual sea la rama (creación o fusión)
        // recién tomada arriba, porque `session` ya apunta a la sesión vigente aquí.
        if (isPlaying) {
            session?.hasConfirmedPlayback = true
        }

        // ACTUALIZACIÓN DEL DIARIO LÓGICO (Cierres-3)
        lastLogicalSnapshot = rawSnapshot
        lastAppliedSnapshot = snapshot
        lastObservedPositionMs = currentProjectedPos

        // ACTIVE WATCHER (v4.3.1): Cronómetro proactivo de 5s con LATE-READ
        if (snapshot.playbackState == PlaybackState.STATE_PLAYING && (trackContentChanged || eagerCacheJob == null)) {
            val uuid = session?.sessionUUID ?: ""
            eagerCacheJob?.cancel()
            eagerCacheJob = serviceScope.launch {
                delay(5000L)
                // Obtenemos el estado refinado (con portada cargada) tras la espera
                val refinedSnapshot = lastLogicalSnapshot ?: return@launch
                persistHistoryArtworkEagerly(refinedSnapshot, uuid)
            }
        } else if (snapshot.playbackState != PlaybackState.STATE_PLAYING) {
            // Cancelación inmediata en pausa/stop para ahorro de recursos
            eagerCacheJob?.cancel()
            eagerCacheJob = null
        }
```

### 5. Asignación en línea 2700 (`lastLogicalSnapshot = lastLogicalSnapshot?.copy(...)`)
```kotlin
                            // Paso 3.2: CACHING DE TRANSFORMACIÓN
                            if (session?.playbackContext?.confirmedArtworkKey != snapshot.artworkKey) {
                                // 1. Guardar versión RAW
                                InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                                
                                // 2. Guardar versión WIDGET (Píldora)
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
                                
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                                session?.let { it.playbackContext = it.playbackContext.copy(confirmedArtworkKey = snapshot.artworkKey) }

                                // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
                                // Inyectamos el bitmap en el snapshot lógico para que la próxima 
                                // transición de historial lo lleve ya resuelto.
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(
                                    artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
                                )
                            }
```

## Paso 3 — Definición de `projectedPositionMs` (`grep -n "fun projectedPositionMs" -r app/src/main/java/`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:569:    fun projectedPositionMs(
```
Cuerpo completo:
```kotlin
        /** 
         * ORÁCULO DE TIEMPO PURO (v9.0): Proyecta la posición basado en el tiempo transcurrido.
         * No conserva estado de marca de agua (Regla D.4).
         */
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

## Paso 4 — Orden de ejecución respecto a `relaunchLyricsTicker`
- Línea de invocación a `relaunchLyricsTicker("identity_change")`: **Línea 2439**
- Línea de la asignación a `lastLogicalSnapshot = rawSnapshot` más cercana dentro de la misma función (`processSnapshot`): **Línea 2363**
