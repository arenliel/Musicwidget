# Auditoría — Ronda 12: Disparadores de Resolución de Sesiones Pendientes y Resurrección del Historial

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## DD1. ¿Qué evento dispara la resolución de una sesión marcada como "pendiente de compromiso"?

La lectura de `isPendingCommit` ocurre durante la rehidratación en `onListenerConnected`:

```kotlin
1135:             InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.")
1136:             val currentInfo = musicDataStore.musicInfoFlow.first()
1137:             InternalLogger.d(applicationContext, "[HIST_BOOT] BOOT_DATA_READY: sessionUUID=${currentInfo.sessionUUID}")
1138:             if (currentInfo.trackKey.isNotEmpty()) {
...
1168:                 
1169:                 // Si la sesión estaba en el limbo, notificamos para que el HistoryWorker esté alerta
1170:                 if (currentInfo.isPendingCommit) {
1171:                     InternalLogger.d(applicationContext, "[REHYDRATION] Sesión en el limbo recuperada (UUID=${currentInfo.sessionUUID}). Esperando reconciliación.")
1172:                 }
```

**Disparador:** El inicio o reinicio del proceso del servicio (`onListenerConnected`). No existe un temporizador recurrente; la sesión se resuelve al recibir el primer snapshot vivo en `processSnapshot`.

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

**Condición:** `session.isProvisional` (sesión rehidratada al arranque) y `!identityChanged` (la canción entrante coincide con la persistida).

---

## DD3. Código completo del consumidor de historial y su contador de "resurrections"

El contador se incrementa en el bloque `catch` del bucle principal en `startHistoryWorker`:

```kotlin
717:     private fun startHistoryWorker() {
718:         val workerId = System.identityHashCode(this)
719:         serviceScope.launch(Dispatchers.IO + historyHandler) {
...
761:                     InternalLogger.w(applicationContext, "[HIST_CONSUMER] [$workerId] CHANNEL_CLOSED_EXIT")
762:                     break
763:                 } catch (ce: CancellationException) {
764:                     throw ce
765:                 } catch (t: Throwable) {
766:                     resurrectionsCount.incrementAndGet()
767:                     InternalLogger.e(applicationContext, "[HIST_CONSUMER] [$workerId] LOOP_DEATH_RESURRECTING #${resurrectionsCount.get()}: ${t.message}")
768:                     delay(500L)
769:                 }
770:             }
771:             InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_LOOP_EXIT")
772:         }
773:     }
```

**Condición:** Ocurre cuando el bucle de procesamiento del canal de historial muere por un error inesperado (`Throwable`), provocando el reinicio de la escucha.

---

## DD4. ¿El latido (`CONSUMER_HEARTBEAT`) depende de recibir algo externo para seguir funcionando?

No, el latido es independiente. Se lanza como una corrutina hermana al inicio de `startHistoryWorker`:

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

**Confirmación:** Tiene su propio temporizador (`delay(60000L)`) y solo depende de que el `serviceScope` permanezca activo.
