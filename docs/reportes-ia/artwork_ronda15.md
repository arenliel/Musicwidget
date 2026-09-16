# Auditoría — Ronda 15 (Portadas): ¿Quién Escribe los Archivos que el Widget Realmente Consulta?

**Confirmación de Git Log (HEAD):**
```
699229c (HEAD -> master) Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
c10562a Conjunto Artwork-3: Guardar imagen resuelta con verificación de identidad (identityGenerationCounter based)
7f27757 Conjunto Artwork-Diag: Instrumentación de diagnóstico para resolución de portadas e identidad de sesión
```

---

## AL1. ¿Quién escribe `ALB_KEY_FILE`, `ALBUM_ART_FILE`, y `ALB_RAW_FILE`?

Ubicado en `MusicNotificationListener.kt` (Stage 2 de `processSnapshot`).

### 1. En caso de Éxito de Resolución (Líneas 2509 - 2514):
```kotlin
2509:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2510:                                 
2511:                                 // 2. Guardar versión WIDGET (Píldora)
2512:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
2513:                                 
2514:                                 saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
```

### 2. En caso de Fallo / Placeholder (Líneas 2527 - 2529):
```kotlin
2527:                             saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2528:                             saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
2529:                             saveTextToFile("", ALBUM_ART_KEY_FILE)
```

**Constantes (Líneas 3264 - 3266):**
```kotlin
3264:         private const val ALBUM_ART_FILE = "album_art.webp"
3265:         private const val ALBUM_ART_RAW_FILE = "album_art_raw.webp"
3266:         private const val ALB_KEY_FILE = "album_art.key" // Nota: el nombre de la constante es ALB_KEY_FILE
```

---

## AL2. ¿Quién escribe el archivo de "buffer de sesión" (`buf_<UUID>.webp`, dentro de `history/buffer`)?

**NADIE.**

Tras una búsqueda exhaustiva en todo el proyecto (`grep -r "buffer/buf_"`), no existe ningún código que escriba en esa ruta. 
- `MusicWidget.kt` intenta **leerlo** como fallback (Línea 249).
- `ArtworkStorageManager.kt` guarda archivos con el prefijo `art_` (Línea 32), no `buf_`.
- El historial guarda sus archivos en la carpeta raíz `history/`, no en `history/buffer/`.

**Confirmación:** El código de lectura en el widget (`history/buffer/buf_...`) es una **referencia huérfana** a una arquitectura de carpetas que ya no existe o fue eliminada en versiones previas (v9.1/v9.2).

---

## AL3. ¿Quién escribe el "Disk Shield" (`current_artwork_raw.webp`)?

Ubicado en `MusicNotificationListener.kt`.

### Función que realiza la escritura (Líneas 3286 - 3302):
```kotlin
3286:     private suspend fun saveBitmapToDiskShield(bitmap: Bitmap) = withContext(Dispatchers.IO) {
3287:         fileMutex.withLock {
3288:             val file = File(cacheDir, DISK_SHIELD_FILE)
3289:             try {
3290:                 val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
3291:                     Bitmap.CompressFormat.WEBP_LOSSY
3292:                 } else {
3293:                     @Suppress("DEPRECATION")
3294:                     Bitmap.CompressFormat.WEBP
3295:                 }
3296:                 FileOutputStream(file).use { out ->
3297:                     bitmap.compress(format, 90, out)
3298:                     out.flush()
3299:                 }
3300:             } catch (e: Exception) {
3301:                 Log.e(TAG, "Fallo al escribir Disk Shield", e)
3302:             }
3303:         }
3304:     }
```

### Punto de llamada (Fase A de `createSnapshot`, Líneas 1754 - 1756):
```kotlin
1754:                     serviceScope.launch(Dispatchers.IO) {
1755:                         saveBitmapToDiskShield(clone)
1756:                     }
```
Se escribe de forma asíncrona cada vez que se captura un nuevo bitmap fresco desde los metadatos de la sesión multimedia.
