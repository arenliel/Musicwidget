# Auditoría — Ronda 5: Estado Actual del Mecanismo "Pendiente de Compromiso"

**Confirmación de Git Log:**
```
741c0ae (HEAD -> master) Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
2d4125d Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
```

---

## W1. Función completa y actual relacionada con "pendiente de compromiso"

### 1. En `onSessionDestroyed` (dentro de `updateActiveSessions`)
**Ubicación:** Línea 1388 - 1413

```kotlin
1388:                         // REGLA v6.7: Cierre Diferido.
1389:                         // Cuando la sesión muere, guardamos su estado en el DataStore marcándola como
1390:                         // "pendiente de compromiso". Si la sesión no resucita tras Doze, la archivaremos tarde.
1391:                         currentLogicalSession?.let { session ->
1392:                             if (session.liveSnapshot.packageName == controller.packageName) {
1393:                                 serviceScope.launch {
1394:                                     val currentInfo = musicDataStore.musicInfoFlow.first()
1395:                                     val finalPos = session.liveSnapshot.projectedPositionMs()
1396:                                     
1397:                                     val pendingInfo = currentInfo.copy(
1398:                                         isPendingCommit = true,
1399:                                         lastMaxPositionMs = Math.max(currentInfo.lastMaxPositionMs, finalPos),
1400:                                         isSessionActive = false,
1401:                                         isPlaying = false
1402:                                     )
1403:                                     musicDataStore.saveMusicInfo(pendingInfo)
1404:                                     
1405:                                     MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))
1406:                                     uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
1407:                                     
1408:                                     InternalLogger.d(applicationContext, "[FSM] Sesión interrumpida (UUID=${session.sessionUUID}). Marcada como pendiente de compromiso.")
1409:                                 }
1410:                             }
1411:                         }
```

### 2. En `processSnapshot` (BLOQUE 6.1)
**Ubicación:** Línea 2103 - 2125

```kotlin
2103:         // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
2104:         if (session != null && session.isProvisional) {
2105:             if (!identityChanged) {
2106:                 // ESCENARIO A: Resurrección tras Doze confirmada. 
2107:                 // Adoptamos la sesión rehidratada y limpiamos el flag de provisional.
2108:                 session.isProvisional = false
2109:                 InternalLogger.d(applicationContext, "[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=${session.sessionUUID}")
2110:             } else {
2111:                 // ESCENARIO B: Cierre Real. La canción cambió mientras el widget dormía.
2112:                 // Archivamos la sesión vieja usando el watermark persistido.
2113:                 historyChannel.trySend(HistoryEvent.CommitSession(
2114:                     sessionUUID = session.sessionUUID,
2115:                     birthSnapshot = session.birthSnapshot,
2116:                     finalSnapshot = session.liveSnapshot,
2117:                     maxPositionMs = session.maxPositionMs,
2118:                     startedAtRealtime = session.startedAtRealtime
2119:                 ))
2120:                 InternalLogger.d(applicationContext, "[HIST_BOOT] CIERRE RETROACTIVO: uuid=${session.sessionUUID}")
2121:                 currentLogicalSession = null
2122:                 // Continuamos al flujo normal de creación de sesión nueva
2123:             }
2124:         }
```

---

## W2. Confirmación de qué variable de posición usa actualmente

### Decisiones de Cierre y Posición:

1. **Línea 1395:** `val finalPos = session.liveSnapshot.projectedPositionMs()`
   - Se utiliza el `liveSnapshot` de la sesión actual (que es un `MediaSnapshot`) para proyectar la posición en el momento del cierre diferido.
   
2. **Línea 1399:** `lastMaxPositionMs = Math.max(currentInfo.lastMaxPositionMs, finalPos)`
   - Se utiliza `finalPos` (derivado de `liveSnapshot`) para actualizar la marca de agua (`lastMaxPositionMs`) en el `DataStore`.

3. **Línea 2117:** `maxPositionMs = session.maxPositionMs`
   - En el "CIERRE RETROACTIVO", se utiliza `session.maxPositionMs` (el campo de la `LogicalSession`) para el compromiso definitivo al historial.
   - Este `session.maxPositionMs` fue rehidratado en `onListenerConnected` desde `currentInfo.lastMaxPositionMs`.

**Resumen mecánico:** El mecanismo usa `liveSnapshot.projectedPositionMs()` para marcar el "limbo" y `session.maxPositionMs` (rehidratado) para el cierre retroactivo. No utiliza `lastAppliedSnapshot` ni `previousReliable` en estos bloques.
