# Auditoría — Ronda 22: Orden de Declaración de `previousLogical` en Relación a la Etapa 1

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## PP1. Ubicación actual de `previousLogical`

Ubicada en `MusicNotificationListener.kt` (Línea 1970).

```
1970:         val previousLogical =
1971:             lastLogicalSnapshot
```

---

## PP2. Contexto completo desde la declaración de `previousLogical` hasta el bloque de la Etapa 1 (líneas ~2211-2215)

Ubicado en `MusicNotificationListener.kt`.

### PARTE 1 de 3 (Líneas 1970 - 2050)

```kotlin
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
```

### PARTE 2 de 3 (Líneas 2051 - 2130)

```kotlin
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
2076:         val isManualRewind = !isCatchUp && currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged
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
```

### PARTE 3 de 3 (Líneas 2131 - 2220)

```kotlin
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
2213:         // ACTUALIZACIÓN DEL DIARIO LÓGICO (Cierres-3)
2214:         lastLogicalSnapshot = rawSnapshot
2215:         lastAppliedSnapshot = snapshot
2216:         lastObservedPositionMs = currentProjectedPos
```

---

## PP3. ¿Se reasigna `previousLogical` (la variable local, no el campo de clase) en algún punto entre su declaración y la línea 2358?

**NO.** No hay ninguna reasignación de la variable local `previousLogical` entre su declaración en la línea 1970 y el final de la Etapa 1. El `grep` en todo el archivo solo muestra la declaración inicial:

```
1970:         val previousLogical =
```

**Confirmación:** La variable local `previousLogical` permanece inmutable con el valor capturado de `lastLogicalSnapshot` en el momento de entrar a la función.
