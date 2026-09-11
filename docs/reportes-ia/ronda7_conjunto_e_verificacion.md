# Auditoría — Ronda 7: Verificación Previa al Conjunto E (Reloj de Verdad)

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```

---

## H1. Declaración completa de `MediaSnapshot`
Ubicación: `MusicNotificationListener.kt` (Líneas 465-565)

```kotlin
    private data class MediaSnapshot(
        val packageName: String,
        val title: String,
        val artist: String,
        val album: String?,
        val mediaId: String?,
        val artworkUri: String?,
        val playbackState: Int,
        val isSessionActive: Boolean,
        val playbackDeviceName: String,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val recordedAt: Long = System.currentTimeMillis(),
        val artworkSource: ArtworkSource = ArtworkSource.Placeholder,
        val firstObservedAt: Long = recordedAt,
        val observedAtRealtime: Long = SystemClock.elapsedRealtime(),
        val playbackDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    ) {
        /**
         * Canonical "is this the same song" identity: package + title + artist, normalized.
         * This is the single source of truth for business-logic identity comparisons.
         * Never include album or duration here.
         */
        val sessionIdentity: String
            get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)

        /**
         * Business identity key used for history matching, streak tracking, and "blessed repeat"
         * detection. Deliberately does NOT include the album — a song's single and album editions
         * are treated as the same track for these purposes (see Conjunto C).
         * Do not add album back into this formula; use [artworkKey] for anything that needs to
         * distinguish album editions.
         */
        val trackKey: String
            get() = "$sessionIdentity|$durationMs"

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

        /*
         * Identidad base de contenido (v9.0).
         */
        val coreKey: String
            get() = "${MusicDataStore.normalize(title)}|${MusicDataStore.normalize(artist)}"

        /*
         * Identidad completa del snapshot.
         * Incluye la posición redondeada para detectar Seeks significativos.
         */
        val contentKey: String
            get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"

        /** 
         * ORÁCULO DE TIEMPO PURO (v9.0): Proyecta la posición basado en el tiempo transcurrido.
         * No conserva estado de marca de agua (Regla D.4).
         */
        fun projectedPositionMs(
            nowRealtime: Long = SystemClock.elapsedRealtime()
        ): Long {
            if (playbackState != PlaybackState.STATE_PLAYING) return positionMs
            val delta = nowRealtime - observedAtRealtime
            val projected = positionMs + delta
            
            return if (durationMs > 0) projected.coerceIn(0L, durationMs)
            else projected.coerceAtLeast(0L)
        }

        companion object {
            /** Única ruta admitida de rehidratación (v7.0). */
            fun fromPersisted(info: MusicInfo): MediaSnapshot {
                return MediaSnapshot(
                    packageName = info.packageName,
                    title = info.title,
                    artist = info.artist,
                    album = info.album,
                    mediaId = "",
                    artworkUri = info.artworkUri,
                    playbackState = if (info.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    isSessionActive = info.isSessionActive,
                    playbackDeviceName = info.playbackDeviceName,
                    playbackDeviceType = info.playbackDeviceType,
                    durationMs = info.durationMs,
                    positionMs = info.lastMaxPositionMs, // Usar el último récord como base al boot
                    observedAtRealtime = SystemClock.elapsedRealtime()
                )
            }
        }
    }
```

---

## H2. Lugares de construcción de `MediaSnapshot`

### 1. Rehidratación (v7.0)
Ubicación: `MusicNotificationListener.kt:547` (Dentro de `companion object.fromPersisted`)
```kotlin
return MediaSnapshot(
    packageName = info.packageName,
    title = info.title,
    artist = info.artist,
    album = info.album,
    mediaId = "",
    artworkUri = info.artworkUri,
    playbackState = if (info.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
    isSessionActive = info.isSessionActive,
    playbackDeviceName = info.playbackDeviceName,
    playbackDeviceType = info.playbackDeviceType,
    durationMs = info.durationMs,
    positionMs = info.lastMaxPositionMs, // Usar el último récord como base al boot
    observedAtRealtime = SystemClock.elapsedRealtime()
)
```

### 2. Captura de Evento (Pipeline Principal)
Ubicación: `MusicNotificationListener.kt:1793` (Dentro de `createSnapshot`)
```kotlin
return MediaSnapshot(
    packageName = controller.packageName,
    title = title,
    artist = artist,
    album = album,
    mediaId = mediaId,
    artworkUri = artworkUri,
    playbackState = playbackState,
    isSessionActive = true,
    playbackDeviceName = deviceName,
    playbackDeviceType = deviceType,
    durationMs = duration,
    positionMs = position,
    recordedAt = System.currentTimeMillis(),
    artworkSource = ArtworkSource.Placeholder,
    observedAtRealtime = SystemClock.elapsedRealtime()
)
```

### 3. Derivaciones mediante `.copy()`
Se identificaron los siguientes puntos donde se crean copias, las cuales heredarán los nuevos campos automáticamente si estos tienen valores por defecto:
- **Seek Event Processor (Línea 663):** `snapshot.copy(positionMs = ..., observedAtRealtime = ...)`
- **Process Snapshot (Línea 2029):** `rawSnapshot.copy(firstObservedAt = ..., artworkSource = ...)`
- **Artwork Relay (Línea 2465):** `lastLogicalSnapshot?.copy(artworkSource = ...)`

---

## H3. Confirmación de Ubicaciones actuales

- **`projectedPositionMs()`:** Línea 533 en `MusicNotificationListener.kt`.
- **`return MediaSnapshot(...)` en `createSnapshot()`:** Línea 1793 en `MusicNotificationListener.kt`.
- **`return MediaSnapshot(...)` en `fromPersisted()`:** Línea 547 en `MusicNotificationListener.kt`.
