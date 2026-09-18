# Auditoría — Ronda 2 (Identidad): Confirmación Completa Antes del Barrido

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AQ1. ¿Son dos verificaciones distintas de "conservar letra", o la misma?

Son dos bloques de lógica casi idénticos pero con propósitos técnicos distintos (uno para el gating de ahorro de batería y otro para el compromiso final en disco).

### Bloque 1: Gating de Stage 1 (Líneas 2313 - 2316)
Este bloque se ejecuta cuando el widget **no es visible**. Su objetivo es decidir qué letra guardar en el "diario lógico" mientras la pantalla está apagada.

```kotlin
2313:                 val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
2314:                 
2315:                 val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2316:                 val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
```
**Estado:** Usa `trackKey` (física), por lo que un cambio de duración descarta la letra.

### Bloque 2: Compromiso de Stage 2 (Líneas 2546 - 2553)
Este bloque se ejecuta cuando el widget **es visible**. Su objetivo es el compromiso atómico de metadatos finales. Ya fue corregido con `sessionIdentity`.

```kotlin
2546:                     val canKeepLyric = snapshot.isSessionActive &&
2547:                         currentInfo.lyricsTrackKey.isNotBlank() &&
2548:                         MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
2549:                             MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
2550:                     
2551:                     val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2552:                     val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
```
**Estado:** Usa la identidad de negocio (`packageName|title|artist`). Conserva la letra incluso si el `trackKey` (duración) cambia.

---

## AQ2. Todos los usos de `trackKey` como texto (nombres/claves)

### 1. Nombramiento de Caché de Glance (`MusicWidget.kt`)
```kotlin
501:         val cacheKey = "hist_${item.trackKey}_${item.timestamp}"
```
**Propósito:** Clave única para la caché de bitmaps del historial. Usa `trackKey` + `timestamp` para evitar colisiones si la misma canción se repite.

### 2. Logging y Debugging (`MusicNotificationListener.kt`)
```kotlin
1090:         Log.d("GLANCE_REFRESH", "Portada reconciliada para ${item.trackKey}. Solicitando updateAll a Glance...")
```
**Propósito:** Trazabilidad en consola.

### 3. Persistencia en DataStore (`MusicDataStore.kt`)
```kotlin
803:             prefs[TRACK_KEY] = info.trackKey
815:             prefs[LYRICS_TRACK_KEY] = info.lyricsTrackKey
```
**Propósito:** Guarda la clave como cadena de texto en SharedPreferences (Jetpack DataStore).

### 4. Construcción de Clave de Resumen (`MusicNotificationListener.kt`)
```kotlin
531:         val contentKey: String get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"
```
**Propósito:** Identidad de ráfaga para deduplicación.

---

## AQ3. Contexto completo de `reconcileArtwork` y `reconcileLyric`

Ubicado en `MusicStateProvider.kt` (Líneas 98 - 116).

```kotlin
98:     private fun reconcileArtwork(current: MusicInfo, e: MusicUpdateEvent.ArtworkResolved): MusicInfo {
99:         if (e.trackKey != current.trackKey) return current
100:         
101:         return current.copy(
102:             artworkKey = e.artworkKey,
103:             appIconKey = e.iconKey ?: current.appIconKey
104:         )
105:     }
106: 
107:     private fun reconcileLyric(current: MusicInfo, e: MusicUpdateEvent.LyricTick): MusicInfo {
108:         if (e.trackKey != current.trackKey) return current
109:         
110:         return current.copy(
111:             currentLyric = e.lyric,
112:             lyricsTrackKey = e.trackKey
113:         )
114:     }
```

---

## AQ4. Código completo de la condición que dispara la resolución de portada

Ubicado en `MusicNotificationListener.kt` (Líneas 2392 - 2404).

```kotlin
2392:             // REGLA Artwork-1: Resolución dirigida por incoherencia (evita ráfagas CPU)
2393:             if (controller != null && metadata != null && artIncoherent) {
2394:                 
2395:                 // A. Portada (v6.3 Pipeline Unificado)
2396:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2397:                     resolveArtworkDeduplicated(
2398:                         snapshot = snapshot,
2399:                         controller = controller,
2400:                         metadata = metadata,
2401:                         generation = myGeneration
2402:                     )
2403:                 } ?: run {
2404:                     artworkTimedOut = true
```

---

## AQ5. ¿Cómo se llama a la búsqueda de letras, y qué parámetros usa exactamente?

Ubicado en `MusicNotificationListener.kt` (Línea 2446).

### Llamada síncrona:
```kotlin
2446:                     val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
```

### Definición en `LyricsRepository.kt`:
```kotlin
20: suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? {
...
32:     // 2. Si no hay o TTL expiró, ir a red
33:     val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
```

**Análisis de parámetros:**
- Se pasa el `trackKey` completo como identificador de caché local (Room).
- Se pasa la **duración** (`durationMs`).
- La función interna de red (`fetchFromNetwork`) **SÍ utiliza la duración** (convertida a segundos) para la búsqueda en la API externa. Esto significa que si la duración cambia (ej. de 180s a 181s), el sistema intentará una búsqueda distinta en la red, lo cual es correcto para canciones con versiones de diferente longitud (ej. Single vs Album version).
