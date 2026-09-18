# Auditoría — Ronda 1 (Identidad): Mapa Completo de Usos de `trackKey` en Decisiones de Cambio

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AP1. Todos los usos de `.trackKey` en comparaciones (`==` o `!=`)

### 1. `MusicDataStore.kt`
Ubicado en `updateHistoryItemArtworkStatus` (Línea 892).

```kotlin
885:     suspend fun updateHistoryItemArtworkStatus(trackKey: String, timestamp: Long, isPending: Boolean) {
886:         context.dataStore.edit { prefs ->
...
891:             val newHistory = oldHistory.map { item ->
892:                 if (item.trackKey == trackKey && item.timestamp == timestamp) {
893:                     val localUri = if (!isPending) Uri.fromFile(java.io.File(item.artworkPath)).toString() else item.artworkUri
```
**Propósito:** Identificación física de un ítem del historial para actualizar su portada una vez resuelta. No decide cambios de sesión.

---

### 2. `MusicNotificationListener.kt`

#### A. Identificación de "Canción Bendecida" (Línea 851)
Ubicado en `shouldSuppressNewSession` (o bloque de validación de skip).

```kotlin
850:             val currentRAM = MusicStateProvider.current()
851:             val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
852:             if (isBlessed && isSkipped) isSkipped = false
```
**Propósito:** Decisión de negocio. Inmuniza una canción contra ser marcada como "saltada" si ya fue escuchada con éxito previamente.

#### B. Gating de Letras en Stage 1 (Línea 2313)
Ubicado en `processSnapshot` (Stage 1).

```kotlin
2312:                 val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
2313:                 val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
2314:                 
2315:                 val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
```
**Propósito:** Preservación de estado. Decide si se puede mantener la letra actual en la base de datos durante el gating de pantalla apagada.

#### C. Detección de Cambio de Pista en Stage 2 (Línea 2369)
Ubicado en `processSnapshot` (Stage 2).

```kotlin
2368:         val trackChangedUI = 
2369:             previousLogical?.trackKey != snapshot.trackKey
```
**Propósito:** Decisión visual. Es el disparador principal para relanzar la resolución de portada y la búsqueda de letras.

#### D. Respaldo de URI de Portada (Línea 2562)
Ubicado en `processSnapshot` (Stage 2 - Compromiso de Identidad).

```kotlin
2560:                         val uri = if (resolvedArtwork != null && identityGenerationCounter == genAlIniciarResolucion && currentUUID != null) {
2561:                             ArtworkStorageManager.saveHistoryArtwork(applicationContext, resolvedArtwork, currentUUID)
2562:                         } else if (snapshot.trackKey == currentInfo.trackKey) {
2563:                             currentInfo.artworkUri
2564:                         } else {
```
**Propósito:** Conservación de metadatos. Decide si se hereda la URI anterior cuando la resolución actual falla pero la canción es la misma.

#### E. Zombie Detector de Letras (Línea 2677)
Ubicado en `runLyricsShowcase` (Ticker de letras).

```kotlin
2675:         while (currentCoroutineContext().isActive) {
2676:             val currentRAM = MusicStateProvider.current()
2677:             if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
2678:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] Zombie Detector: Clave discordante. Cancelando Ticker.")
```
**Propósito:** Ciclo de vida. Canciona la corrutina de letras si la canción en RAM ya no coincide con la que inició el ticker.

#### F. Verificación de Relevancia de Letras (Líneas 2704 y 2715)
Ubicado en `runLyricsShowcase`.

```kotlin
2703:                 if (waitTime > LYRICS_SILENCE_THRESHOLD_MS) {
2704:                     if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
2705:                         updateLyricInWidget(myTrackKey, "")
```
**Propósito:** Integridad. Asegura que solo se limpie el widget si la canción sigue siendo la misma tras el retardo del umbral de silencio.

---

### 3. `MusicStateProvider.kt`

#### A. Detección de Cambio de Sesión (Línea 57)
Ubicado en `reconcileNewSession`.

```kotlin
56:     private fun reconcileNewSession(current: MusicInfo, e: MusicUpdateEvent.NewSession): MusicInfo {
57:         val sessionChanged = current.trackKey != e.info.trackKey
58:         val stableHistory = if (!sessionChanged && e.info.history == current.history) {
```
**Propósito:** Decisión estructural. Determina si el reloj de tiempo relativo debe resetearse y si el historial debe estabilizarse.

#### B. Reconciliación de Refinamiento y Letras (Líneas 99 y 108)
Ubicado en `reconcileArtwork` y `reconcileLyric`.

```kotlin
98:     private fun reconcileArtwork(current: MusicInfo, e: MusicUpdateEvent.ArtworkResolved): MusicInfo {
99:         if (e.trackKey != current.trackKey) return current
...
107:     private fun reconcileLyric(current: MusicInfo, e: MusicUpdateEvent.LyricTick): MusicInfo {
108:         if (e.trackKey != current.trackKey) return current
```
**Propósito:** Filtro de atribución. Ignora eventos de actualización que lleguen tarde para una canción que ya no es la actual en el proveedor de estado.

---

### 4. `MusicWidget.kt`
Ubicado en `TextInfo` (Línea 731).

```kotlin
730:                     info.title == context.getString(R.string.widget_empty_title) -> info.artist
731:                     isSnapshot && !isStatusLabelVisible -> { val time = formatRelativeTime(context, info.lastUpdateEpoch); if (time.isEmpty()) info.artist else "${info.artist} • $time" }
732:                     info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> "“${info.currentLyric}”"
733:                     else -> info.artist
```
**Propósito:** Presentación. Decide si se muestra la línea de letras basándose en si la clave de la letra coincide con la clave de la pista actual del widget.

---

## AP2. Confirmación de `reconcileNewSession`

Ubicado en `MusicStateProvider.kt` (Líneas 56 - 63).

```kotlin
56:     private fun reconcileNewSession(current: MusicInfo, e: MusicUpdateEvent.NewSession): MusicInfo {
57:         val sessionChanged = current.trackKey != e.info.trackKey
58:         val stableHistory = if (!sessionChanged && e.info.history == current.history) {
59:             current.history
60:         } else {
61:             e.info.history
62:         }
63: 
64:         val shouldResetClock = sessionChanged || e.info.isPlaying
```

**Verbatim de la decisión:**
`val sessionChanged = current.trackKey != e.info.trackKey`
`val shouldResetClock = sessionChanged || e.info.isPlaying`

**Efecto:** Si `trackKey` es idéntico, `sessionChanged` es falso y el historial se estabiliza (referencia física conservada). El reloj de pausa (`lastUpdateEpoch`) solo se resetea si la clave cambia o si la canción está reproduciéndose.
