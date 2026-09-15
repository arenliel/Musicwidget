# Auditoría — Ronda 6 (Portadas): Rastreo Completo de `resolvedArtwork` Entre el Paso C y el Paso B

**Confirmación de Git Log:**
```
e54d9d0 (HEAD -> master) Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
998c2ef Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
```

---

## ZZ1. Código completo, sin abreviar, desde la declaración de `resolvedArtwork` hasta la construcción de `finalMusicInfo`

Ubicado en `MusicNotificationListener.kt` (Líneas 2385 - 2562).

### PARTE 1 de 2 (Líneas 2385 - 2480)

```kotlin
2385:             var resolvedArtwork: Bitmap? = null
2386:             var resolvedAppIconFinal: Bitmap? = null
2387:             var resolvedIconKey: String? = null
2388:             var resolvedTierFinal: Int = TIER_NONE
2389: 
2390:             // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
2391:             // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
2392:             var artworkTimedOut = false
2393: 
2394:             // REGLA Artwork-1: Resolución dirigida por incoherencia (evita ráfagas CPU)
2395:             if (controller != null && metadata != null && artIncoherent) {
2396:                 
2397:                 // A. Portada (v6.3 Pipeline Unificado)
2398:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2399:                     resolveArtworkDeduplicated(
2400:                         snapshot = snapshot,
2401:                         controller = controller,
2402:                         metadata = metadata,
2403:                         generation = myGeneration
2404:                     )
2405:                 } ?: run {
2406:                     artworkTimedOut = true
2407:                     null
2408:                 }
2409:             }
2410: 
2411:             // B. Icono de app (Optimizado)
2412:             if (controller != null && metadata != null && 
2413:                 (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION)) {
2414:                 val (icon, tier) = resolveAppIcon(snapshot.packageName)
2415:                 
2416:                 // Solo actualizamos si el nuevo tier es mejor o igual al actual (o es un cambio de app)
2417:                 if (icon != null && (appChangedUI || tier > currentIconTier)) {
2418:                     resolvedAppIconFinal = icon
2419:                     resolvedIconKey = "${snapshot.packageName}_stable"
2420:                     resolvedTierFinal = tier
2421:                 }
2422:             }
2423: 
2424:             // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
2425:             if (songChangedForLyrics) {
2426:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
2427:                 lyricsUpdateJob?.cancel()
2428:                 lyricsFetchJob?.cancel()
2429:                 currentLyrics = null
2430:                 
2431:                 lyricsFetchJob = serviceScope.launch {
2432:                     // PUNTO B: Debounce para evitar spam de API
2433:                     delay(500L)
2434:                     
2435:                     val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
2436:                     if (result != null && isActive) {
2437:                         currentLyrics = result
2438:                         relaunchLyricsTicker("identity_change")
2439:                     } else if (isActive) {
2440:                         // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
2441:                         InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
2442:                         updateLyricInWidget(snapshot.trackKey, "")
2443:                     }
2444:                 }
2445:             } else if (trackChangedUI && currentLyrics != null) {
2446:                 // La canción de negocio es la misma (solo se afinó trackKey, ej. duración tardía).
2447:                 // Reutilizamos la letra ya cargada en vez de re-buscarla en red (Conjunto Letras-3).
2448:                 relaunchLyricsTicker("metadata_refined")
2449:             } else {
2450:                 // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
2451:                 if (currentLyrics == null) {
2452:                     currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
2453:                 }
2454: 
2455:                 val effectivePos = previousApplied?.projectedPositionMs() ?: 0L
2456:                 val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
2457:                 
2458:                 // Hard-Sync: Solo si el desvío es mayor a 1s o cambió el estado
2459:                 val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true
2460: 
2461:                 if (shouldResync && currentLyrics != null) {
2462:                     relaunchLyricsTicker("state_sync")
2463:                 }
2464:             }
2465: 
2466:             val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
2467:             if (myGeneration != generation.get() && !isStillRelevant) {
2468:                 Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
2469:                 return
2470:             }
2471: 
2472:             kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
2473:                 commitMutex.withLock {
2474: 
2475:                     val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
2476:                     if (myGeneration != generation.get() && !isStillRelevantInLock) {
2477:                         Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
2478:                         return@withLock
2479:                     }
2480: 
2481:                     if (controller != null && metadata != null && 
```

### PARTE 2 de 2 (Líneas 2482 - 2562)

```kotlin
2482:                         (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
2483:                         
2484:                         if (resolvedArtwork != null) {
2485:                             // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
2486:                             // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
2487:                             // SEGURIDAD IPC (v4.5): Escalado de cortesía para el bus Binder.
2488:                             val transportBitmap = scaleForTransport(resolvedArtwork)
2489:                             val cacheKey = "${rawSnapshot.artworkKey}_raw"
2490:                             MusicWidget.bitmapCache.put(cacheKey, transportBitmap)
2491: 
2492:                             // Paso 3.2: CACHING DE TRANSFORMACIÓN
2493:                             if (savedArtworkKey != snapshot.artworkKey) {
2494:                                 // 1. Guardar versión RAW
2495:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2496:                                 
2497:                                 // 2. Guardar versión WIDGET (Píldora)
2498:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
2499:                                 
2500:                                 saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
2501:                                 savedArtworkKey = snapshot.artworkKey
2502: 
2503:                                 // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
2504:                                 // Inyectamos el bitmap en el snapshot lógico para que la próxima 
2505:                                 // transición de historial lo lleve ya resuelto.
2506:                                 lastLogicalSnapshot = lastLogicalSnapshot?.copy(
2507:                                     artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
2508:                                 )
2509:                             }
2510:                         } else if (trackChangedUI || artworkChangedUI) {
2511:                             // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
2512:                             val placeholder = getPlaceholderBitmap()
2513:                             saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2514:                             saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
2515:                             saveTextToFile("", ALBUM_ART_KEY_FILE)
2516:                             savedArtworkKey = null
2517:                         }
2518: 
2519:                         if (resolvedAppIconFinal != null && resolvedIconKey != null) {
2520:                             saveBitmapToFile(resolvedAppIconFinal, APP_ICON_FILE)
2521:                             saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
2522:                             savedAppIconKey = resolvedIconKey
2523:                             currentIconTier = resolvedTierFinal
2524:                         } else if (appChangedUI) {
2525:                             // FIX: Solo borramos la llave si la APP cambió y no tenemos nuevo icono.
2526:                             // Esto evita la alternancia visual (flicker) al cambiar de track en la misma app.
2527:                             saveTextToFile("", APP_ICON_KEY_FILE)
2528:                             savedAppIconKey = null
2529:                             currentIconTier = TIER_NONE
2530:                         }
2531:                     }
2532: 
2533:                     val currentInfo = musicDataStore.musicInfoFlow.first()
2534:                     val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
2535:                     val canKeepLyric = snapshot.isSessionActive &&
2536:                         currentInfo.lyricsTrackKey.isNotBlank() &&
2537:                         MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
2538:                             MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
2539:                     
2540:                     val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2541:                     val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
2542: 
2543:                     val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
2544: 
2545:                     // REGLA Artwork-1: Conservar identidad de portada si no hubo resolución
2546:                     val (finalArtworkKey, finalArtworkUri) = if (artIncoherent) {
2547:                         snapshot.artworkKey to (snapshot.artworkUri ?: "")
2548:                     } else {
2549:                         currentInfo.artworkKey to currentInfo.artworkUri
2550:                     }
2551: 
2552:                     val finalMusicInfo = MusicInfo(
2553:                         title = snapshot.title,
2554:                         artist = snapshot.artist,
2555:                         packageName = snapshot.packageName,
2556:                         album = snapshot.album ?: "",
2557:                         trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2558:                         artworkKey = finalArtworkKey,
2559:                         artworkUri = finalArtworkUri,
2560:                         appIconKey = savedAppIconKey ?: "",
2561:                         isPlaying = isPlaying,
2562:                         isSessionActive = snapshot.isSessionActive,
```

---

## ZZ2. Declaración de tipo de `resolvedArtwork`

- **Variable `resolvedArtwork`:** Declarada como `Bitmap?` (Línea 2385).
- **Función `resolveArtworkDeduplicated`:** Devuelve `Bitmap?` (Línea 2843).

---

## ZZ3. Confirmación directa

**NO.** En ningún punto del tramo mostrado (ZZ1), el valor de `finalArtworkUri` (o `snapshot.artworkUri`) se reemplaza o deriva de `resolvedArtwork`.

La variable `resolvedArtwork` se utiliza exclusivamente para:
1.  Poblar la caché de Glance (Línea 2490).
2.  Persistir los archivos físicos `.webp` (Líneas 2495 y 2498).
3.  Inyectar el bitmap en el diario lógico (Línea 2507).

Sin embargo, para la construcción de `finalMusicInfo`, los campos de metadatos se resuelven independientemente de la siguiente manera:

```kotlin
2546:                     val (finalArtworkKey, finalArtworkUri) = if (artIncoherent) {
2547:                         snapshot.artworkKey to (snapshot.artworkUri ?: "")
2548:                     } else {
2549:                         currentInfo.artworkKey to currentInfo.artworkUri
2550:                     }
```

**Confirmación de ausencia:** No existe ninguna línea que asigne a `finalArtworkUri` un valor derivado de `resolvedArtwork`.
