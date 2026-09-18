# Auditoría — Ronda 4 (Identidad): Barrido Completo de `trackKey`, y Cuerpo Real de `fetchFromNetwork`

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AS1. Cada aparición de `trackKey` en todo el proyecto

**Comando ejecutado:** `grep -rn "trackKey" . --include="*.kt"`

**Resultados completos (Línea por línea):**

```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 7: @Query("SELECT * FROM lyrics_cache WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 7: @Query("SELECT * FROM lyrics_cache WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 8: suspend fun getLyrics(trackKey: String): LyricsEntity?
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 13: @Query("UPDATE lyrics_cache SET lastAccessed = :timestamp WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 13: @Query("UPDATE lyrics_cache SET lastAccessed = :timestamp WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 14: suspend fun updateLastAccessed(trackKey: String, timestamp: Long)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 19: @Query("DELETE FROM lyrics_cache WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 19: @Query("DELETE FROM lyrics_cache WHERE trackKey = :trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt (732B): 20: suspend fun deleteLyrics(trackKey: String)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsEntity.kt (408B): 8: @PrimaryKey val trackKey: String,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 15: data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 20: suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResu...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 22: val cached = lyricsDao.getLyrics(trackKey)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 31: lyricsDao.updateLastAccessed(trackKey, now)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 32: return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 43: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 43: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 51: return@withContext parseLrc(trackKey, networkResult, durationMs)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 55: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 55: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 98: private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 99: return parseLrc(trackKey, lrc, durationMs)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 102: fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt (5KB): 120: return LyricsResult(trackKey, allEntries)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 35: val trackKey: String = "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 82: val lyricsTrackKey: String = "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 172: val isEmpty: Boolean get() = trackKey.isBlank()
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 190: trackKey = "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 197: lyricsTrackKey = ""
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 213: val trackKey: String,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 233: val canonicalTrackKey: String
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 470: trackKey =
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 514: lyricsTrackKey =
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 631: trackKey = obj.optString("tk", ""),
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 647: trackKey = item.canonicalTrackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 647: trackKey = item.canonicalTrackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 668: obj.put("tk", item.trackKey)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 711: val currentTrackKey =
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 739: val currentLyricsTrackKey =
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 763: currentTrackKey != info.trackKey ||
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 763: currentTrackKey != info.trackKey ||
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 783: currentLyricsTrackKey != info.lyricsTrackKey ||
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 783: currentLyricsTrackKey != info.lyricsTrackKey ||
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 803: prefs[TRACK_KEY] = info.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 815: prefs[LYRICS_TRACK_KEY] = info.lyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 886: suspend fun updateHistoryItemArtworkStatus(trackKey: String, timestamp: Long, isPending: Boolean) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 892: if (item.trackKey == trackKey && item.timestamp == timestamp) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 892: if (item.trackKey == trackKey && item.timestamp == timestamp) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 894: Log.d("DATASTORE_MUTATION", "Actualizando URI de portada para trackKey: $trackKey -> Nueva URI: $localUri (isPending: $isPending)")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 894: Log.d("DATASTORE_MUTATION", "Actualizando URI de portada para trackKey: $trackKey -> Nueva URI: $localUri (isPending: $isPending)")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 1066: suspend fun updateLyricsOnly(lyric: String, trackKey: String): Boolean {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 1069: // Solo escribimos si el trackKey coincide y la letra cambió para evitar recomposiciones innecesaria...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 1072: if (currentTrack == trackKey && currentLyric != lyric) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt (40.9KB): 1074: prefs[LYRICS_TRACK_KEY] = trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 179: val frozenTrackKey: String,       // Identidad física congelada al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 229: data class IdentityChange(val trackKey: String) : UpdateEvent()
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 502: val trackKey: String
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 507: * album-aware key (not [trackKey]) specifically so that different album editions of the
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 508: * same song can carry different artwork. Do not change this fallback to use [trackKey] —
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 531: get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 681: lastProcessedTrack = last.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 791: val trackKey = endSnapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 791: val trackKey = endSnapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 812: Log.w(TAG, "[SHADOW_OBSERVER] Fallo rescate de artwork: $trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 851: val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 851: val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 862: if (trackKey == lastProcessedTrack && outcome == lastProcessedOutcome && sessionUUID == lastProcesse...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 863: lastProcessedTrack = trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 879: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 879: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1022: it.artworkKey == artworkKey && it.hasPendingArtwork 
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1089: musicDataStore.updateHistoryItemArtworkStatus(item.trackKey, item.timestamp, false)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1090: Log.d("GLANCE_REFRESH", "Portada reconciliada para ${item.trackKey}. Solicitando updateAll a Glance...")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1141: if (currentInfo.trackKey.isNotEmpty()) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1154: frozenTrackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1154: frozenTrackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1263: trackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1263: trackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 1746: val trackKeyStr = "$title|$artist|$duration"
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2184: frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2184: frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2265: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2265: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2265: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2272: lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2272: lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2290: MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2313: val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2313: val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2316: val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2322: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2322: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2322: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2329: lyricsTrackKey = finalLyricKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2369: previousLogical?.trackKey != snapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2369: previousLogical?.trackKey != snapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2372: // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2446: val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2453: updateLyricInWidget(snapshot.trackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2457: // La canción de negocio es la misma (solo se afinó trackKey, ej. duración tardía).
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2463: currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snaps...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2548: currentInfo.lyricsTrackKey.isNotBlank() &&
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2553: val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2562: } else if (snapshot.trackKey == currentInfo.trackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2562: } else if (snapshot.trackKey == currentInfo.trackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2579: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2579: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2579: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2586: lyricsTrackKey = finalLyricKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2616: uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2657: currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2664: runLyricsShowcase(currentInfo.trackKey, lyricsRes)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2666: runPausedLyricsCycle(currentInfo.trackKey, lyricsRes)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2671: private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2677: if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2677: if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2690: updateLyricInWidget(myTrackKey, entry.text)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2704: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2704: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2705: updateLyricInWidget(myTrackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2715: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2715: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2716: updateLyricInWidget(myTrackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2725: private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2729: if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2729: if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2739: updateLyricInWidget(myTrackKey, text)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2746: private fun updateLyricInWidget(trackKey: String, lyric: String) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (150KB): 2749: if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 57: val sessionChanged = current.trackKey != e.info.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 57: val sessionChanged = current.trackKey != e.info.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 66: // Identidad de negocio (sessionIdentity, no trackKey) para decidir si la letra actual
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 68: // trackKey cambió por una corrección tardía de duración (Conjunto Letras-2).
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 74: lyricsTrackKey = if (lyricBelongsToSameSong) e.info.trackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 74: lyricsTrackKey = if (lyricBelongsToSameSong) e.info.trackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 84: // etiquetada, se re-etiqueta con el trackKey nuevo para que no quede huérfana
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 86: val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyri...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 86: val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyri...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 86: val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyri...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 86: val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyri...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 89: trackKey = e.newTrackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 89: trackKey = e.newTrackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 94: lyricsTrackKey = updatedLyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 94: lyricsTrackKey = updatedLyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 99: if (e.trackKey != current.trackKey) return current
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 99: if (e.trackKey != current.trackKey) return current
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 108: if (e.trackKey != current.trackKey) return current
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 108: if (e.trackKey != current.trackKey) return current
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 112: lyricsTrackKey = e.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 112: lyricsTrackKey = e.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 145: data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: L...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 146: data class ArtworkResolved(val trackKey: String, val artworkKey: String, val iconKey: String? = null...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt (7.2KB): 147: data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt (52.4KB): 461: List(4) { HistoryItem(title = "", artist = "", album = "", durationMs = 0L, packageName = "", artwor...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt (52.4KB): 501: val cacheKey = "hist_${item.trackKey}_${item.timestamp}"
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt (52.4KB): 731: info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.l...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt (52.4KB): 731: info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.l...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt (52.4KB): 880: trackKey = if (isEmptyMock) "" else "mock_key",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 179: val frozenTrackKey: String,       // Identidad f├¡sica congelada al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 229: data class IdentityChange(val trackKey: String) : UpdateEvent()
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 501: val trackKey: String
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 506: * album-aware key (not [trackKey]) specifically so that different album editions of the
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 507: * same song can carry different artwork. Do not change this fallback to use [trackKey] ΓÇö
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 530: get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 680: lastProcessedTrack = last.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 790: val trackKey = endSnapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 790: val trackKey = endSnapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 811: Log.w(TAG, "[SHADOW_OBSERVER] Fallo rescate de artwork: $trackKey")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 850: val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 850: val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 861: if (trackKey == lastProcessedTrack && outcome == lastProcessedOutcome && sessionUUID == lastProcesse...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 862: lastProcessedTrack = trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 878: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 878: trackKey = trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1021: pendingItem.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1088: musicDataStore.updateHistoryItemArtworkStatus(item.trackKey, item.timestamp, false)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1089: Log.d("GLANCE_REFRESH", "Portada reconciliada para ${item.trackKey}. Solicitando updateAll a Glance....
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1140: if (currentInfo.trackKey.isNotEmpty()) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1153: frozenTrackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1153: frozenTrackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1262: trackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1262: trackKey = currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 1741: val trackKeyStr = "$title|$artist|$duration"
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2174: frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2174: frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2251: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2251: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2251: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2258: lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2258: lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2276: MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isP...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2299: val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2299: val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2302: val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2308: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2308: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2308: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2315: lyricsTrackKey = finalLyricKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2358: previousApplied?.trackKey != snapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2358: previousApplied?.trackKey != snapshot.trackKey
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2423: val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2430: updateLyricInWidget(snapshot.trackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2436: currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snaps...
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2520: currentInfo.lyricsTrackKey.isNotBlank() &&
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2525: val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2534: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2534: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2534: trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad f├¡sica congelada (v6.5)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2541: lyricsTrackKey = finalLyricKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2570: uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2612: currentInfo.trackKey,
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2619: runLyricsShowcase(currentInfo.trackKey, lyricsRes)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2621: runPausedLyricsCycle(currentInfo.trackKey, lyricsRes)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2626: private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2632: if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2632: if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2645: updateLyricInWidget(myTrackKey, entry.text)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2659: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2659: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2660: updateLyricInWidget(myTrackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2670: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2670: if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2671: updateLyricInWidget(myTrackKey, "")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2680: private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2684: if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2684: if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2694: updateLyricInWidget(myTrackKey, text)
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2701: private fun updateLyricInWidget(trackKey: String, lyric: String) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt (291KB): 2704: if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
```

---

## AS2. Código completo de `fetchFromNetwork`

Ubicado en `LyricsRepository.kt` (Líneas 71 - 96).

```kotlin
71:     private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
72:         var connection: HttpURLConnection? = null
73:         try {
74:             val cleanArtist = URLEncoder.encode(normalizeForSearch(artist), "UTF-8")
75:             val cleanTitle = URLEncoder.encode(normalizeForSearch(title), "UTF-8")
76:             val durationParam = if (durationSec > 0) "&duration=$durationSec" else ""
77:             val urlString = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle$durationParam"
78:             
79:             connection = URL(urlString).openConnection() as HttpURLConnection
80:             connection.requestMethod = "GET"
81:             connection.connectTimeout = 5000
82:             connection.readTimeout = 5000
83:             connection.setRequestProperty("User-Agent", "MusicWidgetAndroidApp (https://github.com/arenliel/musicwidget)")
84:             
85:             if (connection.responseCode == 200) {
86:                 val response = connection.inputStream.bufferedReader().use { it.readText() }
87:                 val json = JSONObject(response)
88:                 return@withContext json.optString("syncedLyrics").takeIf { it.isNotBlank() }
89:             }
90:         } catch (e: Exception) {
91:             Log.e("LyricsRepo", "Error fetching lyrics", e)
92:         } finally {
93:             connection?.disconnect()
94:         }
95:         null
96:     }
```

**Análisis del uso de la duración:**
- El parámetro `durationSec` (que llega como `durationMs / 1000` desde el llamador) se utiliza para construir la variable `durationParam` (Línea 76).
- Si la duración es mayor a cero, se añade a la URL como un parámetro de consulta obligatorio (`&duration=...`).
- La API de `lrclib.net` utiliza este parámetro para garantizar que la letra devuelta coincida exactamente con la longitud de la canción, permitiendo diferenciar entre versiones (ej. Radio Edit vs Album Version).
- El sistema no elige entre varios resultados después de la respuesta; la API misma realiza el filtrado basándose en los parámetros de la URL.
