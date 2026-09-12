# Auditoría — Ronda 6: Confiabilidad de `session.liveSnapshot` y Relación entre Mecanismos de Cierre

**Confirmación de Git Log:**
```
741c0ae (HEAD -> master) Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
2d4125d Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
```

---

## V1. Todos los lugares donde se asigna `liveSnapshot`

Ubicados en `MusicNotificationListener.kt`.

### 1. En `onListenerConnected` (Rehidratación) - Línea 1152
```kotlin
1144:                 lastAppliedSnapshot = recoveredSnapshot
1145:                 
1146:                 val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
1147:                 
1148:                 currentLogicalSession = LogicalSession(
1149:                     sessionUUID = currentInfo.sessionUUID,
1150:                     identity = recoveredIdentity,
1151:                     birthSnapshot = recoveredSnapshot,
1152:                     liveSnapshot = recoveredSnapshot,
1153:                     frozenTrackKey = currentInfo.trackKey,
1154:                     maxPositionMs = currentInfo.lastMaxPositionMs, // v9.0: Recuperar marca de agua
1155:                     isProvisional = true, // Marcada como provisional (BLOQUE 6.1)
```

### 2. En `processSnapshot` (Nueva Sesión) - Línea 2176
```kotlin
2169:             val newSession = LogicalSession(
2170:                 identity = newIdentity,
2171:                 birthSnapshot = rawSnapshot,
2172:                 liveSnapshot = rawSnapshot,
2173:                 frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
2174:                 maxPositionMs = rawSnapshot.positionMs, // v9.0: Reset marca de agua
2175:                 playbackContext = newContext,
2176:                 context = this@MusicNotificationListener
2177:             )
```

### 3. En `processSnapshot` (Fusión de Estado) - Línea 2208
```kotlin
2203:             val updatedContext = PlaybackContext(
2204:                 durationMs = rawSnapshot.durationMs,
2205:                 album = rawSnapshot.album,
2206:                 artworkKey = rawSnapshot.artworkKey
2207:             )
2208:             session?.let { s ->
2209:                 s.liveSnapshot = rawSnapshot
2210:                 s.playbackContext = updatedContext
2211:             }
```

---

## V2. ¿Esta asignación depende de la visibilidad de la pantalla?

1. **Línea 1152 (`onListenerConnected`):** **NO**. Ocurre durante la rehidratación al iniciar el servicio.
2. **Línea 2176 (`processSnapshot`):** **NO**. Se encuentra en la "STAGE 1" de `processSnapshot`, la cual se ejecuta siempre. El bloqueo de visibilidad ("STAGE 2") comienza en la línea 2322.
3. **Línea 2208 (`processSnapshot`):** **NO**. También se encuentra en la "STAGE 1", antes del bloqueo de visibilidad.

**Conclusión:** `session.liveSnapshot` se actualiza incondicionalmente respecto a la visibilidad de la pantalla.

---

## V3. Código completo y actual de la rama `activeSessions.isEmpty()` dentro de `requestRefresh`

(Nota: El bloque reside realmente dentro de `refreshBestSession`, la cual es invocada por `requestRefresh`).

```kotlin
1513:         if (
1514:             activeSessions.isEmpty()
1515:         ) {
1516:             lastLogicalSnapshot?.let { last ->
1517:                 // WARM-UP DE DESPERTAR (v5.2.5): Bloqueo de Placeholder. Rescatamos del escudo antes de emitir SessionEnded.
1518:                 serviceScope.launch(Dispatchers.IO) {
1519:                     val shieldFile = File(cacheDir, DISK_SHIELD_FILE)
1520:                     if (shieldFile.exists() && !memoryArtworkCache.containsKey(last.coreKey)) {
1521:                         runCatching {
1522:                             val bitmap = BitmapFactory.decodeFile(shieldFile.absolutePath)
1523:                             if (bitmap != null) {
1524:                                 memoryArtworkCache[last.coreKey] = bitmap
1525:                                 InternalLogger.d(applicationContext, "[CATCH-UP] RAM Warmed-up tras desaparición de sesión: ${last.coreKey}")
1526:                             }
1527:                         }
1528:                     }
1529:                     
1530:                     val finalPos = last.projectedPositionMs()
1531:                     if (MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))) {
1532:                         uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
1533:                     }
1534:                 }
1535:             }
1536:             selectedController = null
1537:             return
1538:         }
```

---

## V4. Relación entre esta rama y "pendiente de compromiso"

- **`RESURRECCIÓN CONFIRMADA` (Línea 2109):** Ubicada en `processSnapshot`.
- **`CIERRE RETROACTIVO` (Línea 2120):** Ubicada en `processSnapshot`.

**Confirmación:** Ninguna de las marcas de "retroactivo" o "resurrección" se encuentra dentro del bloque `activeSessions.isEmpty()` de `refreshBestSession`. Son mecanismos independientes que operan en etapas distintas del ciclo de vida de la sesión.
