# Auditoría — Ronda 12: Disparadores de Resolución de Sesiones Pendientes y Resurrección del Historial

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## DD1. ¿Qué evento dispara la resolución de una sesión marcada como "pendiente de compromiso"?

La lectura de `isPendingCommit` ocurre durante la rehidratación del servicio en `onListenerConnected`:

```kotlin
1135:             InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.")
1136:             val currentInfo = musicDataStore.musicInfoFlow.first()
1137:             InternalLogger.d(applicationContext, "[HIST_BOOT] BOOT_DATA_READY: sessionUUID=${currentInfo.sessionUUID}")
1138:             if (currentInfo.trackKey.isNotEmpty()) {
...
1167:                 
1168:                 // Si la sesión estaba en el limbo, notificamos para que el HistoryWorker esté alerta
1169:                 if (currentInfo.isPendingCommit) {
1170:                     InternalLogger.d(applicationContext, "[REHYDRATION] Sesión en el limbo recuperada (UUID=${currentInfo.sessionUUID}). Esperando reconciliación.")
1171:                 }
...
1186:             }
```

**Análisis:**
- El disparo es el **inicio/reinicio del proceso del Listener** (`onListenerConnected`).
- No hay un temporizador propio para "mirar" sesiones pendientes; se detectan al arrancar y se resuelven en el primer `processSnapshot` que ocurra tras recibir metadatos vivos.

---

## DD2. Código completo de "RESURRECCIÓN CONFIRMADA"

Ubicada en `processSnapshot` (BLOQUE 6.1):

```kotlin
2103:         // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
2104:         if (session != null && session.isProvisional) {
2105:             if (!identityChanged) {
2106:                 // ESCENARIO A: Resurrección tras Doze confirmada. 
2107:                 // Adoptamos la sesión rehidratada y limpiamos el flag de provisional.
2108:                 session.isProvisional = false
2109:                 InternalLogger.d(applicationContext, "[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=${session.sessionUUID}")
2110:             } else {
...
2123:             }
2124:         }
```

**Condición exacta:**
1. La sesión actual debe estar marcada como `isProvisional` (estado post-boot).
2. `identityChanged` debe ser `false` (la canción recibida del sistema es la misma que la que estaba persistida antes del reinicio).

---

## DD3. Código completo del consumidor de historial y su contador de "resurrections"

El contador se incrementa en el bloque `catch` del bucle principal de `startHistoryWorker`:

```kotlin
717:     private fun startHistoryWorker() {
718:         val workerId = System.identityHashCode(this)
719:         serviceScope.launch(Dispatchers.IO + historyHandler) {
...
760:                     InternalLogger.w(applicationContext, "[HIST_CONSUMER] [$workerId] CHANNEL_CLOSED_EXIT")
761:                     break
762:                 } catch (ce: CancellationException) {
763:                     throw ce
764:                 } catch (t: Throwable) {
765:                     resurrectionsCount.incrementAndGet()
766:                     InternalLogger.e(applicationContext, "[HIST_CONSUMER] [$workerId] LOOP_DEATH_RESURRECTING #${resurrectionsCount.get()}: ${t.message}")
767:                     delay(500L)
768:                 }
769:             }
770:             InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_LOOP_EXIT")
771:         }
772:     }
```

**Condición:**
Cualquier error crítico (`Throwable`) distinto de una cancelación que provoque la muerte del bucle del canal de historial. El sistema incrementa el contador y reinicia el bucle tras un retardo de 500ms.

---

## DD4. ¿El latido (`CONSUMER_HEARTBEAT`) depende de recibir algo externo para seguir funcionando?

No, el latido tiene su propia corrutina independiente lanzada al inicio de `startHistoryWorker`:

```kotlin
723:             // Heartbeat cada 60s (B1.4)
724:             launch {
725:                 while (isActive) {
726:                     delay(60000L)
727:                     InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_HEARTBEAT: " +
728:                         "scopeActive=$isActive, channelClosed=${historyChannel.isClosedForSend}, " +
729:                         "successCount=${successCounter.get()}, failCount=${failureCounter.get()}, " +
730:                         "pendingCount=${pendingEventsCount.get()}, resurrections=${resurrectionsCount.get()}")
731:                 }
732:             }
```

**Confirmación:**
El temporizador es propio (`delay(60000L)`) e independiente de avisos externos o la llegada de eventos al canal. Seguirá emitiendo mientras el `serviceScope` esté activo.
