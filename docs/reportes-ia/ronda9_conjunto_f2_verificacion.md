# Auditoría — Ronda 9: Verificación Puntual del Árbitro de Estado

## Estado del Proyecto (git log)

```text
77ff7a9 (HEAD -> master) Conjunto F.1: Andamiaje del estado Cargando (Buffering)
bf6f590 Conjunto E: Implementación del Reloj de Verdad usando señales oficiales de PlaybackState
bb7b30a Docs: descriptive identity and sync comments (Phase 1)
```

---

## J1. Declaración de `MusicUpdateEvent`
Ubicación: `MusicStateProvider.kt` (Línea 128)

```kotlin
    sealed class MusicUpdateEvent {
        data class NewSession(val info: MusicInfo) : MusicUpdateEvent()
        data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: Long, val isPlaying: Boolean) : MusicUpdateEvent()
        data class ArtworkResolved(val trackKey: String, val artworkKey: String, val iconKey: String? = null) : MusicUpdateEvent()
        data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
        data class SessionEnded(val finalPos: Long) : MusicUpdateEvent()
        data class StatusUpdate(val isPlaying: Boolean, val deviceName: String, val deviceType: Int) : MusicUpdateEvent()
        object ClearVisualHistory : MusicUpdateEvent()
    }
```

## J2. Código de `MusicStateProvider.applyEvent`
Ubicación: `MusicStateProvider.kt` (Línea 33)

```kotlin
    suspend fun applyEvent(event: MusicUpdateEvent): Boolean = mutationMutex.withLock {
        val current = _musicInfoState.value
        val next = when (event) {
            is MusicUpdateEvent.NewSession -> reconcileNewSession(current, event)
            is MusicUpdateEvent.MetadataRefinement -> reconcileRefinement(current, event)
            is MusicUpdateEvent.ArtworkResolved -> reconcileArtwork(current, event)
            is MusicUpdateEvent.LyricTick -> reconcileLyric(current, event)
            is MusicUpdateEvent.SessionEnded -> reconcileEnd(current, event)
            is MusicUpdateEvent.StatusUpdate -> reconcileStatus(current, event)
            is MusicUpdateEvent.ClearVisualHistory -> current.copy(history = emptyList())
        }

        // Full-object equality check — this intentionally catches ANY field change, including
        // isPlaying, so a real play/pause transition always triggers a UI notification.
        if (next == current) return@withLock false
        
        _musicInfoState.value = next
        return@withLock true
    }
```

### Reconciliación de `StatusUpdate`
```kotlin
    private fun reconcileStatus(current: MusicInfo, e: MusicUpdateEvent.StatusUpdate): MusicInfo {
        val statusChanged = current.isPlaying != e.isPlaying || 
                           current.playbackDeviceName != e.deviceName
        
        return current.copy(
            isPlaying = e.isPlaying,
            playbackDeviceName = e.deviceName,
            playbackDeviceType = e.deviceType,
            lastUpdateEpoch = if (statusChanged) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (statusChanged) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }
```
**Análisis:** `StatusUpdate` utiliza `.copy()` sobre el estado actual (`current`) de la RAM, preservando todos los campos que no se pasan explícitamente (como `isBuffering`).

## J3. Relación disco/RAM con pantalla apagada
Ubicación: `MusicNotificationListener.kt` (Dentro de `processSnapshot`)

El código sigue este orden:
1. **FAST-TRACK SSOT (Líneas 2212-2270):** Se lanza un job que llama a `MusicStateProvider.applyEvent(event)`. Esto actualiza la RAM **siempre**, incluso con pantalla apagada.
2. **Gating de Persistencia (Líneas 2274-2305):** Si `!isWidgetPotentiallyVisible()`, se lanza un job que llama a `musicDataStore.saveMusicInfo(...)`. Esto asegura que el disco también se actualice en modo latente.
3. **Gating de Stage 2 (Líneas 2307-2321):** Si `!isWidgetPotentiallyVisible()`, la función retorna temprano para evitar la resolución pesada de carátulas.

**Conclusión:** La RAM **se actualiza** vía `applyEvent` en todos los casos. El diseño del Conjunto F.2 debe simplemente asegurar que los eventos (como `StatusUpdate`) incluyan el estado de `isBuffering` para que `MusicStateProvider` lo refleje en la RAM y se persista coherentemente.
