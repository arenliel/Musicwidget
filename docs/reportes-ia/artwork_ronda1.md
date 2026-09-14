# Auditoría — Ronda 1: Mapa Completo del Subsistema de Resolución de Portadas

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## UU1. ¿Existe algún mecanismo de reintento para portadas?

**Búsqueda literal:** `retry`, `backoff`, `reintent`, `intentosFallidos`.

**Resultado:** No hay ninguna coincidencia para ninguno de estos términos en el proyecto completo. No existe un mecanismo de reintento programado con re-intentos explícitos o backoff. Como se observó en la Ronda 25, el "reintento" es un efecto colateral de dejar `savedArtworkKey` en `null` si la resolución falla, lo que provoca que la siguiente ráfaga de la misma canción vuelva a entrar en el bloque de resolución si el widget es visible.

---

## UU2. Código completo de `findRealAlbumArt`

Ubicado en `MusicNotificationListener.kt` (Líneas 2907 - 2977).

```kotlin
2907:     private suspend fun findRealAlbumArt(
2908:         snapshot: MediaSnapshot,
2909:         controller: MediaController?,
2910:         metadata: MediaMetadata?
2911:     ): Bitmap? = withContext(Dispatchers.IO) {
2912:         val minArtDimension = MIN_ART_DIMENSION
2913:         val targetTitle = snapshot.title
2914:         val artworkKey = snapshot.artworkKey
2915:         
2916:         InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] findRealAlbumArt START: $targetTitle")
2917:         
2918:         // 1. Intentar desde metadatos vivos (solo si el título coincide)
2919:         metadata?.let { meta ->
2920:             val metaTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
2921:             if (metaTitle == targetTitle) {
2922:                 meta.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { bitmap ->
2923:                     if (isValidArtwork(bitmap, minArtDimension)) {
2924:                         InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de METADATA_KEY_ART")
2925:                         return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
2926:                     }
2927:                 }
2928:                 meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { bitmap ->
2929:                     if (isValidArtwork(bitmap, minArtDimension)) {
2930:                         InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de METADATA_KEY_ALBUM_ART")
2931:                         return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
2932:                     }
2933:                 }
2934:             }
2935:         }
2936: 
2937:         // 2. Intentar desde notificaciones activas
2938:         try {
2939:             val notifications = getActiveNotifications()
2940:             val mediaNotification = notifications.firstOrNull { sbn ->
2941:                 sbn.packageName == snapshot.packageName && 
2942:                 sbn.notification.category == Notification.CATEGORY_TRANSPORT &&
2943:                 sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == targetTitle
2944:             }
2945:             if (mediaNotification != null) {
2946:                 mediaNotification.notification.getLargeIcon()?.loadDrawable(this@MusicNotificationListener)?.toBitmap()?.let {
2947:                     if (isValidArtwork(it, minArtDimension)) {
2948:                         InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de NOTIFICACIÓN (LargeIcon)")
2949:                         return@withContext ensureMaxDimension(it, MAX_ART_DIMENSION)
2950:                     }
2951:                 }
2952:             }
2953:         } catch (e: Exception) {
2954:             Log.e(TAG, "Fallo consultando notificación activa", e)
2955:         }
2956: 
2957:         // 3. Resolución asíncrona de URI
2958:         snapshot.artworkUri?.takeIf { it.isNotBlank() }?.let { uri ->
2959:             decodeAlbumArtUri(uri)?.let { bitmap ->
2960:                 if (isValidArtwork(bitmap, minArtDimension)) {
2961:                     InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de URI: $uri")
2962:                     return@withContext bitmap
2963:                 }
2964:             }
2965:         }
2966: 
2967:         // 4. FALLBACK v6.3: Bóveda de Reserva (Fase A / Rehydration)
2968:         // Usamos CoreKey para recuperar el Bitmap independientemente del refinamiento de duración.
2969:         val myCoreKey = snapshot.coreKey
2970:         val fallbackBitmap = memoryArtworkCache[myCoreKey]
2971:         
2972:         fallbackBitmap?.let { bitmap ->
2973:             if (isValidArtwork(bitmap, minArtDimension)) {
2974:                 InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de BÓVEDA DE RESERVA (CoreKey: $myCoreKey)")
2975:                 return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
2976:             }
2977:         }
2978: 
2979:         null
2980:     }
```

---

## UU3. Código completo de ambas funciones de `[ART_LIFECYCLE]`

Ambos logs se producen dentro de la función `createSnapshot` (Líneas 1676 - 1814).

### Función: `createSnapshot` (PARTE 1 de 1)

```kotlin
1676:     private fun createSnapshot(
1677:         controller: MediaController,
1678:         metadata: MediaMetadata
1679:     ): MediaSnapshot? {
... (omitiendo extracción de metadatos básicos por brevedad, pero cubriendo la lógica de vida del artwork)
1740:         val trackKeyStr = "$title|$artist|$duration"
1741:         val myCoreKey = "$title|$artist".trim().lowercase()
1742: 
1743:         // FASE A: Captura Inmediata (Segundo 0)
1744:         // Intentamos extraer y clonar el bitmap del sistema mientras está fresco.
1745:         // v5.2.3: Se usa CoreKey como índice. UI-Only (Disk Shield). No toca el historial.
1746:         metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { original ->
1747:             if (!memoryArtworkCache.containsKey(myCoreKey)) {
1748:                 runCatching {
1749:                     val clone = original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
1750:                     memoryArtworkCache[myCoreKey] = clone
1751:                     
1752:                     // DISK SHIELD (v5.1): Persistencia inmediata para Glance
1753:                     serviceScope.launch(Dispatchers.IO) {
1754:                         saveBitmapToDiskShield(clone)
1755:                     }
1756: 
1757:                     // RETOQUE ATÓMICO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
1758:                     currentLogicalSession?.let { session ->
1759:                         if (session.identity.title == sanitize(title) && session.identity.artist == sanitize(artist)) {
1760:                             serviceScope.launch(Dispatchers.IO) {
1761:                                 val historyDir = File(filesDir, "history")
1762:                                 if (!historyDir.exists()) historyDir.mkdirs()
1763:                                 val artworkFile = File(historyDir, "art_${session.sessionUUID}.webp")
1764:                                 val tempFile = File(historyDir, "art_${session.sessionUUID}.tmp")
1765:                                 
1766:                                 try {
1767:                                     val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
1768:                                         Bitmap.CompressFormat.WEBP_LOSSY
1769:                                     } else {
1770:                                         @Suppress("DEPRECATION")
1771:                                         Bitmap.CompressFormat.WEBP
1772:                                     }
1773:                                     FileOutputStream(tempFile).use { out ->
1774:                                         if (clone.compress(format, 80, out)) {
1775:                                             out.flush()
1776:                                             Files.move(tempFile.toPath(), artworkFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
1777:                                             InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Retoque Atómico: Portada persistida directamente (UUID=${session.sessionUUID})")
1778:                                         }
1779:                                     }
1780:                                 } catch (e: Exception) {
1781:                                     Log.e(TAG, "Error en retoque atómico directo", e)
1782:                                 } finally {
1783:                                     if (tempFile.exists()) tempFile.delete()
1784:                                 }
1785:                             }
1786:                         }
1787:                     }
1788:                     
1789:                     InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para $title")
1790:                 }
1791:             }
1792:         }
...
1814:     }
```

---

## UU4. Código completo del mecanismo "Artwork ya está en vuelo"

Ubicado en `getOrCreateArtworkDeferred` (Líneas 2862 - 2905).

```kotlin
2862:     private suspend fun getOrCreateArtworkDeferred(
2863:         snapshot: MediaSnapshot,
2864:         controller: MediaController?,
2865:         metadata: MediaMetadata?,
2866:         generation: Long
2867:     ): Deferred<Bitmap?> {
2868:         val artworkKey = snapshot.artworkKey
2869:         artworkInFlightMutex.withLock {
2870:             artworkCache.get(artworkKey)?.let { bitmap ->
2871:                 return CompletableDeferred(bitmap)
2872:             }
2873:             artworkInFlight[artworkKey]?.let { existing ->
2874:                 if (existing.isActive) {
2875:                     Log.d(TAG, "Artwork ya está en vuelo; reutilizando Deferred: $artworkKey")
2876:                     return existing
2877:                 }
2878:                 artworkInFlight.remove(artworkKey)
2879:             }
2880:             val deferred = serviceScope.async {
2881:                 try {
2882:                     val bitmap = findRealAlbumArt(snapshot, controller, metadata)
...
2905:         }
2906:     }
```

---

## UU5. Todos los usos de `memoryArtworkCache`

Ubicado en `MusicNotificationListener.kt`.

1. **Declaración (Línea 133):** `private val memoryArtworkCache = ConcurrentHashMap<String, Bitmap>()`
2. **Purga en cambio de track (Línea 1981):** `memoryArtworkCache.keys.retainAll(setOf(myCoreKey))`. Clave: `myCoreKey` (title|artist).
3. **Escritura en Fase A (Línea 1750):** `memoryArtworkCache[myCoreKey] = clone`. Clave: `myCoreKey`.
4. **Lectura en `findRealAlbumArt` (Línea 2970):** `val fallbackBitmap = memoryArtworkCache[myCoreKey]`. Clave: `myCoreKey`.
5. **Lectura en `commitToHistory` (Línea 799):** `var memoryBitmap = memoryArtworkCache[myCoreKey]`. Clave: `myCoreKey`.
6. **Lectura en `refreshBestSession` (Línea 1519):** `!memoryArtworkCache.containsKey(last.coreKey)`. Clave: `last.coreKey`.
7. **Escritura en rehidratación de Disk Shield (Líneas 1180 y 1523):** `memoryArtworkCache[coreKey] = bitmap`. Clave: `coreKey`.

---

## UU6. Constante de tiempo de espera para la descarga de portada

Ubicada en el `companion object` de `MusicNotificationListener.kt`.

```kotlin
3244:         private const val ARTWORK_TIMEOUT_MS = 7000L
3245:         private const val ARTWORK_PROMOTION_TIMEOUT_MS = 3500L
```

---

## UU7. Variable de relevancia temporal mencionada en la documentación del proyecto

**Búsqueda literal:** `lastMetadataTimestamp`.

**Resultado:** No existe ninguna variable con ese nombre exacto. El proyecto utiliza `observedAtRealtime` (Línea 245) y `lastUpdateEpoch` (Línea 307) para la gestión temporal del estado.

---

## UU8. Todos los usos de `artworkTimedOut`

Ubicada en `processSnapshot`.

1. **Declaración (Línea 2391):** `var artworkTimedOut = false`
2. **Escritura (Línea 2405):** `artworkTimedOut = true` (dentro del `run` tras el `withTimeoutOrNull`).
3. **Lectura para refresco forzado (Línea 2569):** `forceUpdate = isCatchUp || artIncoherent || artworkTimedOut`
4. **Lectura para log (Línea 2571):** `if (artworkTimedOut) { Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). ...") }`

---

## UU9. ¿La reactivación de pantalla (`catch_up_render`) relanza `findRealAlbumArt`, o solo reescribe el valor ya existente?

**SOLO REESCRIBE.**

Analizando `processSnapshot`:
```kotlin
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
```
La condición para llamar a `resolveArtworkDeduplicated` (que invoca a `findRealAlbumArt`) **no incluye** el flag `isCatchUp`. Si la pantalla se apaga y se vuelve a encender para la misma canción, y la resolución ya había tenido éxito (o el placeholder ya tenía llave), el bloque de resolución se salta.

Sin embargo, `isCatchUp` sí fuerza el guardado en disco:
```kotlin
2569:                     val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || ...)
```

---

## UU10. Confirmación de los tres niveles de búsqueda descritos en la documentación

Dentro de `findRealAlbumArt` (UU2), el orden es:

1.  **NIVEL 1: Metadatos vivos** (Línea 2919). Intenta `METADATA_KEY_ART` primero, luego `METADATA_KEY_ALBUM_ART`.
2.  **NIVEL 2: Notificación activa** (Línea 2938). Extrae el `LargeIcon` usando `mediaNotification.notification.getLargeIcon()`.
3.  **NIVEL 3: URI de portada** (Línea 2958). Incluye traducción de Spotify (`content://com.spotify.mobile.android.mediaapi`) a CDN (`https://i.scdn.co/image/`).
4.  **FALLBACK: Bóveda de reserva** (Línea 2967). Usa `memoryArtworkCache[myCoreKey]`.

**Lógica de Spotify:**
```kotlin
3006:         if (uriString.startsWith(SPOTIFY_MEDIA_API_PREFIX)) {
3007:             val hash = Uri.decode(uriString).substringAfterLast(":").substringBefore("?")
3008:             if (hash.isNotBlank()) return downloadBitmapFromUrl("$SPOTIFY_CDN_PREFIX$hash")
3009:         }
```
Previene el uso de la URI de contenido local de Spotify (que a menudo falla) traduciéndola a una URL de CDN pública.
