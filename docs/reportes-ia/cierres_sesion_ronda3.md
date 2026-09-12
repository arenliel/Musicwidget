# Verificación — Ronda 3: Confirmación Directa de Ambas Variables (Post Cierres-1)

**Confirmación de Git Log:**
```
0d2d925 (HEAD -> master) Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
2d4125d Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
c9c5b7c Conjunto Letras-2: Regla Unificada de Silencio (10 segundos)
12b87e4 Conjunto Letras-1: Identidad Unificada para Preservación de Letra
6e5278f Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
```

---

## T1. Todos los lugares donde se asigna `lastAppliedSnapshot`

### Coincidencia 1: `startBlacklistObserver` (Línea 698)
```kotlin
    private fun startBlacklistObserver() {
        serviceScope.launch {
            musicDataStore.musicInfoFlow.collect { info ->
                val currentPkg = lastObservedSnapshot?.packageName
                if (currentPkg != null && info.blacklist.contains(currentPkg)) {
                    InternalLogger.d(applicationContext, "[BLACKLIST_PURGE] App actual $currentPkg ha sido añadida a la lista negra. Purgando.")
                    
                    // 1. Limpieza en Disco
                    musicDataStore.clearActiveSession()
                    
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
                    lastObservedSnapshot = null
                    inFlightSnapshot = null
                    savedArtworkKey = null
                    savedAppIconKey = null
                    
                    // 3. Sincronía Atómica: El observador startRamMirror actualizará la memoria (Fast-Track)
```

### Coincidencia 2: `onListenerConnected` (Línea 1144)
```kotlin
            val currentInfo = musicDataStore.musicInfoFlow.first()
            InternalLogger.d(applicationContext, "[HIST_BOOT] BOOT_DATA_READY: sessionUUID=${currentInfo.sessionUUID}")
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                
                val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
```

### Coincidencia 3: `processSnapshot` (Línea 2587)
```kotlin
                    // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
                    // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
                    
                    if (changedDisco || isSessionEnded || changedRAM) {
                        if (isSessionEnded) {
                            uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
                        } else {
                            uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                        }
                    }
                    
                    lastAppliedSnapshot = snapshot
                    lastObservedSnapshot = snapshot
                    lastCommittedInfo = MusicStateProvider.current()
                }
            }
```

### Coincidencia 4: `onDestroy` (Línea 3205)
```kotlin
        controllerCallbacks.clear()
        selectedController = null
        lastObservedSnapshot = null
        lastAppliedSnapshot = null
        inFlightSnapshot = null
        savedArtworkKey = null
        artworkCache.evictAll()
```

---

## T2. Todos los lugares donde se asigna `lastLogicalSnapshot`

### Coincidencia 1: `startSeekEventProcessor` (Línea 669)
```kotlin
                    val updatedSnapshot = snapshot.copy(
                        positionMs = position + processingLag,
                        observedAtRealtime = now
                    )
                    lastLogicalSnapshot = updatedSnapshot
                    relaunchLyricsTicker("seek_event")
                }
        }
    }
```

### Coincidencia 2: `startBlacklistObserver` (Línea 699)
```kotlin
                    // 1. Limpieza en Disco
                    musicDataStore.clearActiveSession()
                    
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
                    lastObservedSnapshot = null
                    inFlightSnapshot = null
                    savedArtworkKey = null
```

### Coincidencia 3: `onListenerConnected` (Línea 1143)
```kotlin
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                
                val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
```

### Coincidencia 4: `processSnapshot` (Línea 2211)
```kotlin
            session?.let { s ->
                s.liveSnapshot = rawSnapshot
                s.playbackContext = updatedContext
            }
        }

        // ACTUALIZACIÓN DEL DIARIO LÓGICO
        lastLogicalSnapshot = rawSnapshot
        lastObservedPositionMs = currentProjectedPos

        // ACTIVE WATCHER (v4.3.1): Cronómetro proactivo de 5s con LATE-READ
```

### Coincidencia 5: `processSnapshot` (Línea 2501)
```kotlin
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                                savedArtworkKey = snapshot.artworkKey

                                // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
                                // Inyectamos el bitmap en el snapshot lógico para que la próxima 
                                // transición de historial lo lleve ya resuelto.
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(
                                    artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
                                )
                            }
                        } else if (trackChangedUI || artworkChangedUI) {
```

---

## T3. Confirmación estructural, puramente mecánica

### `lastAppliedSnapshot`:
1. **Línea 698 (`startBlacklistObserver`):** **NO** depende de visibilidad. Está dentro del colector del DataStore.
2. **Línea 1144 (`onListenerConnected`):** **NO** depende de visibilidad. Ocurre durante la inicialización del servicio.
3. **Línea 2587 (`processSnapshot`):** **SÍ** depende de visibilidad. Se encuentra después de la línea 2322: `if (!isWidgetPotentiallyVisible()) { ... return }`.
4. **Línea 3205 (`onDestroy`):** **NO** depende de visibilidad. Ocurre al finalizar el servicio.

### `lastLogicalSnapshot`:
1. **Línea 669 (`startSeekEventProcessor`):** **NO** depende de visibilidad.
2. **Línea 699 (`startBlacklistObserver`):** **NO** depende de visibilidad.
3. **Línea 1143 (`onListenerConnected`):** **NO** depende de visibilidad.
4. **Línea 2211 (`processSnapshot`):** **NO** depende de visibilidad. Esta asignación ocurre en la "STAGE 1" de `processSnapshot`, antes de la comprobación `if (!isWidgetPotentiallyVisible())` de la línea 2322.
5. **Línea 2501 (`processSnapshot`):** **SÍ** depende de visibilidad. Se encuentra dentro de la "STAGE 2" (post-gating).
