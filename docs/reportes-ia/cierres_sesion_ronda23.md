# Auditoría — Ronda 23: De Dónde Lee el Widget la Portada de "Ahora Sonando" (Rastreo Inverso)

**Confirmación de Git Log:**
```
34fc859 (HEAD -> master) Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
```

---

## QQ1. ¿Qué campo de `MusicInfo` usa el Composable de "ahora sonando" para la portada?

Ubicado en `MusicWidget.kt` (dentro de `provideGlance`).

### Código del campo (Línea 232):
```kotlin
232:             val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim() && displayedInfo.artworkKey.isNotBlank()
```

### Código del Composable que usa la imagen (Líneas 234 - 263):
```kotlin
234:             val albumArtBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.artworkKey, displayedInfo.sessionUUID, needsPillAsset, isArtworkSynchronized) {
235:                 if (isArtworkSynchronized) {
236:                     val cacheKey = "${displayedInfo.artworkKey}_${if(needsPillAsset) "pill" else "raw"}"
237:                     bitmapCache.get(cacheKey)?.also { value = it } ?: withContext(Dispatchers.IO) {
238:                         val decoded = decodeBitmap(
239:                             File(context.filesDir, if (needsPillAsset) ALBUM_ART_FILE else ALB_RAW_FILE),
240:                             reqWidth = if (needsPillAsset) 400 else 800, // RAW a mayor resolución (P1)
241:                             reqHeight = if (needsPillAsset) 400 else 800
242:                         )
243:                         decoded?.also { bitmapCache.put(cacheKey, it); value = it }
244:                     }
245:                 } else {
246:                     // PIPELINE DE FALLBACK IDEMPOTENTE (v5.2): Prioridad Imagen sobre Llave
247:                     if (!displayedInfo.isEmpty) {
248:                         withContext(Dispatchers.IO) {
249:                             // Paso 1: Intentar rescatar del Buffer de Sesión (UUID Inmutable)
250:                             val sessionBuffer = File(context.filesDir, "history/buffer/buf_${displayedInfo.sessionUUID}.webp")
251:                             if (sessionBuffer.exists()) {
252:                                 value = decodeBitmap(sessionBuffer, 800, 800)
253:                             } 
254:                             
255:                             // Paso 2: Si falla, rescatar del Disk Shield Maestro
256:                             if (value == null) {
257:                                 val shieldFile = File(context.cacheDir, "current_artwork_raw.webp")
258:                                 if (shieldFile.exists()) {
259:                                     value = decodeBitmap(shieldFile, 800, 800)
260:                                 }
261:                             }
262:                         }
263:                     } else value = null
264:                 }
265:             }
```

**Campo de `MusicInfo` usado:** `artworkKey`. Se usa como disparador del estado (`produceState`) y como llave de verificación contra el archivo físico `ALB_KEY_FILE`.

---

## QQ2. ¿De dónde obtiene el Composable su instancia de `MusicInfo`?

El Composable obtiene la instancia mediante un mecanismo de prioridad de fuentes en `provideGlance`:

```kotlin
196:             // FAST-TRACK SSOT (v2.0): Priorizamos la memoria sobre el disco
197:             val memInfo by MusicStateProvider.musicInfoState.collectAsState()
198:             val diskInfo by dataStore.musicInfoFlow.collectAsState(
199:                 initial = MusicNotificationListener.getLatestMusicInfo() ?: MusicInfo(title = "", artist = "", packageName = "")
200:             )
201: 
202:             val musicInfo = memInfo ?: diskInfo
```

**Confirmación:** Lee prioritariamente de la RAM (`MusicStateProvider`) y usa el disco (`MusicDataStore`) como respaldo sincronizado.

---

## QQ3. ¿Quién escribe ese campo específico identificado en QQ1?

Ubicado en `MusicNotificationListener.kt`.

### Coincidencia 1 (En `logicalMusicInfo`, dentro del gating de visibilidad):
```kotlin
2313:                     artworkKey = snapshot.artworkKey,
```

### Coincidencia 2 (En `finalMusicInfo`, Stage 2 - Compromiso Final):
```kotlin
2550:                         artworkKey = snapshot.artworkKey,
```

---

## QQ4. Relación entre ese campo y `artworkChangedUI`

La **asignación del campo** `artworkKey` en los objetos `MusicInfo` es **incondicional** dentro de sus respectivos bloques en `processSnapshot`:
- Se asigna siempre en `logicalMusicInfo` si el widget no es visible.
- Se asigna siempre en `finalMusicInfo` si el widget es visible y el flujo llega al final de la Stage 2.

Sin embargo, existe una **dependencia crítica indirecta**:
El archivo físico `ALB_KEY_FILE` (que el widget usa para verificar si la imagen es válida) solo se escribe si `artworkChangedUI` es verdadero (o se cumple alguna de las otras condiciones de refresco):

```kotlin
2481:                     if (controller != null && metadata != null && 
2482:                         (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
...
2492:                             if (savedArtworkKey != snapshot.artworkKey) {
...
2499:                                 saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
2500:                                 savedArtworkKey = snapshot.artworkKey
```

**Resumen:** Si `artworkChangedUI` falla (es falso por error de comparación), el campo `MusicInfo.artworkKey` en el DataStore se actualizará (porque `saveMusicInfo` detecta el cambio de valor), pero el archivo de llave en disco `ALB_KEY_FILE` **no**, provocando que el widget vea el estado como "desincronizado" (`isArtworkSynchronized = false`) y caiga permanentemente en el pipeline de fallback.
