# Auditoría — Ronda 3: Relojes Desincronizados + Interpretación de Estados en Conexión Pobre

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```

---

## Sección A — Relojes (continuación)

### A9. Código completo de `refreshBestSession`
Ubicación: `MusicNotificationListener.kt` (Línea 1492)

```kotlin
    private suspend fun refreshBestSession(
        reason: String
    ) {
        val componentName = ComponentName(this, MusicNotificationListener::class.java)
        val activeSessions = mediaSessionManager.getActiveSessions(componentName)

        if (activeSessions.isEmpty()) {
            lastLogicalSnapshot?.let { last ->
                // WARM-UP DE DESPERTAR (v5.2.5): Bloqueo de Placeholder. Rescatamos del escudo antes de emitir SessionEnded.
                serviceScope.launch(Dispatchers.IO) {
                    val shieldFile = File(cacheDir, DISK_SHIELD_FILE)
                    if (shieldFile.exists() && !memoryArtworkCache.containsKey(last.coreKey)) {
                        runCatching {
                            val bitmap = BitmapFactory.decodeFile(shieldFile.absolutePath)
                            if (bitmap != null) {
                                memoryArtworkCache[last.coreKey] = bitmap
                                InternalLogger.d(applicationContext, "[CATCH-UP] RAM Warmed-up tras desaparición de sesión: ${last.coreKey}")
                            }
                        }
                    }
                    
                    val finalPos = last.projectedPositionMs()
                    if (MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))) {
                        uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    }
                }
            }
            selectedController = null
            return
        }

        val controller = selectBestController(activeSessions) ?: return
        val metadata = controller.metadata ?: return
        val snapshot = createSnapshot(controller, metadata) ?: return

        // --- BARRERA DE DEDUPLICACIÓN DE ENTRADA ---
        if (reason != "catch_up_render" &&
            snapshot.contentKey == lastObservedSnapshot?.contentKey) {
            return
        }

        if (reason != "catch_up_render" &&
            snapshot.contentKey == inFlightSnapshot?.contentKey) {
            return
        }

        if (reason != "catch_up_render" &&
            snapshot.contentKey == lastAppliedSnapshot?.contentKey) {
            return
        }

        processSnapshot(
            controller = controller,
            metadata = metadata,
            rawSnapshot = snapshot,
            reason = reason
        )
    }
```

### A10. Texto de error en `requestRefresh`
Ubicación: `MusicNotificationListener.kt` (Línea 1485)

El log de error no utiliza la cadena literal solicitada, sino la siguiente:
```kotlin
Log.e(TAG, "Error actualizando sesión multimedia", e)
```

### A12. Actualización de `lastAppliedSnapshot`
Ubicación: `MusicNotificationListener.kt`

```kotlin
// Línea 696 (Limpieza)
    lastAppliedSnapshot = null

// Línea 1142 (Rehidratación al inicio)
    lastAppliedSnapshot = recoveredSnapshot

// Línea 2545 (Éxito del pipeline de proceso - FINAL de processSnapshot)
    lastAppliedSnapshot = snapshot

// Línea 3153 (Destrucción del servicio)
    lastAppliedSnapshot = null
```

---

## Sección C — Interpretación de Estados y Descarte de Paquetes

### C1. Código completo del "Gatekeeper"
Ubicación: `MusicNotificationListener.kt` (Líneas 1926-1936, dentro de `processSnapshot`)

```kotlin
        val currentMem = MusicStateProvider.current()

        // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
        // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
        // abortamos para proteger el estado coherente en RAM y Disco.
        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        val isDegraded = rawSnapshot.durationMs <= 0L

        if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
            return
        }
```
**Efecto:** El flujo se detiene con un `return`. No se actualiza `lastAppliedSnapshot`, ni se notifica al widget, ni se procesan letras.

### C2. Determinación de "Reproduciendo" vs "Pausado"
Ubicación: Múltiples puntos en `processSnapshot` (e.g., Línea 2038)

```kotlin
val isPlaying = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING
```

El estado `isPlaying` es estrictamente una comparación contra `STATE_PLAYING` (3). Cualquier otro estado, incluyendo `STATE_BUFFERING` (6), se traduce como `isPlaying = false`. Sin embargo, en `processSnapshot` (Línea 1913), los logs de diagnóstico agrupan los estados no explícitos como `OTHER`:

```kotlin
        val stateName = when(rawSnapshot.playbackState) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            else -> "OTHER(${rawSnapshot.playbackState})"
        }
```

### C3. Catálogo de condiciones de descarte/ignorado

| Ubicación (Archivo:Línea) | Condición Verbatim | Disparador (Campos) |
| :--- | :--- | :--- |
| `MNL.kt:1351` | `if (selectedController?.sessionToken != controller.sessionToken) return` | `sessionToken` divergente |
| `MNL.kt:1571` | `if (reason != "catch_up_render" && snapshot.contentKey == lastObservedSnapshot?.contentKey) return` | `contentKey` idéntico (Deduplicación entrada) |
| `MNL.kt:1579` | `if (reason != "catch_up_render" && snapshot.contentKey == inFlightSnapshot?.contentKey) return` | `contentKey` en proceso |
| `MNL.kt:1587` | `if (reason != "catch_up_render" && snapshot.contentKey == lastAppliedSnapshot?.contentKey) return` | `contentKey` ya aplicado |
| `MNL.kt:1936` | `if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty)` | `isLatent` (no PLAYING), `isDegraded` (Duración <= 0) |
| `MNL.kt:1971` | `if (!isCatchUp && !trackContentChanged && !artIncoherent && currentMem.isPlaying == (...) && currentMem.isSessionActive == (...))` | Deduplicación de RAM (Estado e Identidad idénticos) |
| `MNL.kt:2014` | `if (isNewSessionWeak) { ... return }` | `sessionChanged` pero el nuevo paquete no es `PLAYING` |
| `MNL.kt:2020` | `if (!isAppAllowed(rawSnapshot.packageName)) return` | `packageName` no permitido |
| `MNL.kt:2023` | `if (currentBlacklist.contains(rawSnapshot.packageName)) return` | `packageName` en lista negra |
| `MNL.kt:2133` | `if (session.isProvisional) return@let` | Sesión marcada como provisional (Evita commits al historial) |
| `MNL.kt:2226` | `if (myGeneration != generation.get() && !isStillRelevant) return` | Generación obsoleta (Pre-Lock) |
| `MNL.kt:2237` | `if (myGeneration != generation.get() && !isStillRelevantInLock) return@withLock` | Generación obsoleta (In-Lock) |
| `MNL.kt:2472` | `if (!isWidgetPotentiallyVisible()) { ... return }` | Pantalla apagada / Widget no visible (Gating de Stage 2) |
