# Auditoría Profunda — Cálculo de Porcentaje Escuchado y Alimentación de Rachas

## Sección A — Cálculo del porcentaje de reproducción (`SKIP_MATH`)

### A1. Bloque de cálculo de `SKIP_MATH`
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 825-842)

```kotlin
            val hasArtwork = artworkFile.exists()
            // v9.0: Usar marca de agua rehidratada/persistente para clasificación (Bloque D)
            val finalPos = maxPositionMs
            
            // CASCADA DE DURACIÓN (v9.0: Blindaje contra valores <= 0)
            val effectiveDuration = when {
                endSnapshot.durationMs > 0 -> endSnapshot.durationMs
                startSnapshot.durationMs > 0 -> startSnapshot.durationMs
                else -> {
                    val diskDur = musicDataStore.musicInfoFlow.first().durationMs
                    if (diskDur > 0) diskDur else 0L
                }
            }

            val progressFactor = if (effectiveDuration > 0) {
                finalPos.toFloat() / effectiveDuration.toFloat()
            } else -1f // Indicador de UNKNOWN

            // FÓRMULA DE SKIP PURA (v6.5): Basada exclusivamente en el progreso del Snapshot final
            // v7.0: Blindaje contra división por cero (UNKNOWN)
            var isSkipped = progressFactor in 0.0f..0.4f
```

**Origen de los valores:**
- **`FinalPos` (línea 825):** Es literalmente el parámetro `maxPositionMs` recibido por la función `commitToHistory`. Este valor proviene de la sesión lógica (`session.maxPositionMs`).
- **`Duration` (líneas 828-835):** Sigue una cascada de prioridad: `endSnapshot.durationMs` -> `startSnapshot.durationMs` -> valor persistido en `DataStore`.
- **`Factor` (línea 837):** Es la división simple de `finalPos / effectiveDuration`.
- **`Verdict` (`isSkipped`) (línea 841):** Es un booleano que determina si el factor está entre 0% y 40%.

### A2. Umbrales de clasificación
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 841-852)

```kotlin
            var isSkipped = progressFactor in 0.0f..0.4f
            
            // ... (lógica de bendición omitida) ...

            val isPartial = !isSkipped && progressFactor < 0.85f

            val outcome = when {
                isSkipped -> "SKIPPED"
                isPartial -> "PARTIAL"
                else -> "COMPLETED"
            }
```
**Umbrales exactos:**
- **SKIPPED:** 0% a 40% (inclusive).
- **PARTIAL:** Mayor al 40% y menor al 85%.
- **COMPLETED:** 85% o superior.

### A3. Dependencia de visibilidad
El cálculo de `SKIP_MATH` en sí mismo **no contiene condiciones de visibilidad**. Se ejecuta incondicionalmente cuando el `HistoryWorker` procesa un evento de commit. Sin embargo, la actualización visual posterior sí está condicionada (Línea 876):

```kotlin
            // v9.0: Refresco visual condicionado al estado de pantalla (Bloque E.2)
            if (isWidgetPotentiallyVisible()) {
                serviceScope.launch {
                    MusicWidget.updateAll(applicationContext)
                }
            } else {
                hasPendingUpdates = true
                InternalLogger.d(applicationContext, "[GATING] Commit de historial con pantalla apagada. Postergando refresco.")
            }
```

---

## Sección B — Alimentación de Rachas

### B1. Consumo de datos por mecanismo
Los tres mecanismos consumen directamente el resultado de `isSkipped` calculado en `commitToHistory`.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 855-857)

```kotlin
            val newStreak = musicDataStore.updateSkipStreak(startSnapshot.title, startSnapshot.artist, isSkipped)
            val repeatAnalytics = musicDataStore.updateRepeatStats(startSnapshot.title, startSnapshot.artist, isSkipped)
            if (!isSkipped && !isPartial) musicDataStore.updateArtistStats(startSnapshot.artist)
```

- **Racha de Skips (`updateSkipStreak`):** Recibe `isSkipped` (booleano).
- **Racha de Repetición (`updateRepeatStats`):** Recibe `isSkipped` (booleano). Internamente, si `isSkip` es true, mata la racha (Ver `MusicDataStore.kt:981`).
- **Artista Frecuente (`updateArtistStats`):** No recibe el booleano directamente, sino que su llamada está condicionada a que la canción sea `COMPLETED` (`!isSkipped && !isPartial`).

### B2. Momento de actualización
Los tres mecanismos se actualizan **únicamente en el momento del cierre de sesión** (dentro de `commitToHistory`), no en tiempo real.

### B3. Afectación del estado de la pantalla
El estado de la pantalla **no afecta de forma independiente** a estos mecanismos. Se actualizan siempre que se procese el commit de la sesión, independientemente de si el widget se redibuja o no.

---

## Sección C — Disponibilidad de datos y Proyección

### C1. Mecanismo de sondeo (Polling)
**No existe** actualmente un mecanismo de sondeo activo que pregunte periódicamente la posición al `MediaController`. El sistema es puramente reactivo a callbacks y eventos de sistema. El único "timer" relacionado es `unlockPollingJob`, pero solo verifica el estado del `Keyguard` tras encender la pantalla.

### C2. Disponibilidad del Snapshot en el cierre
En `processSnapshot`, cuando se detecta el fin de sesión (`sessionEnded = true`), el objeto `currentLogicalSession` todavía contiene la información de la sesión que expira.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2073-2086)

```kotlin
        if (sessionEnded && !isCatchUpRender) {
            // AQUÍ EJECUTAMOS EL COMPROMISO ATÓMICO AL HISTORIAL (v6.5)
            currentLogicalSession?.let { session ->
                // Verificación de seguridad v7.0: Una sesión provisional nunca emite aquí
                if (session.isProvisional) return@let

                val commitEvent = HistoryEvent.CommitSession(
                    sessionUUID = session.sessionUUID,
                    birthSnapshot = session.birthSnapshot,
                    finalSnapshot = session.liveSnapshot,
                    maxPositionMs = session.maxPositionMs,
                    startedAtRealtime = session.startedAtRealtime
                )
```
En este punto, `session.liveSnapshot` es el último snapshot capturado para esa sesión, el cual contiene los campos `positionMs` y `observedAtRealtime` necesarios para invocar `projectedPositionMs()`.

### C3. Fuentes de datos de posición
La **única fuente** de datos de posición son los objetos `PlaybackState` obtenidos a través de los `MediaController` (ya sea vía callback `onPlaybackStateChanged` o lectura directa en `refreshBestSession`). No hay otras vías de obtención de progreso.
