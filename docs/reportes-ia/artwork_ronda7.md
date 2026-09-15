# Auditoría — Ronda 7 (Portadas): Código Completo de `resolveArtworkDeduplicated` y Cómo un Bitmap se Convierte en URI

**Confirmación de Git Log:**
```
e54d9d0 (HEAD -> master) Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
998c2ef Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
```

---

## AB1. Confirmación fresca del tipo de retorno

Ubicada en `MusicNotificationListener.kt` (Línea 2847):

```kotlin
2847:     private suspend fun resolveArtworkDeduplicated(
```

**Tipo de retorno:** `Bitmap?` (Confirmado en la línea 2853).

---

## AB2. Código completo de `resolveArtworkDeduplicated`

Ubicado en `MusicNotificationListener.kt` (Líneas 2847 - 2866).

```kotlin
2847:     private suspend fun resolveArtworkDeduplicated(
2848:         snapshot: MediaSnapshot,
2849:         controller: MediaController? = null,
2850:         metadata: MediaMetadata? = null,
2851:         generation: Long = -1L // -1 indica que se ignora la validación de generación (v6.3)
2852:     ): Bitmap? {
2853:         val artworkKey = snapshot.artworkKey
2854:         val isVisible = isWidgetPotentiallyVisible()
2855:         InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Intentando resolución. Track=${snapshot.title}, Visible=$isVisible")
2856:         
2857:         artworkCache.get(artworkKey)?.let { bitmap ->
2858:             InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Cache HIT en RAM. Key=$artworkKey")
2859:             return bitmap
2860:         }
2861:         val deferred = getOrCreateArtworkDeferred(snapshot, controller, metadata, generation)
2862:         return deferred.await()
2863:     }
```

**Comportamiento:** La función devuelve el `Bitmap?` resuelto (ya sea de caché o de una búsqueda activa). **No realiza ninguna persistencia en disco dentro de sí misma.**

---

## AB3. ¿Dónde se guarda un `Bitmap` como archivo y se obtiene su URI, en este proyecto?

El proyecto utiliza dos mecanismos distintos de guardado:

### 1. Mecanismo de Historial (Genera URI/Path)
Ubicado en `ArtworkStorageManager.kt`:
```kotlin
25:     fun saveHistoryArtwork(context: Context, bitmap: Bitmap, identifier: String): String {
...
56:         return finalFile.absolutePath
57:     }
```
Esta es la única función que convierte un `Bitmap` en un archivo físico y devuelve su ruta absoluta (`String`) como identificador de acceso. Se utiliza en la Stage 1 de `processSnapshot` (Línea 2195) para el historial.

### 2. Mecanismo de "Ahora Sonando" (No genera URI)
Ubicado en `MusicNotificationListener.kt` bajo la función `saveBitmapToFile` (Línea 3120). Se usa en el **Paso C** (Stage 2) para actualizar los archivos de la sesión activa:
```kotlin
2495:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2496:                                 
2497:                                 // 2. Guardar versión WIDGET (Píldora)
2498:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
```
Esta función **no devuelve nada** y no se utiliza para generar una URI que se guarde en `MusicInfo`. El widget de Glance accede a estos archivos por nombre fijo (`ALBUM_ART_FILE`), no por URI dinámica.

**Confirmación de conexión:** El flujo de "ahora sonando" (Stage 2) **nunca** utiliza `saveHistoryArtwork` ni ninguna lógica que convierta el `resolvedArtwork` (Bitmap) en una URI de texto para el campo `artworkUri`. Este campo permanece dependiente de los metadatos de la sesión multimedia (`snapshot.artworkUri`).
