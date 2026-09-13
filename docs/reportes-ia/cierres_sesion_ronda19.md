# Auditoría — Ronda 19: Mapa Completo de Lectores de las Variables Modificadas en Cierres-1 a 3

**Confirmación de Git Log:**
```
34fc859 (HEAD -> master) Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
```

---

## MM1. Todos los usos de `lastAppliedSnapshot` en todo el proyecto

### Archivo: `MusicNotificationListener.kt`

1. **Línea 253 (Declaración):**
```kotlin
    /*
     * Snapshot cuya actualización terminó correctamente (Estado Visual).
     */
    private var lastAppliedSnapshot: MediaSnapshot? = null
```

2. **Línea 698 (Limpieza en `startBlacklistObserver`):**
```kotlin
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
```

3. **Línea 1144 (Rehidratación en `onListenerConnected`):**
```kotlin
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
```

4. **Línea 1233 (Lectura en `onNotificationPosted`):**
```kotlin
        // Lógica de Ascenso de Icono dirigida por eventos
        serviceScope.launch {
            val lastSnapshot = lastAppliedSnapshot
            if (lastSnapshot != null && sbn.packageName == lastSnapshot.packageName && 
```

5. **Línea 1355 (Lectura en `onPlaybackStateChanged`):**
```kotlin
                        // REGLA 3: Intercepción del Seek (Optimizado con Debounce y Reloj Monotónico)
                        if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                            val lastSnapshot = lastAppliedSnapshot
                            if (lastSnapshot != null && lastSnapshot.packageName == controller.packageName) {
```

6. **Línea 1515 (Lectura en `refreshBestSession`):**
```kotlin
        if (
            activeSessions.isEmpty()
        ) {
            lastAppliedSnapshot?.let { last ->
```

7. **Línea 1579 (Lectura en `refreshBestSession`):**
```kotlin
        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            lastAppliedSnapshot
                ?.contentKey
        ) {
```

8. **Línea 1974 (Lectura en `processSnapshot`):**
```kotlin
        val previousReliable =
            lastAppliedSnapshot
```

9. **Línea 2215 (Escritura en `processSnapshot`):**
```kotlin
        // ACTUALIZACIÓN DEL DIARIO LÓGICO (Cierres-3)
        lastLogicalSnapshot = rawSnapshot
        lastAppliedSnapshot = snapshot
        lastObservedPositionMs = currentProjectedPos
```

10. **Línea 2359 (Lectura en `processSnapshot`):**
```kotlin
        // Si el widget es visible, reseteamos flags de gating
        isPresentationDirty = false
        pendingSnapshot = null

        val previousApplied = 
            lastAppliedSnapshot
```

11. **Línea 3208 (Limpieza en `onDestroy`):**
```kotlin
        controllerCallbacks.clear()
        selectedController = null
        lastObservedSnapshot = null
        lastAppliedSnapshot = null
        inFlightSnapshot = null
```

---

## MM2. Todos los usos de `previousReliable`

### Archivo: `MusicNotificationListener.kt`

1. **Línea 1973 (Declaración y Lectura en `processSnapshot`):**
```kotlin
        val previousReliable =
            lastAppliedSnapshot
```

2. **Línea 2067 (Lectura en `processSnapshot`):**
```kotlin
        val currentProjectedPos = rawSnapshot.projectedPositionMs()
        val lastProjectedPos = previousReliable?.projectedPositionMs() ?: 0L
```

---

## MM3. Todos los usos de `previousLogical`

### Archivo: `MusicNotificationListener.kt`

1. **Línea 1970 (Declaración y Lectura en `processSnapshot`):**
```kotlin
        // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
        // Esto permite que el historial use la fuente de verdad del último estado aplicado.
        val previousLogical =
            lastLogicalSnapshot
```

2. **Línea 1984 (Lectura en `processSnapshot`):**
```kotlin
        val trackContentChanged = previousLogical?.artworkKey != rawSnapshot.artworkKey
```

3. **Línea 2015 (Lectura en `processSnapshot`):**
```kotlin
        val appChanged = previousLogical?.packageName != rawSnapshot.packageName
```

4. **Línea 2030 (Lecturas en `processSnapshot`):**
```kotlin
        // REGLA DE PROMOCIÓN DE SESIÓN (Persistent Snapshot)
        if (sessionChanged && previousLogical != null && rawSnapshot.packageName != previousLogical.packageName) {
```

---

## MM4. Todos los usos de `isManualRewind`

### Archivo: `MusicNotificationListener.kt`

1. **Línea 2076 (Cálculo en `processSnapshot`):**
```kotlin
        // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
        val isManualRewind = !isCatchUp && currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged
```

2. **Línea 2089 (Lectura en `processSnapshot`):**
```kotlin
        // DECISIÓN ESTRUCTURAL: Solo rompemos la sesión si cambió la canción, loop o reinicio manual.
        // v9.0: isManualRewind ignorado si es provisional para evitar skips al boot (Bloque D.3)
        val sessionEnded = identityChanged || isRealLoop || (isManualRewind && session?.isProvisional == false)
```

**Confirmación:** No hay otros usos de `isManualRewind` en el proyecto.

---

## MM5. Todos los usos de `isCatchUp`

### Archivo: `MusicNotificationListener.kt`

1. **Línea 1988 (Cálculo en `processSnapshot`):**
```kotlin
        // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
        // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
        val isCatchUp = reason == "catch_up_render"
```

2. **Línea 1995 (Lectura en `processSnapshot`):**
```kotlin
        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        if (!isCatchUp && !trackContentChanged && !artIncoherent && 
```

3. **Línea 2011 (Lecturas en `processSnapshot`):**
```kotlin
        if (isCatchUp || artIncoherent) {
            InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
        }
```

4. **Línea 2076 (Lectura en `processSnapshot`):**
```kotlin
        // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
        val isManualRewind = !isCatchUp && currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged
```

5. **Línea 2079 (Lectura en `processSnapshot`):**
```kotlin
        // 2. Evaluamos si es un resurgimiento del sistema sin cambio real de tiempo (Catch-up)
        val isCatchUpRender = if (identityChanged) false else Math.abs(currentProjectedPos - lastProjectedPos) < 1500L
```
*(Nota: En la línea 2079 se usa `isCatchUpRender`, no `isCatchUp` directamente, pero son variables hermanas en la lógica FSM).*

6. **Línea 2569 (Lectura en `processSnapshot`):**
```kotlin
                    // 1. Sincronía Atómica: Disco -> RAM -> UI
                    val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
```
