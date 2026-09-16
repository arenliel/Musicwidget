# Auditoría — Ronda 14 (Portadas): Código Completo de `provideGlance`

**Confirmación de Git Log (HEAD):**
```
699229c (HEAD -> master) Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
c10562a Conjunto Artwork-3: Guardar imagen resuelta con verificación de identidad (identityGenerationCounter based)
7f27757 Conjunto Artwork-Diag: Instrumentación de diagnóstico para resolución de portadas e identidad de sesión
```

---

## AK1. Código completo de `provideGlance`

Ubicado en `MusicWidget.kt` (Líneas 192 - 278).

```kotlin
192:     override suspend fun provideGlance(context: Context, id: GlanceId) {
193:         val dataStore = MusicDataStore(context)
194:         
195:         provideContent {
196:             // FAST-TRACK SSOT (v2.0): Priorizamos la memoria sobre el disco
197:             val memInfo by MusicStateProvider.musicInfoState.collectAsState()
198:             val diskInfo by dataStore.musicInfoFlow.collectAsState(
199:                 initial = MusicNotificationListener.getLatestMusicInfo() ?: MusicInfo(title = "", artist = "", packageName = "")
200:             )
201: 
202:             val musicInfo = memInfo ?: diskInfo
203:             
204:             val widgetSize = LocalSize.current
205:             val context = LocalContext.current
206:             val fontScale = context.resources.configuration.fontScale
207: 
208:             // 1. EVALUACIÓN DEL SENSOR (v1.5.2)
209:             val sensorResult = if (!widgetSize.width.value.isNaN()) {
210:                 CollisionSensor.evaluate(
211:                     availableHeight = widgetSize.height.value,
212:                     fontScale = fontScale,
213:                     isPreview = false,
214:                     appearance = appearance
215:                 )
216:             } else null
217: 
218:             // El asset depende estrictamente de la decisión de layout final
219:             val needsPillAsset = sensorResult?.layoutType == WidgetLayout.STACKED
220: 
221:             val notificationsEnabled = PermissionUtils.isNotificationServiceEnabled(context)
222:             val batteryOptimized = PermissionUtils.isBatteryOptimizationIgnored(context)
223:             
224:             // MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en visual.
225:             // Gestiona automáticamente el estado vacío y la lista negra.
226:             val displayedInfo = musicInfo.copy(
227:                 notificationsEnabled = notificationsEnabled, 
228:                 batteryOptimized = batteryOptimized
229:             ).toDisplayedState(context)
230: 
231:             val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim() && displayedInfo.artworkKey.isNotBlank()
232: 
233:             val albumArtBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.artworkKey, displayedInfo.sessionUUID, needsPillAsset, isArtworkSynchronized) {
234:                 if (isArtworkSynchronized) {
235:                     val cacheKey = "${displayedInfo.artworkKey}_${if(needsPillAsset) "pill" else "raw"}"
236:                     bitmapCache.get(cacheKey)?.also { value = it } ?: withContext(Dispatchers.IO) {
237:                         val decoded = decodeBitmap(
238:                             File(context.filesDir, if (needsPillAsset) ALBUM_ART_FILE else ALB_RAW_FILE),
239:                             reqWidth = if (needsPillAsset) 400 else 800, // RAW a mayor resolución (P1)
240:                             reqHeight = if (needsPillAsset) 400 else 800
241:                         )
242:                         decoded?.also { bitmapCache.put(cacheKey, it); value = it }
243:                     }
244:                 } else {
245:                     // PIPELINE DE FALLBACK IDEMPOTENTE (v5.2): Prioridad Imagen sobre Llave
246:                     if (!displayedInfo.isEmpty) {
247:                         withContext(Dispatchers.IO) {
248:                             // Paso 1: Intentar rescatar del Buffer de Sesión (UUID Inmutable)
249:                             val sessionBuffer = File(context.filesDir, "history/buffer/buf_${displayedInfo.sessionUUID}.webp")
250:                             if (sessionBuffer.exists()) {
251:                                 value = decodeBitmap(sessionBuffer, 800, 800)
252:                             } 
253:                             
254:                             // Paso 2: Si falla, rescatar del Disk Shield Maestro
255:                             if (value == null) {
256:                                 val shieldFile = File(context.cacheDir, "current_artwork_raw.webp")
257:                                 if (shieldFile.exists()) {
258:                                     value = decodeBitmap(shieldFile, 800, 800)
259:                                 }
260:                             }
261:                         }
262:                     } else value = null
263:                 }
264:             }
265: 
266:             val isIconSynchronized = displayedInfo.appIconKey.trim() == readTextFile(File(context.filesDir, APP_ICON_KEY_FILE)).trim() && displayedInfo.appIconKey.isNotBlank()
267:             val appIconBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.packageName, isIconSynchronized) {
268:                 if (isIconSynchronized) withContext(Dispatchers.IO) { value = decodeBitmap(File(context.filesDir, APP_ICON_FILE)) }
269:                 else if (displayedInfo.packageName.isNotBlank()) withContext(Dispatchers.IO) {
270:                     runCatching { context.packageManager.getApplicationIcon(displayedInfo.packageName).toBitmap(40, 40) }.getOrNull()?.also { value = it }
271:                 } else value = null
272:             }
273: 
274:             GlanceTheme {
275:                 MusicWidgetUI(displayedInfo, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, forcedAppearance = appearance)
276:             }
277:         }
278:     }
```

**Mecanismo de decisión de `albumArtBitmap`:**
1.  **Archivo Sincronizado (Línea 234):** Si `isArtworkSynchronized` es true (la llave en disco coincide con la llave en memoria), intenta cargar de `bitmapCache`. Si falla, decodifica de `ALBUM_ART_FILE` (píldora) o `ALB_RAW_FILE` (bruto) según el sensor de layout.
2.  **Buffer de Sesión (Línea 249):** Si no hay sincronía, busca en el buffer del historial: `"history/buffer/buf_${displayedInfo.sessionUUID}.webp"`.
3.  **Disk Shield Maestro (Línea 256):** Como último recurso, busca en el escudo de disco: `cacheDir/current_artwork_raw.webp`.

---

## AK2. ¿Alguna de esas tres fuentes depende de `artworkUri`, aunque sea indirectamente?

**NO.**

Las tres fuentes de imagen son completamente independientes del campo `artworkUri`:
- El **Archivo Sincronizado** depende de `artworkKey` (para verificar sincronía y generar la clave de caché) y de archivos con nombres estáticos (`album_art.webp`, `album_art_raw.webp`).
- El **Buffer de Sesión** depende de `sessionUUID` para construir la ruta del archivo.
- El **Disk Shield** utiliza una ruta de archivo fija.

En ningún punto de la lógica de resolución de `albumArtBitmap` dentro de `provideGlance` se lee, evalúa o procesa el valor contenido en `displayedInfo.artworkUri` (o `musicInfo.artworkUri`). Este campo solo se propaga para ser usado como "Extra" en la navegación a la actividad de detalle.
