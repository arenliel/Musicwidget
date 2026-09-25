# Auditoría — Funciones reconcile* de MusicStateProvider

## 1. reconcileNewSession
```kotlin
    private fun reconcileNewSession(current: MusicInfo, e: MusicUpdateEvent.NewSession): MusicInfo {
        // Hallazgo v4.2: Estabilización de Scroll Inteligente.
        // Si la sesión cambió, permitimos que el historial se actualice (para mostrar la canción nueva).
        // Pero si los datos son idénticos, preservamos la referencia física para silenciar el scroll.
        val oldSessionChanged = current.trackKey != e.info.trackKey
        val sessionChanged = MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist) !=
            MusicDataStore.computeSessionIdentity(e.info.packageName, e.info.title, e.info.artist)
        android.util.Log.d("IDENTITY_TRACE", "Paso4_reconcileNewSession: viejo=$oldSessionChanged, nuevo=$sessionChanged, coincide=${oldSessionChanged == sessionChanged}")
        val stableHistory = if (!sessionChanged && e.info.history == current.history) {
            current.history
        } else {
            e.info.history
        }

        val shouldResetClock = sessionChanged || e.info.isPlaying

        // Identidad de negocio (sessionIdentity, no trackKey) para decidir si la letra actual
        // sigue perteneciendo a la misma canción. Evita descartar una letra válida solo porque
        // trackKey cambió por una corrección tardía de duración (Conjunto Letras-2).
        val lyricBelongsToSameSong = MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist) ==
            MusicDataStore.computeSessionIdentity(e.info.packageName, e.info.title, e.info.artist)

        return e.info.copy(
            currentLyric = if (lyricBelongsToSameSong) current.currentLyric else "",
            lyricsTrackKey = if (lyricBelongsToSameSong) e.info.trackKey else "",
            history = stableHistory,
            lastUpdateEpoch = if (shouldResetClock) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (shouldResetClock) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }
```

## 2. reconcileRefinement
```kotlin
    private fun reconcileRefinement(current: MusicInfo, e: MusicUpdateEvent.MetadataRefinement): MusicInfo {
        // Este evento nunca representa un cambio real de canción — es la misma pista con datos
        // afinados (ej. duración exacta llegando tarde). Si ya había una letra resuelta y
        // etiquetada, se re-etiqueta con el trackKey nuevo para que no quede huérfana
        // (Conjunto Letras-2).
        val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyricsTrackKey

        return current.copy(
            trackKey = e.newTrackKey,
            artworkKey = e.newArtworkKey,
            durationMs = e.newDuration,
            isPlaying = e.isPlaying,
            isBuffering = false,
            lyricsTrackKey = updatedLyricsTrackKey
        )
    }
```

## 3. reconcileArtwork
```kotlin
    private fun reconcileArtwork(current: MusicInfo, e: MusicUpdateEvent.ArtworkResolved): MusicInfo {
        if (e.trackKey != current.trackKey) return current
        
        return current.copy(
            artworkKey = e.artworkKey,
            appIconKey = e.iconKey ?: current.appIconKey
        )
    }
```

## 4. reconcileLyric
```kotlin
    private fun reconcileLyric(current: MusicInfo, e: MusicUpdateEvent.LyricTick): MusicInfo {
        // Conjunto Identidad-Final (regresión post-Parte 1): this must compare sessionIdentity, never
        // trackKey. trackKey includes duration, which frequently arrives late or gets corrected —
        // comparing against it here made every duration correction look like a track change, silently
        // killing the displayed lyric (the artist name kept showing, but the lyric line went blank)
        // even though the Zombie Detector upstream had already been fixed to ignore duration-only
        // changes. Same root cause, found again downstream where it hadn't been fixed yet.
        if (e.trackKey != MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist)) return current
        
        return current.copy(
            currentLyric = e.lyric,
            lyricsTrackKey = e.trackKey
        )
    }
```

## 5. reconcileStatus
```kotlin
    private fun reconcileStatus(current: MusicInfo, e: MusicUpdateEvent.StatusUpdate): MusicInfo {
        val statusChanged = current.isPlaying != e.isPlaying || 
                           current.playbackDeviceName != e.deviceName
        
        return current.copy(
            isPlaying = e.isPlaying,
            isBuffering = e.isBuffering,
            playbackDeviceName = e.deviceName,
            playbackDeviceType = e.deviceType,
            lastUpdateEpoch = if (statusChanged) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (statusChanged) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }
```
