# Auditoría — Ronda 20: Todos los Usos de `previousApplied`

**Confirmación de Git Log:**
```
34fc859 (HEAD -> master) Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
```

---

## NN1. Todos los usos de `previousApplied` en todo el proyecto

Ubicados en `MusicNotificationListener.kt`. No se encontraron usos en otros archivos `.kt`.

### 1. Declaración (Línea 2358)
```kotlin
2355:         isPresentationDirty = false
2356:         pendingSnapshot = null
2357: 
2358:         val previousApplied = 
2359:             lastAppliedSnapshot
```

### 2. Uso en `trackChangedUI` (Línea 2362)
```kotlin
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
```

### 3. Uso en `songChangedForLyrics` (Líneas 2367-2368)
```kotlin
2366:         // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
2367:         val songChangedForLyrics = previousApplied == null ||
2368:             MusicDataStore.computeSessionIdentity(previousApplied.packageName, previousApplied.title, previousApplied.artist) !=
2369:                 MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
```

### 4. Uso en `appChangedUI` (Línea 2372)
```kotlin
2371:         val appChangedUI = 
2372:             previousApplied?.packageName != snapshot.packageName
```

### 5. Uso en `stateChangedUI` (Línea 2375)
```kotlin
2374:         val stateChangedUI = 
2375:             previousApplied?.playbackState != snapshot.playbackState
```

### 6. Uso en `artworkChangedUI` (Línea 2378)
```kotlin
2377:         val artworkChangedUI =
2378:             previousApplied?.artworkKey != snapshot.artworkKey
```

### 7. Uso en cálculo de desvío de letras (Línea 2454)
```kotlin
2452:                 }
2453: 
2454:                 val effectivePos = previousApplied?.projectedPositionMs() ?: 0L
2455:                 val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())
```

---

## NN2. Contexto completo del bloque donde vive `artworkChangedUI`

(Líneas 2358 - 2380 de `MusicNotificationListener.kt`):

```kotlin
2358:         val previousApplied = 
2359:             lastAppliedSnapshot
2360: 
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
2363: 
2364:         // Identidad de negocio para decidir si hace falta re-buscar la letra (Conjunto Letras-3).
2365:         // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
2366:         // cambió por una corrección tardía de duración.
2367:         val songChangedForLyrics = previousApplied == null ||
2368:             MusicDataStore.computeSessionIdentity(previousApplied.packageName, previousApplied.title, previousApplied.artist) !=
2369:                 MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
2370: 
2371:         val appChangedUI = 
2372:             previousApplied?.packageName != snapshot.packageName
2373: 
2374:         val stateChangedUI = 
2375:             previousApplied?.playbackState != snapshot.playbackState
2376: 
2377:         val artworkChangedUI =
2378:             previousApplied?.artworkKey != snapshot.artworkKey
2379: 
2380:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")
```
