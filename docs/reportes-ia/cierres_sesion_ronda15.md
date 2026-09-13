# Auditoría — Ronda 15: Lógica Completa de `sessionEnded` / `isManualRewind` / `isCatchUpRender`

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## II1. Confirmación de ubicación actual

Resultados de `grep -n` en `MusicNotificationListener.kt`:
- `isManualRewind`: Línea **2076**
- `isCatchUpRender`: Línea **2079**
- `val sessionEnded`: Línea **2089**

---

## II2. Bloque completo, de principio a fin, sin abreviar

Ubicado en `MusicNotificationListener.kt` (Líneas 2089 - 2211).

### PARTE 1 de 1

```kotlin
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
```

---

## II3. ¿Es accesible la variable `reason` en este punto exacto?

**SÍ.** La variable `reason` es accesible como parámetro de la función `processSnapshot` (Línea 1910) que envuelve todo este bloque:

```kotlin
1906:     private suspend fun processSnapshot(
...
1910:         reason: String
1911:     ) {
```

---

## II4. Todos los usos existentes de la cadena `"catch_up_render"`

Resultados de `grep -n` en `MusicNotificationListener.kt`:

### 1. Desbloqueo de Pantalla (Línea 422)
```kotlin
417:         // Sincronización de recuperación:
418:         // Forzamos al proceso a descargar recursos que se omitieron durante el bloqueo.
419:         serviceScope.launch {
420:             refreshBestSession(reason = "catch_up_render")
```

### 2. Filtro de redundancia (Línea 1557)
```kotlin
1554:         if (
1555:             reason != "catch_up_render" &&
1556:             snapshot.contentKey ==
1557:             lastObservedSnapshot
1558:                 ?.contentKey
```

### 3. Filtro de redundancia (Línea 1567)
```kotlin
1564:         if (
1565:             reason != "catch_up_render" &&
1566:             snapshot.contentKey ==
1567:             inFlightSnapshot
1568:                 ?.contentKey
```

### 4. Filtro de redundancia (Línea 1577)
```kotlin
1574:         if (
1575:             reason != "catch_up_render" &&
1576:             snapshot.contentKey ==
1577:             lastAppliedSnapshot
1578:                 ?.contentKey
```

### 5. Definición de `isCatchUp` en `processSnapshot` (Línea 1988)
```kotlin
1986:         // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
1987:         // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
1988:         val isCatchUp = reason == "catch_up_render"
```
