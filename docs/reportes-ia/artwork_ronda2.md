# Auditoría — Ronda 2 (Portadas): Destino del Retoque Atómico, Nivel 1 en Casos Normales, y Reprocesamiento Rápido

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## VV1. Destino exacto de la escritura del "Retoque Atómico"

Ubicado en `MusicNotificationListener.kt` (dentro de `createSnapshot`).

```kotlin
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
```

**Confirmación:** Escribe **únicamente** al archivo de historial identificado por el UUID de la sesión (`art_${session.sessionUUID}.webp`). No actualiza `musicDataStore` (el registro que alimenta "ahora sonando") en este bloque.

---

## VV2. Campo exacto de portada que usa el Composable de "ahora sonando"

Declarado en `MusicDataStore.kt` dentro de la clase `MusicInfo`:

```kotlin
44:     /*
45:      * Identidad de la portada actualmente asociada
46:      * a la pista.
47:      *
48:      * Es especialmente útil para Spotify, donde la URI
49:      * puede apuntar a un recurso remoto.
50:      */
51:     val artworkKey: String = "",
```

**Tipo de dato:** `String`.

---

## VV3. ¿El Nivel 1 (metadatos directos) depende de la misma construcción síncrona de `finalMusicInfo` que ya sabemos que no espera al Nivel 3?

**SÍ, es síncrono.**

Analizando el flujo en `processSnapshot`:

1.  **Stage 2 - Fase de Resolución (Líneas 2397 - 2404):**
    ```kotlin
    2397:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
    2398:                     resolveArtworkDeduplicated(...)
    ```
    Aquí, `resolveArtworkDeduplicated` llama a `getOrCreateArtworkDeferred`, el cual invoca a `findRealAlbumArt` (Nivel 1). Esta llamada está envuelta en un `withTimeoutOrNull` síncrono que suspende la corrutina.

2.  **Construcción de `finalMusicInfo` (Línea 2543):**
    Esta línea se ejecuta **después** de que la fase de resolución anterior ha terminado y ha devuelto `resolvedArtwork`.

**Confirmación:** Si el Nivel 1 (metadatos directos) tiene éxito, el resultado **está disponible** para la construcción de `finalMusicInfo` en el mismo ciclo.

---

## VV4. ¿Se llama a `findRealAlbumArt` en cada una de las "sesiones nuevas" de una misma ráfaga de recreación rápida?

**Depende del estado de la resolución anterior.**

Analizando `getOrCreateArtworkDeferred` (Línea 2862):

```kotlin
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
```

1.  **Caso de Éxito/Caché:** Si la primera sesión de la ráfaga logra guardar el resultado en `artworkCache` (Línea 2885), las sesiones posteriores para la misma canción (`artworkKey` idéntico) **heredan el resultado** inmediatamente de la caché (Línea 2871).
2.  **Caso "En Vuelo":** Si la primera sesión lanzó la búsqueda pero aún no ha terminado, la segunda sesión **reutiliza el mismo `Deferred`** (Línea 2875), por lo que ambas esperan al mismo proceso único de `findRealAlbumArt`.
3.  **Caso de Fallo:** Si el intento anterior terminó con error o `null`, se elimina de `artworkInFlight` (Línea 2878) y la siguiente sesión **dispara una nueva llamada** independiente a `findRealAlbumArt` (Línea 2880).

**Resumen:** No disparan llamadas independientes si ya hay un éxito o una búsqueda activa para el mismo `artworkKey`. Solo reintentan si el anterior falló o fue cancelado.
