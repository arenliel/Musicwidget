# Auditoría de Evidencia — Ronda 1 (Post Identidad-Final): Profundización

## Sección 1 — Letras (profundización)

### L1. Ubicación y cálculo de `songChangedForLyrics` y `trackChangedUI`
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2373-2386)
```kotlin
        val oldTrackChangedUI = previousLogical?.trackKey != snapshot.trackKey
        val trackChangedUI = 
            previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
        InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=$oldTrackChangedUI, nuevo=$trackChangedUI, coincide=${oldTrackChangedUI == trackChangedUI}, track=${snapshot.title}")

        // Identidad de negocio para decidir si hace falta re-buscar la letra (Conjunto Letras-3).
        // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
        // cambió por una corrección tardía de duración.
        val songChangedForLyrics = previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
```

### L2. Cuerpo completo de `getLyrics()` en `LyricsRepository.kt`
**Archivo:** [LyricsRepository.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt)
```kotlin
    suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Intentar desde Room
        val cached = lyricsDao.getLyrics(trackKey)
        if (cached != null) {
            val now = System.currentTimeMillis()
            if (cached.notFound) {
                // TTL 1h para re-intentos de letras (v3.0)
                if (now - cached.timestampFetched < 1 * 60 * 60 * 1000L) {
                    return@withContext null
                }
            } else {
                lyricsDao.updateLastAccessed(trackKey, now)
                return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
            }
        }

        // 2. Si no hay o TTL expiró, ir a red
        val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
        val now = System.currentTimeMillis()
        
        if (networkResult != null) {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = networkResult,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = false
                )
            )
            return@withContext parseLrc(trackKey, networkResult, durationMs)
        } else {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = null,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = true
                )
            )
            return@withContext null
        }
    }
```

### L3. Función `parseStoredLyrics` y lógica `notFound`
**Archivo:** [LyricsRepository.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt)
```kotlin
    private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        return parseLrc(trackKey, lrc, durationMs)
    }

    // Lógica notFound detectada en la consulta (getLyrics línea 24):
    if (cached.notFound) {
        // TTL 1h para re-intentos de letras (v3.0)
        if (now - cached.timestampFetched < 1 * 60 * 60 * 1000L) {
            return@withContext null
        }
    }
```

### L4. Inventario Room en letras
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsEntity.kt:5: @Entity(tableName = "lyrics_cache")
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsEntity.kt:12:     val notFound: Boolean = false // TTL 24h para re-intentos
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsDao.kt:5: @Dao
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:24:             if (cached.notFound) {
```
**Evidencia TTL:** Existe un campo `notFound` en `LyricsEntity.kt` y una validación de tiempo en `LyricsRepository.kt` (Línea 26) que implementa un TTL de 1 hora (`1 * 60 * 60 * 1000L`) para evitar reintentos constantes a la red tras un fallo.

---

## Sección 2 — Portada (profundización)

### A1. Función `saveTextToFile` y sus llamadores
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 3154-3167)
```kotlin
    private suspend fun saveTextToFile(text: String, fileName: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val finalFile = File(filesDir, fileName)
                val tempFile = File(filesDir, "$fileName.tmp")
                tempFile.writeText(text)
                try {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo archivo de texto $fileName", e)
            }
        }
    }
```
**Llamadores (en Stage 2):**
Línea 2521: `saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)`
Línea 2536: `saveTextToFile("", ALBUM_ART_KEY_FILE)`
**Scope:** Se ejecutan dentro de `serviceScope.launch` (Stage 2) dentro de un bloque `NonCancellable` y protegidos por `commitMutex.withLock`. Ocurren **antes** de cualquier llamada a `MusicWidget.updateAll(applicationContext)` que sucede al final de `startUiUpdateDispatcher` tras recolectar del flow.

### A2. Bloque `else` de fallback en `MusicWidget.kt`
**Archivo:** [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt) (Líneas 240-256)
```kotlin
                } else {
                    // PIPELINE DE FALLBACK IDEMPOTENTE (v5.2): Prioridad Imagen sobre Llave
                    if (!displayedInfo.isEmpty) {
                        withContext(Dispatchers.IO) {
                            // Paso 1: Intentar rescatar del Buffer de Sesión (UUID Inmutable)
                            val sessionBuffer = File(context.filesDir, "history/buffer/buf_${displayedInfo.sessionUUID}.webp")
                            if (sessionBuffer.exists()) {
                                value = decodeBitmap(sessionBuffer, 800, 800)
                            } 
                            
                            // Paso 2: Si falla, rescatar del Disk Shield Maestro
                            if (value == null) {
                                val shieldFile = File(context.cacheDir, "current_artwork_raw.webp")
                                if (shieldFile.exists()) {
                                    value = decodeBitmap(shieldFile, 800, 800)
                                }
                            }
                        }
                    } else value = null
                }
```

### A3. Cálculo de `snapshot.artworkKey`
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 503-511)
```kotlin
        /**
         * Visual identity key used for artwork resolution and caching. Falls back to its own
         * album-aware key (not [trackKey]) specifically so that different album editions of the
         * same song can carry different artwork. Do not change this fallback to use [trackKey] —
         * that reintroduces cross-contamination between album editions' cached artwork (see the
         * "Harana" and "Archie, Marry Me" history bugs, Conjunto C).
         */
        val artworkKey: String
            get() =
                artworkUri
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "$sessionIdentity|${MusicDataStore.normalize(album)}"
```

### A4. Escrituras a `ALBUM_ART_FILE` y `ALB_RAW_FILE`
```text
MusicNotificationListener.kt:2516: saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
MusicNotificationListener.kt:2519: saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
MusicNotificationListener.kt:2534: saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
MusicNotificationListener.kt:2535: saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
```

---

## Sección 3 — Inventario de trazas existentes

### Trazas `ART_TRACE`
1. `MusicNotificationListener.kt:1714`: `InternalLogger.d(applicationContext, "[ART_TRACE] createSnapshot: tieneBitmapEmbebido=$hasBitmap, artworkUriCruda=$rawUri")`
2. `MusicNotificationListener.kt:1755`: `InternalLogger.d(applicationContext, "[ART_TRACE] Fase A: escribiendo para coreKey=$myCoreKey, gen_actual=$identityGenerationCounter")`
3. `MusicNotificationListener.kt:1768`: `InternalLogger.d(applicationContext, "[ART_TRACE] Retoque Atómico: escribiendo para UUID=${session.sessionUUID}, gen_actual=$identityGenerationCounter")`
4. `MusicNotificationListener.kt:2189`: `InternalLogger.d(applicationContext, "[ART_TRACE] Sesión saliente antes de reemplazo: UUID=${session?.sessionUUID}, artworkKey=${session?.playbackContext?.artworkKey}")`
5. `MusicNotificationListener.kt:2192`: `InternalLogger.d(applicationContext, "[ART_TRACE] Nueva generación de identidad: gen=$identityGenerationCounter, UUID=${newSession.sessionUUID}, Track=${rawSnapshot.title}")`
6. `MusicNotificationListener.kt:2428`: `InternalLogger.d(applicationContext, "[ART_TRACE] Resolución terminada: Exito=${resolvedArtwork != null}, gen_actual=$genAlIniciarResolucion, UUID_actual=${currentLogicalSession?.sessionUUID}, Track_actual=${snapshot.title.lowercase()}")`
7. `MusicNotificationListener.kt:2515`: `InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")`
8. `MusicNotificationListener.kt:2587`: `InternalLogger.d(applicationContext, "[ART_TRACE] Decisión final de portada: artIncoherent=$artIncoherent, gen=$identityGenerationCounter, UUID=${currentLogicalSession?.sessionUUID}, valorElegido=$finalArtworkUri")`
9. `MusicNotificationListener.kt:2616`: `InternalLogger.d(applicationContext, "[ART_TRACE] Persistencia confirmada: artworkUri_guardado=${musicInfo.artworkUri}")`
10. `MusicNotificationListener.kt:2903`: `InternalLogger.d(applicationContext, "[ART_TRACE] Iniciando resolución para: gen=$identityGenerationCounter, UUID=${currentLogicalSession?.sessionUUID}, Track=${snapshot.title}")`
11. `MusicWidget.kt:232`: `InternalLogger.d(context, "[ART_TRACE] Chequeo de sincronía: resultado=$isArtworkSynchronized, keyActual=${displayedInfo.artworkKey}, keyEnDisco=${readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim()}")`

### Trazas `LYRICS_TRACE`
1. `MusicNotificationListener.kt:664`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] Aplicando Seek (Lag compensado: ${processingLag}ms): ${actualPos}ms (Raw: ${rawSnapshot.positionMs}ms)")`
2. `MusicNotificationListener.kt:1365`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] Seek detectado (${actualPos}ms). Agrupando ráfaga...")`
3. `MusicNotificationListener.kt:2396`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")`
4. `MusicNotificationListener.kt:2446`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")`
5. `MusicNotificationListener.kt:2461`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")`
6. `MusicNotificationListener.kt:2666`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] relaunchLyricsTicker: Reason=$reason | Track=${currentInfo.title}")`
7. `MusicNotificationListener.kt:2696`: `InternalLogger.d(applicationContext, "[LYRICS_TRACE] Zombie Detector: Clave discordante. Cancelando Ticker.")`

---

## Sección 4 — Cruce de Timestamps (Anomalías)

*   **Caught in a Jam:** Sesión creada a las `11:29:03`. Fallo de sincronía registrado a las `11:29:15`. Error `No app widget info for 1113` detectado a las **`11:29:49`**. **Ausencia** de error Glance en el margen de ±5s.
*   **Close 2 Me:** Sesión creada a las `11:28:41`. Error `No app widget info for 1111` detectado previamente a las `11:26:58`. **Ausencia** de error Glance en el margen de ±5s.
*   **Black-Eyed Susan Climb:** Sesión creada a las `11:39:27`. **Ausencia** de error Glance en el margen de ±5s.

---

## Sección 5 — Verificación de estado del repositorio

### Git Log (Últimos 5)
```text
b00b254 (HEAD -> master) Conjunto Identidad-Final (Parte 2): Unificación de porteros de letras a identidad de negocio
ccc3816 Conjunto Identidad-Final (Parte 1): Migración a sessionIdentity con instrumentación de traza
3e85a7a Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

### Git Show --stat (Cambios recientes)
No hay commits posteriores a `b00b254`.
