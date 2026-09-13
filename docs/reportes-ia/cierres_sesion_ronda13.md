# Auditoría — Ronda 13: Estructura Real de la Decisión FSM y Contexto de `lastAppliedSnapshot`

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## GG1. Código completo del bloque de decisión real

Ubicado en `MusicNotificationListener.kt`.

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

## GG2. Contexto completo alrededor de la línea 2590

```kotlin
2565:                     val finalMusicInfo = MusicInfo(
2566:                         title = snapshot.title,
2567:                         artist = snapshot.artist,
2568:                         packageName = snapshot.packageName,
2569:                         album = snapshot.album ?: "",
2570:                         trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2571:                         artworkKey = snapshot.artworkKey,
2572:                         artworkUri = snapshot.artworkUri ?: "",
2573:                         appIconKey = savedAppIconKey ?: "",
2574:                         isPlaying = isPlaying,
2575:                         isSessionActive = snapshot.isSessionActive,
2576:                         currentLyric = finalLyric,
2577:                         lyricsTrackKey = finalLyricKey,
2578:                         playbackDeviceName = snapshot.playbackDeviceName,
2579:                         playbackDeviceType = snapshot.playbackDeviceType,
2580:                         durationMs = snapshot.durationMs,
2581:                         history = currentInfo.history,
2582:                         playsToday = playsToday,
2583:                         skipStreak = skipStreak,
2584:                         isFrequentArtist = isFrequent,
2585:                         sessionUUID = session?.sessionUUID ?: "",
2586:                         isPendingCommit = false,
2587:                         lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
2588:                     )
2589: 
2590:                     // 1. Sincronía Atómica: Disco -> RAM -> UI
2591:                     val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
2592:                     
2593:                     if (artworkTimedOut) {
2594:                         Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
2595:                     }
2596:                     
2597:                     // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
2598:                     // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
2599:                     val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo))
2600: 
2601:                     // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
2602:                     // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
2603:                     
2604:                     if (changedDisco || isSessionEnded || changedRAM) {
2605:                         if (isSessionEnded) {
2606:                             uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
2607:                         } else {
2608:                             uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
2609:                         }
2610:                     }
2611:                     
2612:                     lastAppliedSnapshot = snapshot
2613:                     lastObservedSnapshot = snapshot
2614:                     lastCommittedInfo = MusicStateProvider.current()
2615:                 }
2616:             }
```

**Condición envolvente:** La asignación está dentro de un bloque `commitMutex.withLock` (línea 2432), el cual está dentro de un `kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable)` (línea 2431), y este a su vez está precedido por un filtro de visibilidad (línea 2352):
```kotlin
2352:         if (!isWidgetPotentiallyVisible()) {
...
2367:             return
2368:         }
```

---

## GG3. Re-confirmación completa de todos los puntos de asignación

Resultados de `grep -n "lastAppliedSnapshot ="` en el proyecto completo:

```
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:698:                    lastAppliedSnapshot = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:1144:                lastAppliedSnapshot = recoveredSnapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2612:                    lastAppliedSnapshot = snapshot
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3230:        lastAppliedSnapshot = null
```
