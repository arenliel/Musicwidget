# Auditoría — Ronda 18: Condición Completa que Dispara la Resolución de Portada

**Confirmación de Git Log:**
```
34fc859 (HEAD -> master) Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
```

---

## KK1. Código completo y actual de `artIncoherent`

Ubicado en `MusicNotificationListener.kt`.

### Declaración (Línea 1991):
```kotlin
1986:         // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
1987:         // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
1988:         val isCatchUp = reason == "catch_up_render"
1989:         
1990:         // Detección de Incoherencia de Imagen: Si la portada en disco no coincide con la del snapshot, forzamos bypass
1991:         val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()
```

### Uso 1 (Línea 1995):
```kotlin
1993:         // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
1994:         // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
1995:         if (!isCatchUp && !trackContentChanged && !artIncoherent && 
1996:             currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
1997:             currentMem.isSessionActive == rawSnapshot.isSessionActive) {
```

### Uso 2 (Línea 2011):
```kotlin
2011:         if (isCatchUp || artIncoherent) {
2012:             InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
2013:         }
```

### Uso 3 (Línea 2570):
```kotlin
2568:                     // 1. Sincronía Atómica: Disco -> RAM -> UI
2569:                     val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
```

---

## KK2. La condición completa que precede a `[ARTWORK_RESOLVE]`

El log `[ARTWORK_RESOLVE]` reside en `resolveArtworkDeduplicated`. El flujo que lleva a su ejecución es el siguiente (dentro de `processSnapshot`):

```kotlin
2336:         if (!isWidgetPotentiallyVisible()) {
2337:             // ... (Stage 2 Gating - OMITIDO POR REGLA DE 100%)
... (Ver Ronda 17 para este bloque completo)
2351:             return
2352:         }
2353: 
2354:         // Si el widget es visible, reseteamos flags de gating
2355:         isPresentationDirty = false
...
2381:         try {
2382: 
2383:             // 1. Resolución de recursos visuales (Fase Cancelable).
...
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
2395:                 
2396:                 // A. Portada (v6.3 Pipeline Unificado)
2397:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2398:                     resolveArtworkDeduplicated(
2399:                         snapshot = snapshot,
2400:                         controller = controller,
2401:                         metadata = metadata,
2402:                         generation = myGeneration
2403:                     )
2404:                 } ?: run {
```

---

## KK3. ¿Se lee `lastAppliedSnapshot` o `previousReliable` en algún punto de la Etapa 2 (dentro de `isWidgetPotentiallyVisible()`)?

### `lastAppliedSnapshot`:
**SÍ**, se lee en la línea **2358** para inicializar `previousApplied`:

```kotlin
2354:         // Si el widget es visible, reseteamos flags de gating
2355:         isPresentationDirty = false
2356:         pendingSnapshot = null
2357: 
2358:         val previousApplied = 
2359:             lastAppliedSnapshot
```

### `previousReliable`:
**NO**, no hay lecturas de `previousReliable` dentro de la Etapa 2.

---

## KK4. Declaración de la variable `snapshot`

La variable `snapshot` usada en la Etapa 2 y en la asignación `lastAppliedSnapshot = snapshot` se define en la Stage 1 (Línea 2057):

```kotlin
2052:         // --- PURGA DE TRANSICIÓN Y PROTECCIÓN DE HERENCIA (v9.0: Sede única en session) ---
2053:         // Bloqueamos la herencia de marcas de agua (maxPositionMs) y assets si el título cambia.
2054:         // Si el título entrante es nulo o distinto, el snapshot nace desde cero.
2055:         val canInheritAssets = isSameSession && rawSnapshot.title == session?.identity?.title && rawSnapshot.title.isNotBlank()
2056: 
2057:         val snapshot = rawSnapshot.copy(
2058:             firstObservedAt = firstObservedAt,
2059:             artworkSource = if (canInheritAssets) (session?.birthSnapshot?.artworkSource ?: rawSnapshot.artworkSource) else rawSnapshot.artworkSource
2060:         )
```

**Nota:** `snapshot` es una copia enriquecida de `rawSnapshot` que preserva el momento de inicio de la sesión y la fuente de artwork original. La línea `lastAppliedSnapshot = snapshot` (ahora en la línea 2215) captura este estado procesado.
