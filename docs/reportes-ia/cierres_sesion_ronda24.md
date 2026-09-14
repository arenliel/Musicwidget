# Auditoría — Ronda 24: Bloque Completo de Construcción de `finalMusicInfo` (Valor de Portada)

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## RR1. Bloque completo, sin abreviar, desde `val currentInfo = musicDataStore.musicInfoFlow.first()` hasta la llamada a `musicDataStore.saveMusicInfo(...)`

Ubicado en `MusicNotificationListener.kt` (Líneas 2531 - 2570).

```kotlin
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
```

**Determinación del valor de portada:**
- El campo que identifica la portada en `finalMusicInfo` es **`artworkKey`** (Línea 2549).
- El valor se saca directamente de **`snapshot.artworkKey`** (el metadato extraído de la sesión).
- El valor de la imagen física (el bitmap) se resuelve previamente y se guarda en disco/caché usando esa misma llave. Si `artIncoherent` es verdadero, la resolución ocurre en las líneas **2397-2404** ( Stage 2, Fase de resolución).
- Si la resolución falla o excede el tiempo, se activa `artworkTimedOut = true` (Línea 2405) y se usa un placeholder o el valor previo, pero `finalMusicInfo.artworkKey` sigue recibiendo `snapshot.artworkKey`.

---

## RR2. Confirmación de orden: ¿la resolución de portada (`findRealAlbumArt`) termina antes o después de que se arma `finalMusicInfo`?

**TERMINA ANTES.**

La resolución de portada se realiza en la **fase 1** de la Stage 2 (Líneas 2384 - 2420):
```kotlin
2397:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2398:                     resolveArtworkDeduplicated(...)
```
Puesto que `processSnapshot` es una función de suspensión, el hilo de ejecución **espera** (con un tiempo de espera máximo) a que `resolveArtworkDeduplicated` (que invoca a `findRealAlbumArt`) termine y devuelva el `Bitmap?` antes de continuar a la construcción de `finalMusicInfo` en la línea 2543.

Es una operación **síncrona** dentro del flujo de la corrutina.
