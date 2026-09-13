# Auditoría — Ronda 14: Función Completa, Sin Ninguna Abreviación

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## HH1. Confirmación de ubicación actual

```
grep -n "lastAppliedSnapshot = snapshot" MusicNotificationListener.kt
```
**Resultado:**
`2590:                     lastAppliedSnapshot = snapshot`

---

## HH2. La función completa que contiene esa línea, de principio a fin

**Función:** `processSnapshot` (Líneas 1906 - 2606 de `MusicNotificationListener.kt`).

### PARTE 1 de 4 (Líneas 1906 - 2100)

```kotlin
1906:     private suspend fun processSnapshot(
1907:         controller: MediaController?,
1908:         metadata: MediaMetadata?,
1909:         rawSnapshot: MediaSnapshot,
1910:         reason: String
1911:     ) {
1912:         // v8.0: Bloqueo proactivo hasta que la rehidratación termine (BLOQUE A)
1913:         kotlinx.coroutines.withTimeoutOrNull(BOOT_GATE_TIMEOUT_MS) { bootGate.await() }
1914:             ?: InternalLogger.w(applicationContext, "[HIST_BOOT] BOOT_GATE_TIMEOUT: procesando paquete vivo ($reason) sin estado rehidratado")
1915: 
1916:         val session = currentLogicalSession
1917:         
1918:         val stateName = when(rawSnapshot.playbackState) {
1919:             PlaybackState.STATE_PLAYING -> "PLAYING"
1920:             PlaybackState.STATE_PAUSED -> "PAUSED"
1921:             else -> "OTHER(${rawSnapshot.playbackState})"
1922:         }
1923:         InternalLogger.d(applicationContext, "[DIAG_V5] [INTAKE] Recibido: Estado=$stateName, Track=${rawSnapshot.title}, Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}ms, Reason=$reason")
1924: 
1925:         // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---
1926: 
1927:         val currentMem = MusicStateProvider.current()
1928: 
1929:         // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
1930:         // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
1931:         // abortamos para proteger el estado coherente en RAM y Disco.
1932:         val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
1933:         val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
1934:         val isDegraded = rawSnapshot.durationMs <= 0L
1935: 
1936:         if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
1937:             val now = SystemClock.elapsedRealtime()
1938:             val streakStart = degradedStreakStartRealtime ?: now.also { degradedStreakStartRealtime = it }
1939:             val elapsed = now - streakStart
1940: 
1941:             if (elapsed < BUFFERING_THRESHOLD_MS) {
1942:                 InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
1943:                 return
1944:             }
1945: 
1946:             InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Umbral de ${BUFFERING_THRESHOLD_MS}ms superado. Activando estado Cargando.")
1947:             serviceScope.launch {
1948:                 val bufferingEvent = MusicUpdateEvent.StatusUpdate(
1949:                     isPlaying = false,
1950:                     deviceName = currentMem.playbackDeviceName,
1951:                     deviceType = currentMem.playbackDeviceType,
1952:                     isBuffering = true
1953:                 )
1954:                 if (MusicStateProvider.applyEvent(bufferingEvent)) {
1955:                     uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
1956:                 }
1957:             }
1958:             return
1959:         }
1960:         degradedStreakStartRealtime = null
1961: 
1962:         /*
1963:          * Creamos una nueva generación de forma atómica.
1964:          */
1965:         val myGeneration =
1966:             generation.incrementAndGet()
1967: 
1968:         // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
1969:         // Esto permite que el historial use la fuente de verdad del último estado aplicado.
1970:         val previousLogical =
1971:             lastLogicalSnapshot
1972: 
1973:         val previousReliable =
1974:             lastAppliedSnapshot
1975: 
1976:         val sessionChanged = currentLogicalSession?.identity != TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
1977:         
1978:         if (sessionChanged) {
1979:             // Limpieza de Memoria RAM (v5.2.3): Purga basada en CoreKey
1980:             val myCoreKey = "${sanitize(rawSnapshot.title)}|${sanitize(rawSnapshot.artist)}"
1981:             memoryArtworkCache.keys.retainAll(setOf(myCoreKey))
1982:         }
1983: 
1984:         val trackContentChanged = previousLogical?.artworkKey != rawSnapshot.artworkKey
1985: 
1986:         // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
1987:         // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
1988:         val isCatchUp = reason == "catch_up_render"
1989:         
1990:         // Detección de Incoherencia de Imagen: Si la portada en disco no coincide con la del snapshot, forzamos bypass
1991:         val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()
1992: 
1993:         // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
1994:         // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
1995:         if (!isCatchUp && !trackContentChanged && !artIncoherent && 
1996:             currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
1997:             currentMem.isSessionActive == rawSnapshot.isSessionActive) {
1998:             
1999:             // REGLA B.1 (v9.1): Actualización de Verdad (Posición) previa al Redibujado.
2000:             // Aseguramos que la marca de agua progrese aunque el refresco visual sea ignorado.
2001:             session?.let { s ->
2002:                 s.maxPositionMs = Math.max(s.maxPositionMs, rawSnapshot.projectedPositionMs())
2003:             }
2004: 
2005:             // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
2006:             lastObservedSnapshot = rawSnapshot
2007:             InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
2008:             return
2009:         }
2010: 
2011:         if (isCatchUp || artIncoherent) {
2012:             InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
2013:         }
2014: 
2015:         val appChanged = previousLogical?.packageName != rawSnapshot.packageName
2016:         
2017:         if (appChanged) {
2018:             currentIconTier = TIER_NONE
2019:             // REGLA: Limpieza de Iconos (Icon Fix) ante cambios de app
2020:             saveTextToFile("", APP_ICON_KEY_FILE)
2021:             savedAppIconKey = null
2022:             
2023:             // Hallazgo v3.4: Limpieza preventiva de RAM en transición
2024:             serviceScope.launch {
2025:                 MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(0L)) // Simulamos fin de sesión
2026:             }
2027:         }
2028: 
2029:         // REGLA DE PROMOCIÓN DE SESIÓN (Persistent Snapshot)
2030:         if (sessionChanged && previousLogical != null && rawSnapshot.packageName != previousLogical.packageName) {
2031:             val isNewSessionWeak = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING
2032:             if (isNewSessionWeak) {
2033:                 InternalLogger.d(applicationContext, "[DIAGNOSTIC] IGNORED: Ignorando sesión débil de ${rawSnapshot.packageName}")
2034:                 return
2035:             }
2036:         }
2037: 
2038:         // FILTRO DE IDENTIDAD (Allow-list)
2039:         if (!isAppAllowed(rawSnapshot.packageName)) return
2040: 
2041:         val currentBlacklist = musicDataStore.musicInfoFlow.first().blacklist
2042:         if (currentBlacklist.contains(rawSnapshot.packageName)) return
2043: 
2044:         val isSameSession = session?.sessionIdentity == rawSnapshot.sessionIdentity
2045:         
2046:         val firstObservedAt = if (isSameSession && session != null) {
2047:             session.startedAtRealtime // Usar el inicio real de la sesión (v9.0)
2048:         } else {
2049:             rawSnapshot.recordedAt
2050:         }
2051: 
2052:         // --- PURGA DE TRANSICIÓN Y PROTECCIÓN DE HERENCIA (v9.0: Sede única en session) ---
2053:         // Bloqueamos la herencia de marcas de agua (maxPositionMs) y assets si el título cambia.
2054:         // Si el título entrante es nulo o distinto, el snapshot nace desde cero.
2055:         val canInheritAssets = isSameSession && rawSnapshot.title == session?.identity?.title && rawSnapshot.title.isNotBlank()
2056: 
2057:         val snapshot = rawSnapshot.copy(
2058:             firstObservedAt = firstObservedAt,
2059:             artworkSource = if (canInheritAssets) (session?.birthSnapshot?.artworkSource ?: rawSnapshot.artworkSource) else rawSnapshot.artworkSource
2060:         )
2061: 
2062:         // --- MOTOR DE TRANSICIÓN VECTORIAL (v9.0) ---
2063:         val currentIdentity = session?.identity
2064:         val newIdentity = TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
2065:         
2066:         val currentProjectedPos = rawSnapshot.projectedPositionMs()
2067:         val lastProjectedPos = previousReliable?.projectedPositionMs() ?: 0L
2068:         
2069:         val isPlaying = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING
2070:         val progressFactor = if (rawSnapshot.durationMs > 0) currentProjectedPos.toFloat() / rawSnapshot.durationMs.toFloat() else 0f
2071: 
2072:         // BLOQUE 3.2: Guarda de identidad primero, heurística de posición después
2073:         val identityChanged = currentIdentity != newIdentity
2074:         
2075:         // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
2076:         val isManualRewind = currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged
2077: 
2078:         // 2. Evaluamos si es un resurgimiento del sistema sin cambio real de tiempo (Catch-up)
2079:         val isCatchUpRender = if (identityChanged) false else Math.abs(currentProjectedPos - lastProjectedPos) < 1500L
2080: 
2081:         // 3. Detectamos el loop perfecto
2082:         val isRealLoop = isPlaying && 
2083:                          currentProjectedPos < 2000L && 
2084:                          progressFactor > 0.95f && 
2085:                          !identityChanged
2086: 
2087:         // DECISIÓN ESTRUCTURAL: Solo rompemos la sesión si cambió la canción, loop o reinicio manual.
2088:         // v9.0: isManualRewind ignorado si es provisional para evitar skips al boot (Bloque D.3)
2089:         val sessionEnded = identityChanged || isRealLoop || (isManualRewind && session?.isProvisional == false)
2090:         
2091:         // REGLA D.2: Monotonía de la marca de agua. Sede única: LogicalSession.
2092:         session?.let { s ->
2093:             if (!identityChanged) {
2094:                 s.maxPositionMs = max(s.maxPositionMs, currentProjectedPos)
2095:             }
2096:         }
2097: 
2098:         // INSTRUMENTACIÓN BLOQUE 3.3
2099:         InternalLogger.d(applicationContext, "[FSM_GUARD] identityChanged=$identityChanged, " +
2100:             "projectedPos=${currentProjectedPos}ms, rawPos=${rawSnapshot.positionMs}ms, maxPos=${session?.maxPositionMs ?: 0}ms, " +
```

### PARTE 2 de 4 (Líneas 2101 - 2300)

```kotlin
2101:             "delta=${currentProjectedPos - lastProjectedPos}ms, taken=${if (sessionEnded) "ENDED" else if (isCatchUpRender) "CATCHUP" else "FUSION"}")
2102: 
2103:         // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
2104:         if (session != null && session.isProvisional) {
2105:             if (!identityChanged) {
2106:                 // ESCENARIO A: Resurrección tras Doze confirmada. 
2107:                 // Adoptamos la sesión rehidratada y limpiamos el flag de provisional.
2108:                 session.isProvisional = false
2109:                 InternalLogger.d(applicationContext, "[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=${session.sessionUUID}")
2110:             } else {
2111:                 // ESCENARIO B: Cierre Real. La canción cambió mientras el widget dormía.
2112:                 // Archivamos la sesión vieja usando el watermark persistido.
2113:                 historyChannel.trySend(HistoryEvent.CommitSession(
2114:                     sessionUUID = session.sessionUUID,
2115:                     birthSnapshot = session.birthSnapshot,
2116:                     finalSnapshot = session.liveSnapshot,
2117:                     maxPositionMs = session.maxPositionMs,
2118:                     startedAtRealtime = session.startedAtRealtime
2119:                 ))
2120:                 InternalLogger.d(applicationContext, "[HIST_BOOT] CIERRE RETROACTIVO: uuid=${session.sessionUUID}")
2121:                 currentLogicalSession = null
2122:                 // Continuamos al flujo normal de creación de sesión nueva
2123:             }
2124:         }
2125: 
2126:         if (sessionEnded && !isCatchUpRender) {
2127:             // AQUÍ EJECUTAMOS EL COMPROMISO ATÓMICO AL HISTORIAL (v6.5)
2128:             currentLogicalSession?.let { session ->
2129:                 // Verificación de seguridad v7.0: Una sesión provisional nunca emite aquí
2130:                 if (session.isProvisional) return@let
2131: 
2132:                 val commitEvent = HistoryEvent.CommitSession(
2133:                     sessionUUID = session.sessionUUID,
2134:                     birthSnapshot = session.birthSnapshot,
2135:                     finalSnapshot = session.liveSnapshot,
2136:                     maxPositionMs = max(session.maxPositionMs, session.liveSnapshot.projectedPositionMs()),
2137:                     startedAtRealtime = session.startedAtRealtime
2138:                 )
2139:                 
2140:                 // BLOQUE 7.2: Detector de Atribución (Anti-Envenenamiento)
2141:                 if (commitEvent.finalSnapshot.title != session.birthSnapshot.title ||
2142:                     commitEvent.finalSnapshot.artist != session.birthSnapshot.artist) {
2143:                     InternalLogger.e(applicationContext, "[HIST_POISON] Identidad divergente en commit. " +
2144:                         "birth='${session.birthSnapshot.title} / ${session.birthSnapshot.artist}' " +
2145:                         "capsule='${commitEvent.finalSnapshot.title} / ${commitEvent.finalSnapshot.artist}' " +
2146:                         "uuid=${session.sessionUUID}")
2147:                 }
2148: 
2149:                 val res = historyChannel.trySend(commitEvent)
2150:                 
2151:                 totalEventsCounter.incrementAndGet()
2152:                 if (res.isSuccess) {
2153:                     successCounter.incrementAndGet()
2154:                     pendingEventsCount.incrementAndGet()
2155:                 } else {
2156:                     failureCounter.incrementAndGet()
2157:                 }
2158:                 
2159:                 InternalLogger.d(applicationContext, "[HIST_CHANNEL] EVENT_SENT: Success=${res.isSuccess}, Failure=${res.isFailure}, Closed=${res.isClosed}, Track=${session.liveSnapshot.title}")
2160:                 if (res.isFailure) {
2161:                     InternalLogger.e(applicationContext, "[HIST_CHANNEL] FAIL_CAUSE: ${res.exceptionOrNull()?.message}")
2162:                 }
2163:             }
2164:             
2165:             purgeZombieControllers()
2166:             
2167:             // GENERAMOS LA NUEVA SESIÓN (Su cronómetro arranca en el constructor)
2168:             val newContext = PlaybackContext(
2169:                 durationMs = rawSnapshot.durationMs,
2170:                 album = rawSnapshot.album,
2171:                 artworkKey = rawSnapshot.artworkKey
2172:             )
2173:             val newSession = LogicalSession(
2174:                 identity = newIdentity,
2175:                 birthSnapshot = rawSnapshot,
2176:                 liveSnapshot = rawSnapshot,
2177:                 frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
2178:                 maxPositionMs = rawSnapshot.positionMs, // v9.0: Reset marca de agua
2179:                 playbackContext = newContext,
2180:                 context = this@MusicNotificationListener
2181:             )
2182:             currentLogicalSession = newSession
2183:             InternalLogger.d(applicationContext, "[FSM] Nueva Sesión Creada (UUID=${newSession.sessionUUID}): ${rawSnapshot.title}")
2184:             
2185:             // BUFFER DE NACIMIENTO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
2186:             val myCoreKey = snapshot.coreKey
2187:             val bitmap = memoryArtworkCache[myCoreKey]
2188:             val uuid = newSession.sessionUUID
2189:             if (bitmap != null) {
2190:                 serviceScope.launch(Dispatchers.IO) {
2191:                     val density = applicationContext.resources.displayMetrics.density
2192:                     val w = (80 * density).toInt()
2193:                     val h = (40 * density).toInt()
2194:                     val historyPill = ImageUtils.createHorizontalPill(bitmap, w, h)
2195:                     ArtworkStorageManager.saveHistoryArtwork(applicationContext, historyPill, uuid)
2196:                     historyPill.recycle()
2197:                     InternalLogger.d(applicationContext, "[FSM] Portada de nacimiento persistida (UUID=$uuid)")
2198:                 }
2199:             }
2200:         } else {
2201:             // FUSIÓN DE ESTADO (v6.5): Solo actualizamos liveSnapshot y contexto
2202:             val updatedContext = PlaybackContext(
2203:                 durationMs = rawSnapshot.durationMs,
2204:                 album = rawSnapshot.album,
2205:                 artworkKey = rawSnapshot.artworkKey
2206:             )
2207:             session?.let { s ->
2208:                 s.liveSnapshot = rawSnapshot
2209:                 s.playbackContext = updatedContext
2210:             }
2211:         }
2212: 
2213:         // ACTUALIZACIÓN DEL DIARIO LÓGICO
2214:         lastLogicalSnapshot = rawSnapshot
2215:         lastObservedPositionMs = currentProjectedPos
2216: 
2217:         // ACTIVE WATCHER (v4.3.1): Cronómetro proactivo de 5s con LATE-READ
2218:         if (snapshot.playbackState == PlaybackState.STATE_PLAYING && (trackContentChanged || eagerCacheJob == null)) {
2219:             val uuid = session?.sessionUUID ?: ""
2220:             eagerCacheJob?.cancel()
2221:             eagerCacheJob = serviceScope.launch {
2222:                 delay(5000L)
2223:                 // Obtenemos el estado refinado (con portada cargada) tras la espera
2224:                 val refinedSnapshot = lastLogicalSnapshot ?: return@launch
2225:                 persistHistoryArtworkEagerly(refinedSnapshot, uuid)
2226:             }
2227:         } else if (snapshot.playbackState != PlaybackState.STATE_PLAYING) {
2228:             // Cancelación inmediata en pausa/stop para ahorro de recursos
2229:             eagerCacheJob?.cancel()
2230:             eagerCacheJob = null
2231:         }
2232: 
2233:         val isSessionEnded = sessionEnded
2234:         val isTrackContentChanged = trackContentChanged
2235: 
2236:         // FAST-TRACK SSOT (v4.0 - Relevo Atómico de RAM)
2237:         serviceScope.launch {
2238:             mutationMutex.withLock {
2239:                 val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
2240:                 val currentInfo = musicDataStore.musicInfoFlow.first()
2241:                 
2242:                 // Hallazgo v3.7: Inmunidad de Salida.
2243:                 val (plays, skip, freq) = when {
2244:                     isSessionEnded -> Triple(0, 0, false)
2245:                     !isPlaying -> Triple(currentMem.playsToday, currentMem.skipStreak, currentMem.isFrequentArtist)
2246:                     else -> musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
2247:                 }
2248:                 
2249:                 val memInfo = MusicInfo(
2250:                     title = snapshot.title,
2251:                     artist = snapshot.artist,
2252:                     packageName = snapshot.packageName,
2253:                     album = snapshot.album ?: "",
2254:                     trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2255:                     artworkKey = currentInfo.artworkKey,
2256:                     artworkUri = snapshot.artworkUri ?: currentInfo.artworkUri,
2257:                     appIconKey = currentInfo.appIconKey,
2258:                     isPlaying = isPlaying,
2259:                     isSessionActive = snapshot.isSessionActive,
2260:                     currentLyric = if (!isSessionEnded) currentMem.currentLyric else "",
2261:                     lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
2262:                     playbackDeviceName = snapshot.playbackDeviceName,
2263:                     playbackDeviceType = snapshot.playbackDeviceType,
2264:                     durationMs = snapshot.durationMs,
2265:                     history = currentInfo.history,
2266:                     playsToday = plays,
2267:                     skipStreak = skip,
2268:                     isFrequentArtist = freq,
2269:                     lastUpdateEpoch = currentMem.lastUpdateEpoch,
2270:                     observedAtRealtime = currentMem.observedAtRealtime,
2271:                     sessionUUID = session?.sessionUUID ?: "",
2272:                     isPendingCommit = false,
2273:                     lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
2274:                 )
2275:                 
2276:                 val event = if (isSessionEnded) {
2277:                     MusicUpdateEvent.NewSession(memInfo)
2278:                 } else if (isTrackContentChanged) {
2279:                     MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying)
2280:                 } else {
2281:                     MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType, isBuffering = false)
2282:                 }
2283: 
2284:                 if (MusicStateProvider.applyEvent(event)) {
2285:                     uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
2286:                 }
2287:                 
2288:                 if (isSessionEnded) {
2289:                     relaunchLyricsTicker("identity_change")
2290:                 } else {
2291:                     val stateChangedUI = currentMem.isPlaying != isPlaying
2292:                     if (stateChangedUI) relaunchLyricsTicker("state_sync")
2293:                 }
2294:             }
2295:         }
2296:         // Solo guardamos de forma anticipada si el widget NO es visible (gating activo).
2297:         // Si es visible, dejamos que el Stage 2 maneje la persistencia final para evitar race conditions.
2298:         if (!isWidgetPotentiallyVisible()) {
2299:             serviceScope.launch {
2300:                 val currentInfo = musicDataStore.musicInfoFlow.first()
```

### PARTE 3 de 4 (Líneas 2301 - 2500)

```kotlin
2301:                 val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
2302:                 val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
2303:                 
2304:                 val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2305:                 val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
2306: 
2307:                 val logicalMusicInfo = MusicInfo(
2308:                     title = snapshot.title,
2309:                     artist = snapshot.artist,
2310:                     packageName = snapshot.packageName,
2311:                     trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2312:                     artworkKey = snapshot.artworkKey,
2313:                     artworkUri = snapshot.artworkUri ?: "",
2314:                     appIconKey = savedAppIconKey ?: "",
2315:                     isPlaying = isPlaying,
2316:                     isSessionActive = snapshot.isSessionActive,
2317:                     currentLyric = finalLyric,
2318:                     lyricsTrackKey = finalLyricKey,
2319:                     playbackDeviceName = snapshot.playbackDeviceName,
2320:                     playbackDeviceType = snapshot.playbackDeviceType,
2321:                     durationMs = snapshot.durationMs,
2322:                     sessionUUID = session?.sessionUUID ?: "",
2323:                     isPendingCommit = false, // Reconciliación exitosa (v6.7)
2324:                     lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
2325:                 )
2326:                 val changed = musicDataStore.saveMusicInfo(logicalMusicInfo, forceUpdate = false)
2327:                 if (changed) {
2328:                     lastCommittedInfo = logicalMusicInfo
2329:                 }
2330:             }
2331:         }
2332: 
2333:         // --- STAGE 2: PRESENTACIÓN (BLOQUEO POR COMPUERTA) ---
2334: 
2335:         if (!isWidgetPotentiallyVisible()) {
2336:             // NOTE (Conjunto B.2, decision recorded — do not "fix" without checking with the
2337:             // project owner first): artwork resolution is intentionally skipped entirely while the
2338:             // screen is off, including cases where a corrected artwork would otherwise reach the
2339:             // history. This is a deliberate battery-saving tradeoff, not an oversight.
2340:             Log.d(TAG, "[GATING] Presentación suprimida (Pantalla apagada/bloqueada).")
2341:             InternalLogger.log(applicationContext, "STAGE 2: Suprimido (Pantalla bloqueada). Track=${snapshot.title}")
2342:             isPresentationDirty = true
2343:             pendingSnapshot = snapshot
2344:             
2345:             // Destrucción de Ticker de Letras para ahorro de batería
2346:             lyricsUpdateJob?.cancel()
2347:             
2348:             // Abortamos Stage 2 para evitar I/O y CPU innecesarios
2349:             lastObservedSnapshot = snapshot
2350:             return
2351:         }
2352: 
2353:         // Si el widget es visible, reseteamos flags de gating
2354:         isPresentationDirty = false
2355:         pendingSnapshot = null
2356: 
2357:         val previousApplied = 
2358:             lastAppliedSnapshot
2359: 
2360:         val trackChangedUI = 
2361:             previousApplied?.trackKey != snapshot.trackKey
2362: 
2363:         // Identidad de negocio para decidir si hace falta re-buscar la letra (Conjunto Letras-3).
2364:         // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
2365:         // cambió por una corrección tardía de duración.
2366:         val songChangedForLyrics = previousApplied == null ||
2367:             MusicDataStore.computeSessionIdentity(previousApplied.packageName, previousApplied.title, previousApplied.artist) !=
2368:                 MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
2369: 
2370:         val appChangedUI = 
2371:             previousApplied?.packageName != snapshot.packageName
2372: 
2373:         val stateChangedUI = 
2374:             previousApplied?.playbackState != snapshot.playbackState
2375: 
2376:         val artworkChangedUI =
2377:             previousApplied?.artworkKey != snapshot.artworkKey
2378: 
2379:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")
2380: 
2381:         try {
2382: 
2383:             // 1. Resolución de recursos visuales (Fase Cancelable).
2384:             var resolvedArtwork: Bitmap? = null
2385:             var resolvedAppIconFinal: Bitmap? = null
2386:             var resolvedIconKey: String? = null
2387:             var resolvedTierFinal: Int = TIER_NONE
2388: 
2389:             // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
2390:             // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
2391:             var artworkTimedOut = false
2392: 
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
2395:                 
2396:                 // A. Portada (v6.3 Pipeline Unificado)
2397:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2398:                     resolveArtworkDeduplicated(
2399:                         snapshot = snapshot,
2400:                         controller = controller,
2401:                         metadata = metadata,
2402:                         generation = myGeneration
2403:                     )
2404:                 } ?: run {
2405:                     artworkTimedOut = true
2406:                     null
2407:                 }
2408: 
2409:                 // B. Icono de app (Optimizado)
2410:                 if (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION) {
2411:                     val (icon, tier) = resolveAppIcon(snapshot.packageName)
2412:                     
2413:                     // Solo actualizamos si el nuevo tier es mejor o igual al actual (o es un cambio de app)
2414:                     if (icon != null && (appChangedUI || tier > currentIconTier)) {
2415:                         resolvedAppIconFinal = icon
2416:                         resolvedIconKey = "${snapshot.packageName}_stable"
2417:                         resolvedTierFinal = tier
2418:                     }
2419:                 }
2420:             }
2421: 
2422:             // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
2423:             if (songChangedForLyrics) {
2424:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
2425:                 lyricsUpdateJob?.cancel()
2426:                 lyricsFetchJob?.cancel()
2427:                 currentLyrics = null
2428:                 
2429:                 lyricsFetchJob = serviceScope.launch {
2430:                     // PUNTO B: Debounce para evitar spam de API
2431:                     delay(500L)
2432:                     
2433:                     val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
2434:                     if (result != null && isActive) {
2435:                         currentLyrics = result
2436:                         relaunchLyricsTicker("identity_change")
2437:                     } else if (isActive) {
2438:                         // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
2439:                         InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
2440:                         updateLyricInWidget(snapshot.trackKey, "")
2441:                     }
2442:                 }
2443:             } else if (trackChangedUI && currentLyrics != null) {
2444:                 // La canción de negocio es la misma (solo se afinó trackKey, ej. duración tardía).
2445:                 // Reutilizamos la letra ya cargada en vez de re-buscarla en red (Conjunto Letras-3).
2446:                 relaunchLyricsTicker("metadata_refined")
2447:             } else {
2448:                 // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
2449:                 if (currentLyrics == null) {
2450:                     currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
2451:                 }
2452: 
2453:                 val effectivePos = previousApplied?.projectedPositionMs() ?: 0L
2454:                 val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
2455:                 
2456:                 // Hard-Sync: Solo si el desvío es mayor a 1s o cambió el estado
2457:                 val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true
2458: 
2459:                 if (shouldResync && currentLyrics != null) {
2460:                     relaunchLyricsTicker("state_sync")
2461:                 }
2462:             }
2463: 
2464:             val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
2465:             if (myGeneration != generation.get() && !isStillRelevant) {
2466:                 Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
2467:                 return
2468:             }
2469: 
2470:             kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
2471:                 commitMutex.withLock {
2472: 
2473:                     val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
2474:                     if (myGeneration != generation.get() && !isStillRelevantInLock) {
2475:                         Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
2476:                         return@withLock
2477:                     }
2478: 
2479:                     if (controller != null && metadata != null && 
2480:                         (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
2481:                         
2482:                         if (resolvedArtwork != null) {
2483:                             // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
2484:                             // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
2485:                             // SEGURIDAD IPC (v4.5): Escalado de cortesía para el bus Binder.
2486:                             val transportBitmap = scaleForTransport(resolvedArtwork)
2487:                             val cacheKey = "${rawSnapshot.artworkKey}_raw"
2488:                             MusicWidget.bitmapCache.put(cacheKey, transportBitmap)
2489: 
2490:                             // Paso 3.2: CACHING DE TRANSFORMACIÓN
2491:                             if (savedArtworkKey != snapshot.artworkKey) {
2492:                                 // 1. Guardar versión RAW
2493:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2494:                                 
2495:                                 // 2. Guardar versión WIDGET (Píldora)
2496:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
2497:                                 
2498:                                 saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
2499:                                 savedArtworkKey = snapshot.artworkKey
2500: 
```

### PARTE 4 de 4 (Líneas 2501 - 2606)

```kotlin
2501:                                 // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
2502:                                 // Inyectamos el bitmap en el snapshot lógico para que la próxima 
2503:                                 // transición de historial lo lleve ya resuelto.
2504:                                 lastLogicalSnapshot = lastLogicalSnapshot?.copy(
2505:                                     artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
2506:                                 )
2507:                             }
2508:                         } else if (trackChangedUI || artworkChangedUI) {
2509:                             // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
2510:                             val placeholder = getPlaceholderBitmap()
2511:                             saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2512:                             saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
2513:                             saveTextToFile("", ALBUM_ART_KEY_FILE)
2514:                             savedArtworkKey = null
2515:                         }
2516: 
2517:                         if (resolvedAppIconFinal != null && resolvedIconKey != null) {
2518:                             saveBitmapToFile(resolvedAppIconFinal, APP_ICON_FILE)
2519:                             saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
2520:                             savedAppIconKey = resolvedIconKey
2521:                             currentIconTier = resolvedTierFinal
2522:                         } else if (appChangedUI) {
2523:                             // FIX: Solo borramos la llave si la APP cambió y no tenemos nuevo icono.
2524:                             // Esto evita la alternancia visual (flicker) al cambiar de track en la misma app.
2525:                             saveTextToFile("", APP_ICON_KEY_FILE)
2526:                             savedAppIconKey = null
2527:                             currentIconTier = TIER_NONE
2528:                         }
2529:                     }
2530: 
2531:                     val currentInfo = musicDataStore.musicInfoFlow.first()
2532:                     val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
2533:                     val canKeepLyric = snapshot.isSessionActive &&
2534:                         currentInfo.lyricsTrackKey.isNotBlank() &&
2535:                         MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
2536:                             MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
2537:                     
2538:                     val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2539:                     val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
2540: 
2541:                     val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
2542: 
2543:                     val finalMusicInfo = MusicInfo(
2544:                         title = snapshot.title,
2545:                         artist = snapshot.artist,
2546:                         packageName = snapshot.packageName,
2547:                         album = snapshot.album ?: "",
2548:                         trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2549:                         artworkKey = snapshot.artworkKey,
2550:                         artworkUri = snapshot.artworkUri ?: "",
2551:                         appIconKey = savedAppIconKey ?: "",
2552:                         isPlaying = isPlaying,
2553:                         isSessionActive = snapshot.isSessionActive,
2554:                         currentLyric = finalLyric,
2555:                         lyricsTrackKey = finalLyricKey,
2556:                         playbackDeviceName = snapshot.playbackDeviceName,
2557:                         playbackDeviceType = snapshot.playbackDeviceType,
2558:                         durationMs = snapshot.durationMs,
2559:                         history = currentInfo.history,
2560:                         playsToday = playsToday,
2561:                         skipStreak = skipStreak,
2562:                         isFrequentArtist = isFrequent,
2563:                         sessionUUID = session?.sessionUUID ?: "",
2564:                         isPendingCommit = false,
2565:                         lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
2566:                     )
2567: 
2568:                     // 1. Sincronía Atómica: Disco -> RAM -> UI
2569:                     val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
2570:                     
2571:                     if (artworkTimedOut) {
2572:                         Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
2573:                     }
2574:                     
2575:                     // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
2576:                     // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
2577:                     val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo))
2578: 
2579:                     // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
2580:                     // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
2581:                     
2582:                     if (changedDisco || isSessionEnded || changedRAM) {
2583:                         if (isSessionEnded) {
2584:                             uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
2585:                         } else {
2586:                             uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
2587:                         }
2588:                     }
2589:                     
2590:                     lastAppliedSnapshot = snapshot
2591:                     lastObservedSnapshot = snapshot
2592:                     lastCommittedInfo = MusicStateProvider.current()
2593:                 }
2594:             }
2595: 
2596:         } catch (e: CancellationException) {
2597:             Log.d(TAG, "[DIAGNOSTIC] CANCELLED: #$myGeneration aborted during resolution")
2598:             throw e
2599:         } catch (e: Exception) {
2600:             Log.e(TAG, "Error en pipeline atatomic #$myGeneration", e)
2601:         } finally {
2602:             if (inFlightSnapshot?.contentKey == snapshot.contentKey) {
2603:                 inFlightSnapshot = null
2604:             }
2605:         }
2606:     }
```

---

## HH3. Verificación cruzada por conteo de llaves

La línea `lastAppliedSnapshot = snapshot` (2590) está envuelta por los siguientes niveles estructurales (de más interno a más externo):

1.  **Nivel 1 (Funció Lambda del Lock):** `commitMutex.withLock {` (Línea 2471).
2.  **Nivel 2 (Funció Lambda de Corrutina):** `kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {` (Línea 2470).
3.  **Nivel 3 (Bloque de Excepción):** `try {` (Línea 2381).
4.  **Nivel 4 (Función Principal):** `private suspend fun processSnapshot(...) {` (Línea 1906).

**Conteo de niveles de `if`/`when`:**
La línea 2590 tiene **0 niveles** de `if` o `when` que la envuelvan directamente. Se encuentra inmediatamente después del cierre del bloque `if (changedDisco || isSessionEnded || changedRAM)` (Línea 2582), pero fuera de él.

**Confirmación de ramas hermanas:**
Dado que no está dentro de un `if`/`when`, no tiene ramas hermanas en su nivel actual de anidamiento (dentro del lock). Se ejecuta incondicionalmente una vez que el flujo entra en el lock de la STAGE 2.
