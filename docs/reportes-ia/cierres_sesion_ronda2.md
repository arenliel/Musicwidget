# Auditoría — Ronda 2: Actualización de `lastAppliedSnapshot`

**Confirmación de Git Log:**
```
2d4125d (HEAD -> master) Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
c9c5b7c Conjunto Letras-2: Regla Unificada de Silencio (10 segundos)
12b87e4 Conjunto Letras-1: Identidad Unificada para Preservación de Letra
6e5278f Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
77ff7a9 Conjunto F.1: Andamiaje del estado Cargando (Buffering)
```

---

## S1. Todos los lugares donde se asigna `lastAppliedSnapshot`

Ubicados en `MusicNotificationListener.kt`.

### 1. En `startBlacklistObserver` (Línea 698)
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
                    
                    // 4. Forzar actualización de Glance
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    
                    // 5. Intento de relevo
                    refreshBestSession(reason = "blacklist_handover")
                }
            }
        }
    }
```

### 2. En `onListenerConnected` (Línea 1144)
```kotlin
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                
                val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
                // ...
            }
```

### 3. En `processSnapshot` (Línea 2587)
```kotlin
                    // ... (sincronización de disco y RAM)
                    
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

### 4. En `onDestroy` (Línea 3205)
```kotlin
        controllerCallbacks.clear()
        selectedController = null
        lastObservedSnapshot = null
        lastAppliedSnapshot = null
        inFlightSnapshot = null
        savedArtworkKey = null
        artworkCache.evictAll()
        // ...
```

---

## S2. ¿Esta asignación depende de la visibilidad de la pantalla?

Basado en el código analizado:

1. **En `startBlacklistObserver`:** **Incondicional**. Ocurre cuando una app es añadida a la lista negra, sin importar el estado de la pantalla.
2. **En `onListenerConnected`:** **Incondicional**. Ocurre durante la rehidratación al conectar el servicio (boot/reinicio del proceso).
3. **En `processSnapshot`:** **CONDICIONADO**. Esta asignación se encuentra dentro de la "STAGE 2: PRESENTACIÓN", la cual tiene un bloque de salida temprana (Línea 2322) si la pantalla no está visible:
   ```kotlin
   if (!isWidgetPotentiallyVisible()) {
       // ...
       lastObservedSnapshot = snapshot
       return
   }
   ```
   Puesto que la asignación de `lastAppliedSnapshot = snapshot` ocurre después de este bloque, solo se ejecuta si `isWidgetPotentiallyVisible()` es true.
4. **En `onDestroy`:** **Incondicional**. Ocurre al destruir el servicio para limpieza de memoria.
