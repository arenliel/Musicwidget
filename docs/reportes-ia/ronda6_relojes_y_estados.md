# Auditoría — Ronda 6: Función Completa y Origen del Texto de Estado

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```

---

## G1. Código completo de `processSnapshot`
Ubicación: `MusicNotificationListener.kt` (Líneas 1900-2561)

```kotlin
    private suspend fun processSnapshot(
        controller: MediaController?,
        metadata: MediaMetadata?,
        rawSnapshot: MediaSnapshot,
        reason: String
    ) {
        // v8.0: Bloqueo proactivo hasta que la rehidratación termine (BLOQUE A)
        kotlinx.coroutines.withTimeoutOrNull(BOOT_GATE_TIMEOUT_MS) { bootGate.await() }
            ?: InternalLogger.w(applicationContext, "[HIST_BOOT] BOOT_GATE_TIMEOUT: procesando paquete vivo ($reason) sin estado rehidratado")

        val session = currentLogicalSession
        
        val stateName = when(rawSnapshot.playbackState) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            else -> "OTHER(${rawSnapshot.playbackState})"
        }
        InternalLogger.d(applicationContext, "[DIAG_V5] [INTAKE] Recibido: Estado=$stateName, Track=${rawSnapshot.title}, Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}ms, Reason=$reason")

        // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---

        val currentMem = MusicStateProvider.current()

        // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
        // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
        // abortamos para proteger el estado coherente en RAM y Disco.
        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        val isDegraded = rawSnapshot.durationMs <= 0L

        if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
            return
        }

        /*
         * Creamos una nueva generación de forma atómica.
         */
        val myGeneration =
            generation.incrementAndGet()

        // REGLA: Usamos lastLogicalSnapshot para la deduplicación de negocio
        // Esto permite que el historial detecte cambios aunque la pantalla esté apagada.
        val previousLogical =
            lastLogicalSnapshot

        val sessionChanged = currentLogicalSession?.identity != TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        
        if (sessionChanged) {
            // Limpieza de Memoria RAM (v5.2.3): Purga basada en CoreKey
            val myCoreKey = "${sanitize(rawSnapshot.title)}|${sanitize(rawSnapshot.artist)}"
            memoryArtworkCache.keys.retainAll(setOf(myCoreKey))
        }

        val trackContentChanged = previousLogical?.artworkKey != rawSnapshot.artworkKey

        // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
        // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
        val isCatchUp = reason == "catch_up_render"
        
        // Detección de Incoherencia de Imagen: Si la portada en disco no coincide con la del snapshot, forzamos bypass
        val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()

        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        if (!isCatchUp && !trackContentChanged && !artIncoherent && 
            currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
            currentMem.isSessionActive == rawSnapshot.isSessionActive) {
            
            // REGLA B.1 (v9.1): Actualización de Verdad (Posición) previa al Redibujado.
            // Aseguramos que la marca de agua progrese aunque el refresco visual sea ignorado.
            session?.let { s ->
                s.maxPositionMs = Math.max(s.maxPositionMs, rawSnapshot.projectedPositionMs())
            }

            // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
            lastObservedSnapshot = rawSnapshot
            InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
            return
        }

        if (isCatchUp || artIncoherent) {
            InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
        }

        val appChanged = previousLogical?.packageName != rawSnapshot.packageName
        
        if (appChanged) {
            currentIconTier = TIER_NONE
            // REGLA: Limpieza de Iconos (Icon Fix) ante cambios de app
            saveTextToFile("", APP_ICON_KEY_FILE)
            savedAppIconKey = null
            
            // Hallazgo v3.4: Limpieza preventiva de RAM en transición
            serviceScope.launch {
                MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(0L)) // Simulamos fin de sesión
            }
        }

        // REGLA DE PROMOCIÓN DE SESIÓN (Persistent Snapshot)
        if (sessionChanged && previousLogical != null && rawSnapshot.packageName != previousLogical.packageName) {
            val isNewSessionWeak = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING
            if (isNewSessionWeak) {
                InternalLogger.d(applicationContext, "[DIAGNOSTIC] IGNORED: Ignorando sesión débil de ${rawSnapshot.packageName}")
                return
            }
        }

        // FILTRO DE IDENTIDAD (Allow-list)
        if (!isAppAllowed(rawSnapshot.packageName)) return

        val currentBlacklist = musicDataStore.musicInfoFlow.first().blacklist
        if (currentBlacklist.contains(rawSnapshot.packageName)) return

        val isSameSession = session?.sessionIdentity == rawSnapshot.sessionIdentity
        
        val firstObservedAt = if (isSameSession && session != null) {
            session.startedAtRealtime // Usar el inicio real de la sesión (v9.0)
        } else {
            rawSnapshot.recordedAt
        }

        // --- PURGA DE TRANSICIÓN Y PROTECCIÓN DE HERENCIA (v9.0: Sede única en session) ---
        // Bloqueamos la herencia de marcas de agua (maxPositionMs) y assets si el título cambia.
        // Si el título entrante es nulo o distinto, el snapshot nace desde cero.
        val canInheritAssets = isSameSession && rawSnapshot.title == session?.identity?.title && rawSnapshot.title.isNotBlank()

        val snapshot = rawSnapshot.copy(
            firstObservedAt = firstObservedAt,
            artworkSource = if (canInheritAssets) (session?.birthSnapshot?.artworkSource ?: rawSnapshot.artworkSource) else rawSnapshot.artworkSource
        )

        // --- MOTOR DE TRANSICIÓN VECTORIAL (v9.0) ---
        val currentIdentity = session?.identity
        val newIdentity = TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        
        val currentProjectedPos = rawSnapshot.projectedPositionMs()
        val lastProjectedPos = previousLogical?.projectedPositionMs() ?: 0L
        
        val isPlaying = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING
        val progressFactor = if (rawSnapshot.durationMs > 0) currentProjectedPos.toFloat() / rawSnapshot.durationMs.toFloat() else 0f

        // BLOQUE 3.2: Guarda de identidad primero, heurística de posición después
        val identityChanged = currentIdentity != newIdentity
        
        // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
        val isManualRewind = currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged

        // 2. Evaluamos si es un resurgimiento del sistema sin cambio real de tiempo (Catch-up)
        val isCatchUpRender = if (identityChanged) false else Math.abs(currentProjectedPos - lastProjectedPos) < 1500L

        // 3. Detectamos el loop perfecto
        val isRealLoop = isPlaying && 
                         currentProjectedPos < 2000L && 
                         progressFactor > 0.95f && 
                         !identityChanged

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

        if (sessionEnded && !isCatchUpRender) {
            // AQUÍ EJECUTAMOS EL COMPROMISO ATÓMICO AL HISTORIAL (v6.5)
            currentLogicalSession?.let { session ->
                // Verificación de seguridad v7.0: Una sesión provisional nunca emite aquí
                if (session.isProvisional) return@let

                val commitEvent = HistoryEvent.CommitSession(
                    sessionUUID = session.sessionUUID,
                    birthSnapshot = session.birthSnapshot,
                    finalSnapshot = session.liveSnapshot,
                    maxPositionMs = max(session.maxPositionMs, session.liveSnapshot.projectedPositionMs()),
                    startedAtRealtime = session.startedAtRealtime
                )
                
                // BLOQUE 7.2: Detector de Atribución (Anti-Envenenamiento)
                if (commitEvent.finalSnapshot.title != session.birthSnapshot.title ||
                    commitEvent.finalSnapshot.artist != session.birthSnapshot.artist) {
                    InternalLogger.e(applicationContext, "[HIST_POISON] Identidad divergente en commit. " +
                        "birth='${session.birthSnapshot.title} / ${session.birthSnapshot.artist}' " +
                        "capsule='${commitEvent.finalSnapshot.title} / ${commitEvent.finalSnapshot.artist}' " +
                        "uuid=${session.sessionUUID}")
                }

                val res = historyChannel.trySend(commitEvent)
                
                totalEventsCounter.incrementAndGet()
                if (res.isSuccess) {
                    successCounter.incrementAndGet()
                    pendingEventsCount.incrementAndGet()
                } else {
                    failureCounter.incrementAndGet()
                }
                
                InternalLogger.d(applicationContext, "[HIST_CHANNEL] EVENT_SENT: Success=${res.isSuccess}, Failure=${res.isFailure}, Closed=${res.isClosed}, Track=${session.liveSnapshot.title}")
                if (res.isFailure) {
                    InternalLogger.e(applicationContext, "[HIST_CHANNEL] FAIL_CAUSE: ${res.exceptionOrNull()?.message}")
                }
            }
            
            purgeZombieControllers()
            
            // GENERAMOS LA NUEVA SESIÓN (Su cronómetro arranca en el constructor)
            val newContext = PlaybackContext(
                durationMs = rawSnapshot.durationMs,
                album = rawSnapshot.album,
                artworkKey = rawSnapshot.artworkKey
            )
            val newSession = LogicalSession(
                identity = newIdentity,
                birthSnapshot = rawSnapshot,
                liveSnapshot = rawSnapshot,
                frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
                maxPositionMs = rawSnapshot.positionMs, // v9.0: Reset marca de agua
                playbackContext = newContext,
                context = this@MusicNotificationListener
            )
            currentLogicalSession = newSession
            InternalLogger.d(applicationContext, "[FSM] Nueva Sesión Creada (UUID=${newSession.sessionUUID}): ${rawSnapshot.title}")
            
            // BUFFER DE NACIMIENTO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
            val myCoreKey = snapshot.coreKey
            val bitmap = memoryArtworkCache[myCoreKey]
            val uuid = newSession.sessionUUID
            if (bitmap != null) {
                serviceScope.launch(Dispatchers.IO) {
                    val density = applicationContext.resources.displayMetrics.density
                    val w = (80 * density).toInt()
                    val h = (40 * density).toInt()
                    val historyPill = ImageUtils.createHorizontalPill(bitmap, w, h)
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, historyPill, uuid)
                    historyPill.recycle()
                    InternalLogger.d(applicationContext, "[FSM] Portada de nacimiento persistida (UUID=$uuid)")
                }
            }
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

        val isSessionEnded = sessionEnded
        val isTrackContentChanged = trackContentChanged

        // FAST-TRACK SSOT (v4.0 - Relevo Atómico de RAM)
        serviceScope.launch {
            mutationMutex.withLock {
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val currentInfo = musicDataStore.musicInfoFlow.first()
                
                // Hallazgo v3.7: Inmunidad de Salida.
                val (plays, skip, freq) = when {
                    isSessionEnded -> Triple(0, 0, false)
                    !isPlaying -> Triple(currentMem.playsToday, currentMem.skipStreak, currentMem.isFrequentArtist)
                    else -> musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
                }
                
                val memInfo = MusicInfo(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    packageName = snapshot.packageName,
                    album = snapshot.album ?: "",
                    trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                    artworkKey = currentInfo.artworkKey,
                    artworkUri = snapshot.artworkUri ?: currentInfo.artworkUri,
                    appIconKey = currentInfo.appIconKey,
                    isPlaying = isPlaying,
                    isSessionActive = snapshot.isSessionActive,
                    currentLyric = if (!isSessionEnded) currentMem.currentLyric else "",
                    lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
                    playbackDeviceName = snapshot.playbackDeviceName,
                    playbackDeviceType = snapshot.playbackDeviceType,
                    durationMs = snapshot.durationMs,
                    history = currentInfo.history,
                    playsToday = plays,
                    skipStreak = skip,
                    isFrequentArtist = freq,
                    lastUpdateEpoch = currentMem.lastUpdateEpoch,
                    observedAtRealtime = currentMem.observedAtRealtime,
                    sessionUUID = session?.sessionUUID ?: "",
                    isPendingCommit = false,
                    lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                )
                
                val event = if (isSessionEnded) {
                    MusicUpdateEvent.NewSession(memInfo)
                } else if (isTrackContentChanged) {
                    MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying)
                } else {
                    MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType)
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
        // Si es visible, dejamos que el Stage 2 maneje la persistencia final para evitar race conditions.
        if (!isWidgetPotentiallyVisible()) {
            serviceScope.launch {
                val currentInfo = musicDataStore.musicInfoFlow.first()
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                
                val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                val logicalMusicInfo = MusicInfo(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    packageName = snapshot.packageName,
                    trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                    artworkKey = snapshot.artworkKey,
                    artworkUri = snapshot.artworkUri ?: "",
                    appIconKey = savedAppIconKey ?: "",
                    isPlaying = isPlaying,
                    isSessionActive = snapshot.isSessionActive,
                    currentLyric = finalLyric,
                    lyricsTrackKey = finalLyricKey,
                    playbackDeviceName = snapshot.playbackDeviceName,
                    playbackDeviceType = snapshot.playbackDeviceType,
                    durationMs = snapshot.durationMs,
                    sessionUUID = session?.sessionUUID ?: "",
                    isPendingCommit = false, // Reconciliación exitosa (v6.7)
                    lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                )
                val changed = musicDataStore.saveMusicInfo(logicalMusicInfo, forceUpdate = false)
                if (changed) {
                    lastCommittedInfo = logicalMusicInfo
                }
            }
        }

        // --- STAGE 2: PRESENTACIÓN (BLOQUEO POR COMPUERTA) ---

        if (!isWidgetPotentiallyVisible()) {
            // NOTE (Conjunto B.2, decision recorded — do not "fix" without checking with the
            // project owner first): artwork resolution is intentionally skipped entirely while the
            // screen is off, including cases where a corrected artwork would otherwise reach the
            // history. This is a deliberate battery-saving tradeoff, not an oversight.
            Log.d(TAG, "[GATING] Presentación suprimida (Pantalla apagada/bloqueada).")
            InternalLogger.log(applicationContext, "STAGE 2: Suprimido (Pantalla bloqueada). Track=${snapshot.title}")
            isPresentationDirty = true
            pendingSnapshot = snapshot
            
            // Destrucción de Ticker de Letras para ahorro de batería
            lyricsUpdateJob?.cancel()
            
            // Abortamos Stage 2 para evitar I/O y CPU innecesarios
            lastObservedSnapshot = snapshot
            return
        }

        // Si el widget es visible, reseteamos flags de gating
        isPresentationDirty = false
        pendingSnapshot = null

        val previousApplied = 
            lastAppliedSnapshot

        val trackChangedUI = 
            previousApplied?.trackKey != snapshot.trackKey

        val appChangedUI = 
            previousApplied?.packageName != snapshot.packageName

        val stateChangedUI = 
            previousApplied?.playbackState != snapshot.playbackState

        val artworkChangedUI =
            previousApplied?.artworkKey != snapshot.artworkKey

                InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")

        try {

            // 1. Resolución de recursos visuales (Fase Cancelable).
            var resolvedArtwork: Bitmap? = null
            var resolvedAppIconFinal: Bitmap? = null
            var resolvedIconKey: String? = null
            var resolvedTierFinal: Int = TIER_NONE

            // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
            // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
            var artworkTimedOut = false

            if (controller != null && metadata != null && 
                (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
                
                // A. Portada (v6.3 Pipeline Unificado)
                resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
                    resolveArtworkDeduplicated(
                        snapshot = snapshot,
                        controller = controller,
                        metadata = metadata,
                        generation = myGeneration
                    )
                } ?: run {
                    artworkTimedOut = true
                    null
                }

                // B. Icono de app (Optimizado)
                if (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION) {
                    val (icon, tier) = resolveAppIcon(snapshot.packageName)
                    
                    // Solo actualizamos si el nuevo tier es mejor o igual al actual (o es un cambio de app)
                    if (icon != null && (appChangedUI || tier > currentIconTier)) {
                        resolvedAppIconFinal = icon
                        resolvedIconKey = "${snapshot.packageName}_stable"
                        resolvedTierFinal = tier
                    }
                }
            }

            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (trackChangedUI) {
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
            } else {
                // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
                if (currentLyrics == null) {
                    currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                }

                val effectivePos = previousApplied?.projectedPositionMs() ?: 0L
                val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
                
                // Hard-Sync: Solo si el desvío es mayor a 1s o cambió el estado
                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true

                if (shouldResync && currentLyrics != null) {
                    relaunchLyricsTicker("state_sync")
                }
            }

            val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
            if (myGeneration != generation.get() && !isStillRelevant) {
                Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
                return
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                commitMutex.withLock {

                    val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
                    if (myGeneration != generation.get() && !isStillRelevantInLock) {
                        Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
                        return@withLock
                    }

                    if (controller != null && metadata != null && 
                        (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
                        
                        if (resolvedArtwork != null) {
                            // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
                            // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
                            // SEGURIDAD IPC (v4.5): Escalado de cortesía para el bus Binder.
                            val transportBitmap = scaleForTransport(resolvedArtwork)
                            val cacheKey = "${rawSnapshot.artworkKey}_raw"
                            MusicWidget.bitmapCache.put(cacheKey, transportBitmap)

                            // Paso 3.2: CACHING DE TRANSFORMACIÓN
                            if (savedArtworkKey != snapshot.artworkKey) {
                                // 1. Guardar versión RAW
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                                
                                // 2. Guardar versión WIDGET (Píldora)
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
                                
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                                savedArtworkKey = snapshot.artworkKey

                                // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
                                // Inyectamos el bitmap en el snapshot lógico para que la próxima 
                                // transición de historial lo lleve ya resuelto.
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(
                                    artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
                                )
                            }
                        } else if (trackChangedUI || artworkChangedUI) {
                            // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
                            val placeholder = getPlaceholderBitmap()
                            saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                            saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
                            saveTextToFile("", ALBUM_ART_KEY_FILE)
                            savedArtworkKey = null
                        }

                        if (resolvedAppIconFinal != null && resolvedIconKey != null) {
                            saveBitmapToFile(resolvedAppIconFinal, APP_ICON_FILE)
                            saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
                            savedAppIconKey = resolvedIconKey
                            currentIconTier = resolvedTierFinal
                        } else if (appChangedUI) {
                            // FIX: Solo borramos la llave si la APP cambió y no tenemos nuevo icono.
                            // Esto evita la alternancia visual (flicker) al cambiar de track en la misma app.
                            saveTextToFile("", APP_ICON_KEY_FILE)
                            savedAppIconKey = null
                            currentIconTier = TIER_NONE
                        }
                    }

                    val currentInfo = musicDataStore.musicInfoFlow.first()
                    val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                    val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                    
                    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                    val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                    val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)

                    val finalMusicInfo = MusicInfo(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        packageName = snapshot.packageName,
                        album = snapshot.album ?: "",
                        trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                        artworkKey = snapshot.artworkKey,
                        artworkUri = snapshot.artworkUri ?: "",
                        appIconKey = savedAppIconKey ?: "",
                        isPlaying = isPlaying,
                        isSessionActive = snapshot.isSessionActive,
                        currentLyric = finalLyric,
                        lyricsTrackKey = finalLyricKey,
                        playbackDeviceName = snapshot.playbackDeviceName,
                        playbackDeviceType = snapshot.playbackDeviceType,
                        durationMs = snapshot.durationMs,
                        history = currentInfo.history,
                        playsToday = playsToday,
                        skipStreak = skipStreak,
                        isFrequentArtist = isFrequent,
                        sessionUUID = session?.sessionUUID ?: "",
                        isPendingCommit = false,
                        lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                    )

                    // 1. Sincronía Atómica: Disco -> RAM -> UI
                    val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
                    
                    if (artworkTimedOut) {
                        Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
                    }
                    
                    // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
                    // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
                    val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo))

                    // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
                    // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
                    
                    if (changedDisco || isSessionEnded || changedRAM) {
                        if (isSessionEnded) {
                            uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
                        } else {
                            uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                        }
                    }
                    
                    lastAppliedSnapshot = snapshot
                    lastObservedSnapshot = snapshot
                    lastCommittedInfo = MusicStateProvider.current()
                }
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "[DIAGNOSTIC] CANCELLED: #$myGeneration aborted during resolution")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error en pipeline atatomic #$myGeneration", e)
        } finally {
            if (inFlightSnapshot?.contentKey == snapshot.contentKey) {
                inFlightSnapshot = null
            }
        }
    }
```

---

## G2. Cómo se construye `artworkKey`
Ubicación: `MusicNotificationListener.kt` (Dentro de `MediaSnapshot`)

```kotlin
        /**
         * Visual identity key used for artwork resolution and caching. Falls back to its own
         * album-aware key (not [trackKey]) specifically so that different album editions of the
         * same song can carry different artwork. Do not change this fallback to use [trackKey] —
         * that reintroduces cross-contamination between album editions' cached artwork (see the
         * "Harana" and "Archie, Marry Me" history bugs, Conjunto C).
         */
        val artworkKey: String
            get() =
                artworkUri
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
```
**Análisis:** Depende de `artworkUri` o, en su defecto, de la identidad de la sesión + el nombre del álbum normalizado + la duración. Si el `artworkUri` cambia entre lecturas (por ejemplo, si Spotify rota URLs temporales) o si el álbum viene nulo en una lectura y con valor en otra, el `artworkKey` cambiará, forzando un bypass de la deduplicación.

---

## G3. Generación del Texto de Estado
Ubicación: `MusicWidget.kt` (Función `getStatusText`)

```kotlin
    private fun getStatusText(context: Context, info: MusicInfo): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val timeSinceLastUpdate = now - info.observedAtRealtime
        
        // MOTOR DE CONSCIENCIA TEMPORAL (v2.1)
        // Umbral de 15 minutos para considerar una sesión de pausa como "estancada" (stale).
        val PAUSE_STALE_THRESHOLD = 15 * 60 * 1000L

        return when {
            info.isPlaying -> context.getString(R.string.status_listening)
            
            // Si la sesión está en pausa, pero ha pasado el umbral, dejamos que pase al flujo de "Hace poco"
            info.isSessionActive && timeSinceLastUpdate < PAUSE_STALE_THRESHOLD -> 
                context.getString(R.string.status_paused)
            
            else -> { 
                val time = formatRelativeTime(context, info.observedAtRealtime)
                if (time.isEmpty()) context.getString(R.string.status_recently) else time 
            }
        }
    }
```

**Valores en `strings.xml`:**
- `status_listening`: "Está sonando"
- `status_paused`: "En pausa"
- `status_recently`: "Hace poco"

**Identificación:** El punto de inserción para el estado "Cargando..." sería dentro de este `when`, evaluando una nueva propiedad `isBuffering` (o similar) en el objeto `MusicInfo`.
