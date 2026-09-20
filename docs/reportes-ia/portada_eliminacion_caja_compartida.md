# Auditoría — Código Completo de processSnapshot y de la Persistencia de Portada del Historial

## Confirmación de Estado
**Git Log:**
```text
bdb664c (HEAD -> master) Conjunto Identidad-Final (Instrumentación): Trazas de reintento de letras y sincronía de portada
b00b254 Conjunto Identidad-Final (Parte 2): Unificación de porteros de letras a identidad de negocio
ccc3816 Conjunto Identidad-Final (Parte 1): Migración a sessionIdentity con instrumentación de traza
```

---

## Tarea 1 — Código completo de `processSnapshot`

### PARTE 1 de 3 (Líneas 1913 - 2200)
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
            val now = SystemClock.elapsedRealtime()
            val streakStart = degradedStreakStartRealtime ?: now.also { degradedStreakStartRealtime = it }
            val elapsed = now - streakStart

            if (elapsed < BUFFERING_THRESHOLD_MS) {
                InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
                return
            }

            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Umbral de ${BUFFERING_THRESHOLD_MS}ms superado. Activando estado Cargando.")
            serviceScope.launch {
                val bufferingEvent = MusicUpdateEvent.StatusUpdate(
                    isPlaying = false,
                    deviceName = currentMem.playbackDeviceName,
                    deviceType = currentMem.playbackDeviceType,
                    isBuffering = true
                )
                if (MusicStateProvider.applyEvent(bufferingEvent)) {
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                }
            }
            return
        }
        degradedStreakStartRealtime = null

        /*
         * Creamos una nueva generación de forma atómica.
         */
        val myGeneration =
            generation.incrementAndGet()

        // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
        // Esto permite que el historial use la fuente de verdad del último estado aplicado.
        val previousLogical =
            lastLogicalSnapshot

        val previousReliable =
            lastAppliedSnapshot

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
        val lastProjectedPos = previousReliable?.projectedPositionMs() ?: 0L
        
        val isPlaying = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING
        val progressFactor = if (rawSnapshot.durationMs > 0) currentProjectedPos.toFloat() / rawSnapshot.durationMs.toFloat() else 0f

        // BLOQUE 3.2: Guarda de identidad primero, heurística de posición después
        val identityChanged = currentIdentity != newIdentity
        
        // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
        val isManualRewind = !isCatchUp && currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged

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
            InternalLogger.d(applicationContext, "[ART_TRACE] Sesión saliente antes de reemplazo: UUID=${session?.sessionUUID}, artworkKey=${session?.playbackContext?.artworkKey}")
            currentLogicalSession = newSession
            identityGenerationCounter++
            InternalLogger.d(applicationContext, "[ART_TRACE] Nueva generación de identidad: gen=$identityGenerationCounter, UUID=${newSession.sessionUUID}, Track=${rawSnapshot.title}")
            InternalLogger.d(applicationContext, "[FSM] Nueva Sesión Creada (UUID=${newSession.sessionUUID}): ${rawSnapshot.title}")
            
            // BUFFER DE NACIMIENTO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
            val myCoreKey = snapshot.coreKey
            val bitmap = memoryArtworkCache[myCoreKey]
            val uuid = newSession.sessionUUID
            if (bitmap != null) {
```

### PARTE 2 de 3 (Líneas 2201 - 2500)
```kotlin
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
        // Si es visible, dejamos que el Stage 2 maneje la persistencia final para evitar race conditions.
        if (!isWidgetPotentiallyVisible()) {
            serviceScope.launch {
                val currentInfo = musicDataStore.musicInfoFlow.first()
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val oldCanKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                val canKeepLyric = snapshot.isSessionActive &&
                    currentInfo.lyricsTrackKey.isNotBlank() &&
                    MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                        MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso6_canKeepLyricStage1: viejo=$oldCanKeepLyric, nuevo=$canKeepLyric, coincide=${oldCanKeepLyric == canKeepLyric}")
                
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

        val oldTrackChangedUI = previousLogical?.trackKey != snapshot.trackKey
        val trackChangedUI = 
            previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
        InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=$oldTrackChangedUI, nuevo=$trackChangedUI, coincide=${oldTrackChangedUI == trackChangedUI}, track=${snapshot.title}")

        // Identidad de negocio para decidir si hace falta re-buscar la letra (Conjunto Letras-3).
        // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
        // cambió por una corrección tardía de duración.
        val songChangedForLyrics = previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)

        val appChangedUI = 
            previousLogical?.packageName != snapshot.packageName

        val stateChangedUI = 
            previousLogical?.playbackState != snapshot.playbackState

        val artworkChangedUI =
            previousLogical?.artworkKey != snapshot.artworkKey

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

            // REGLA Artwork-3: Captura de generación para protección de identidad
            val genAlIniciarResolucion = identityGenerationCounter

            // REGLA Artwork-1: Resolución dirigida por incoherencia (evita ráfagas CPU)
            if (controller != null && metadata != null && artIncoherent) {
                
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
                InternalLogger.d(applicationContext, "[ART_TRACE] Resolución terminada: Exito=${resolvedArtwork != null}, gen_actual=$identityGenerationCounter, UUID_actual=${currentLogicalSession?.sessionUUID}, Track_actual=${currentLogicalSession?.identity?.title}")
            }

            // B. Icono de app (Optimizado)
            if (controller != null && metadata != null && 
                (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION)) {
                val (icon, tier) = resolveAppIcon(snapshot.packageName)
                
                // Solo actualizamos si el nuevo tier es mejor o igual al actual (o es un cambio de app)
                if (icon != null && (appChangedUI || tier > currentIconTier)) {
                    resolvedAppIconFinal = icon
                    resolvedIconKey = "${snapshot.packageName}_stable"
                    resolvedTierFinal = tier
                }
            }

            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (songChangedForLyrics) {
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
            } else if (trackChangedUI && currentLyrics != null) {
                // La canción de negocio es la misma (solo se afinó trackKey, ej. duración tardía).
                // Reutilizamos la letra ya cargada en vez de re-buscarla en red (Conjunto Letras-3).
                relaunchLyricsTicker("metadata_refined")
            } else {
                // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=${currentLyrics == null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
                if (currentLyrics == null) {
                    currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                    InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Resultado de reintento: exito=${currentLyrics != null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
                }

                val effectivePos = previousLogical?.projectedPositionMs() ?: 0L
                val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
                
                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${currentLyrics == null}")
                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true

                if (shouldResync && currentLyrics != null) {
                    relaunchLyricsTicker("state_sync")
                }
            }

            val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
```

### PARTE 3 de 3 (Líneas 2501 - 2654)
```kotlin
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
                                InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")
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
                    val canKeepLyric = snapshot.isSessionActive &&
                        currentInfo.lyricsTrackKey.isNotBlank() &&
                        MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                            MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                    
                    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                    val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""

                    val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)

                    // REGLA Artwork-3: Guardar imagen resuelta con verificación de identidad síncrona
                    val (finalArtworkKey, finalArtworkUri) = if (artIncoherent) {
                        val currentUUID = currentLogicalSession?.sessionUUID
                        val uri = if (resolvedArtwork != null && identityGenerationCounter == genAlIniciarResolucion && currentUUID != null) {
                            ArtworkStorageManager.saveHistoryArtwork(applicationContext, resolvedArtwork, currentUUID)
                        } else if (run {
                                val oldMatch = snapshot.trackKey == currentInfo.trackKey
                                val newMatch = MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                                    MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso2_artworkFallback: viejo=$oldMatch, nuevo=$newMatch, coincide=${oldMatch == newMatch}, track=${snapshot.title}")
                                newMatch
                            }) {
                            currentInfo.artworkUri
                        } else {
                            ""
                        }
                        snapshot.artworkKey to uri
                    } else {
                        currentInfo.artworkKey to currentInfo.artworkUri
                    }

                    InternalLogger.d(applicationContext, "[ART_TRACE] Decisión final de portada: artIncoherent=$artIncoherent, gen=$identityGenerationCounter, UUID=${currentLogicalSession?.sessionUUID}, valorElegido=$finalArtworkUri")

                    val finalMusicInfo = MusicInfo(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        packageName = snapshot.packageName,
                        album = snapshot.album ?: "",
                        trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                        artworkKey = finalArtworkKey,
                        artworkUri = finalArtworkUri,
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
                    InternalLogger.d(applicationContext, "[ART_TRACE] Persistencia confirmada: artworkUri_guardado=${musicDataStore.musicInfoFlow.first().artworkUri}")
                    
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

## Tarea 2 — Código de persistencia del historial (`art_<UUID>.webp`)

### [ArtworkStorageManager.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/ArtworkStorageManager.kt)
```kotlin
package arenliel.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Gestor especializado en la persistencia de recursos gráficos.
 * Garantiza la independencia de archivos entre la sesión activa y el historial.
 */
object ArtworkStorageManager {
    private const val TAG = "ArtworkStorage"

    /**
     * Guarda una portada para el historial de forma permanente y atómica.
     * @param identifier El sessionUUID de la sesión (v9.0).
     * @return Ruta absoluta del archivo guardado.
     */
    fun saveHistoryArtwork(context: Context, bitmap: Bitmap, identifier: String): String {
        val historyDir = File(context.filesDir, "history")
        if (!historyDir.exists()) historyDir.mkdirs()

        val fileName = "art_$identifier.webp"
        val finalFile = File(historyDir, fileName)
        val tempFile = File(historyDir, "$fileName.tmp")

        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            FileOutputStream(tempFile).use { out ->
                if (bitmap.compress(format, 80, out)) {
                    out.flush()
                    // Movimiento atómico para evitar corrupción
                    Files.move(
                        tempFile.toPath(), 
                        finalFile.toPath(), 
                        StandardCopyOption.REPLACE_EXISTING, 
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    Log.d(TAG, "[HISTORY_ART] Guardando imagen de historial en disco para: $identifier -> Path: ${finalFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persistiendo imagen de historial", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return finalFile.absolutePath
    }
}
```

---

## Tarea 3 — Confirmación puntual

### 1. Punto de captura de la variable "etiqueta"
Se captura al inicio de `processSnapshot` (Línea 1922):
```kotlin
        val session = currentLogicalSession
```
Esta variable local `session` se mantiene apuntando a la sesión activa *al momento de entrar a la función*. Si la canción cambia durante la ejecución, `currentLogicalSession` se actualiza, pero la variable local `session` sigue siendo la anterior y se usa para el log y cálculos en el Stage 2 (Línea 2517):
```kotlin
InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")
```

### 2. Disponibilidad del UUID del historial
El UUID que usa la función del historial (`session.sessionUUID`) está disponible desde el inicio de la función si ya existía una sesión. Sin embargo, para una **canción nueva**, el UUID definitivo se genera en la Línea 2181:
```kotlin
            val newSession = LogicalSession(...)
```
A partir de ese punto, `newSession.sessionUUID` es el identificador único que se usa para el "Buffer de Nacimiento" y el historial.

### 3. Razones técnicas para la separación de archivos
- **Transformación Visual:** El historial persiste una "píldora horizontal" (`ImageUtils.createHorizontalPill`), mientras que el widget utiliza dos archivos distintos: uno RAW de alta resolución y una "píldora vertical/redonda" optimizada para el layout del widget.
- **Inmutabilidad vs. Estabilidad de URI:** El historial requiere un archivo por sesión (`art_<UUID>.webp`) para que el scroll del historial no sufra parpadeos. El widget utiliza archivos con nombres fijos (`current_artwork.webp`) para que Glance no tenga que recibir una nueva URI de archivo en cada cambio de estado (solo lee el mismo archivo refrescado), minimizando el lag de Binder.
- **Gating de Pantalla:** La resolución del historial se intenta proactivamente tras 5s (`persistHistoryArtworkEagerly`), mientras que la del widget está sujeta al gating de pantalla apagada para ahorrar batería.
