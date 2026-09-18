# Auditoría — Ronda 18 (Portadas): Código Completo de la Rama de Fallo

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AO1. Contexto completo de la rama de fallo

Ubicado en `MusicNotificationListener.kt` (Líneas 2503 - 2535):

```kotlin
2503:                         if (resolvedArtwork != null) {
2504:                             // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
2505:                             // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
2506:                             // SEGURIDAD IPC (v4.5): Escalado de cortesía para el bus Binder.
2507:                             val transportBitmap = scaleForTransport(resolvedArtwork)
2508:                             val cacheKey = "${rawSnapshot.artworkKey}_raw"
2509:                             MusicWidget.bitmapCache.put(cacheKey, transportBitmap)
2510: 
2511:                             // Paso 3.2: CACHING DE TRANSFORMACIÓN
2512:                             if (savedArtworkKey != snapshot.artworkKey) {
2513:                                 // 1. Guardar versión RAW
2514:                                 InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")
2515:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2516:                                 
2517:                                 // 2. Guardar versión WIDGET (Píldora)
2518:                                 saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
2519:                                 
2520:                                 saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
2521:                                 savedArtworkKey = snapshot.artworkKey
2522: 
2523:                                 // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
2524:                                 // Inyectamos el bitmap en el snapshot lógico para que la próxima 
2525:                                 // transición de historial lo lleve ya resuelto.
2526:                                 lastLogicalSnapshot = lastLogicalSnapshot?.copy(
2527:                                     artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
2528:                                 )
2529:                             }
2530:                         } else if (trackChangedUI || artworkChangedUI) {
2531:                             // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
2532:                             val placeholder = getPlaceholderBitmap()
2533:                             saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2534:                             saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
2535:                             saveTextToFile("", ALBUM_ART_KEY_FILE)
2536:                             savedArtworkKey = null
2537:                         }
```

**Condición de decisión:**
La rama de fallo se decide en la línea 2530:
`else if (trackChangedUI || artworkChangedUI)`

Esta condición **SÍ** distingue si es la misma canción de antes. Si el sistema no detecta un cambio de identidad (`trackChangedUI`) ni un cambio en la llave de metadatos (`artworkChangedUI`), simplemente no entra en el bloque `else if` y no escribe el placeholder, conservando así los archivos que ya existían en disco.

---

## AO2. ¿`resolvedArtwork` nulo dispara automáticamente esta rama?

**NO.**

Para entrar a la rama de fallo (Línea 2530) se deben cumplir **dos condiciones simultáneas**:
1.  `resolvedArtwork == null` (que el `if` de la línea 2503 falle).
2.  `trackChangedUI || artworkChangedUI` (que haya un cambio detectado de pista o metadatos).

**Confirmación:** Si la resolución falla (`resolvedArtwork == null`) pero estamos en una ráfaga de continuación de la **misma canción** (donde `trackChangedUI` y `artworkChangedUI` son falsos), el sistema **ignora** la rama de fallo y no sobrescribe el disco con el placeholder.
