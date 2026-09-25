# Auditoría — finalLyricKey

## 1. Resultado de Grep
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 2448: val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
  line 2461: lyricsTrackKey = finalLyricKey,
  line 2719: val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
  line 2758: lyricsTrackKey = finalLyricKey,
```

---

## 2. Bloques de código para cada aparición

### A. Primera aparición (alrededor de la línea 2448 - 2461)
```kotlin
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
```

### B. Segunda aparición (alrededor de la línea 2719 - 2758)
```kotlin
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
```
