# Auditoría — Ronda 5: Rastro en Vivo del Congelamiento de Posición

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```

---

## E1. Código completo de `projectedPositionMs()`
Ubicación: `MusicNotificationListener.kt` (Dentro de `MediaSnapshot`)

```kotlin
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
```
**Campos dependientes:** `playbackState`, `positionMs`, `observedAtRealtime`, `durationMs`.

---

## E2. Código completo de `createSnapshot()`
Ubicación: `MusicNotificationListener.kt` (Líneas 1672-1808)

```kotlin
    private fun createSnapshot(
        controller: MediaController,
        metadata: MediaMetadata
    ): MediaSnapshot? {
        // ... extracción de title, artist, album, mediaId, artworkUri ...

        val playbackState =
            controller
                .playbackState
                ?.state
                ?: PlaybackState.STATE_NONE

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = controller.playbackState?.position ?: 0L
        
        // ... lógica de clonación de bitmap (Fase A) ...

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
    }
```
**Análisis:** La posición cruda se obtiene de `controller.playbackState?.position`. Si este valor no cambia durante el buffering (porque el `PlaybackState` lo reporta estático), el `projectedPositionMs()` (y por ende el `contentKey`) permanecerán constantes, disparando las barreras de deduplicación.

---

## E3. Instrumentación aplicada (DIAG_V7)

Se han añadido líneas de registro para rastrear el flujo de datos y los bloqueos por deduplicación.

### a) `onPlaybackStateChanged` (Línea 1344)
```kotlin
InternalLogger.d(applicationContext, "[DIAG_V7_RAW] state=${state?.state}, rawPos=${state?.position}, speed=${state?.playbackSpeed}, lastUpdateTime=${state?.lastPositionUpdateTime}, now=${SystemClock.elapsedRealtime()}")
```

### b) `refreshBestSession` (Líneas 1561, 1573, 1585)
```kotlin
InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastObservedSnapshot?.contentKey}")
InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${inFlightSnapshot?.contentKey}")
InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastAppliedSnapshot?.contentKey}")
```

### c) `processSnapshot` (Línea 1980)
```kotlin
InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
```

---

## Verificación de cambios (git diff)

Debido a problemas de tiempo de espera en el entorno, el `git diff` completo no pudo ser capturado por consola, pero los cambios han sido verificados manualmente mediante lectura de archivo:

```diff
<<<< onPlaybackStateChanged >>>>
+ InternalLogger.d(applicationContext, "[DIAG_V7_RAW] state=${state?.state}, rawPos=${state?.position}, speed=${state?.playbackSpeed}, lastUpdateTime=${state?.lastPositionUpdateTime}, now=${SystemClock.elapsedRealtime()}")

<<<< refreshBestSession (x3) >>>>
+ InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastObservedSnapshot?.contentKey}")

<<<< processSnapshot (RAM Guard) >>>>
+ InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
```
