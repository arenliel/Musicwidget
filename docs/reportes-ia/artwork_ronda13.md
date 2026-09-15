# Auditoría — Ronda 13 (Portadas): ¿El Widget de "Ahora Sonando" Puede Mostrar una URL Remota, o Solo Archivos Locales?

**Confirmación de Git Log (HEAD):**
```
7f27757 (HEAD -> master) Conjunto Artwork-Diag: Instrumentación de diagnóstico para resolución de portadas e identidad de sesión
e41110d Revert "Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI"
e54d9d0 Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
```

---

## AI1. Código completo del Composable que dibuja la portada de "ahora sonando"

Ubicado en `MusicWidget.kt` (Líneas 760 - 776).

```kotlin
760:     @Composable
761:     private fun AlbumArtWithVisualizer(context: Context, info: MusicInfo, albumArtBitmap: Bitmap?, isArtworkSynchronized: Boolean, pillSize: Dp, showVisualizer: Boolean = true) {
762:         val isPlaceholder = !isArtworkSynchronized || albumArtBitmap == null
763:         Box(modifier = GlanceModifier.size(pillSize).clickable(actionStartActivity(android.content.Intent(context, ArtworkDetailActivity::class.java).apply { putExtra("artwork_uri", info.artworkUri); putExtra("artwork_key", info.artworkKey) }))) {
764:             if (!isPlaceholder) Image(provider = ImageProvider(albumArtBitmap!!), contentDescription = context.getString(R.string.content_desc_album_art), modifier = GlanceModifier.fillMaxSize())
765:             else Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.fillMaxSize()) {
766:                 Image(provider = ImageProvider(R.drawable.ic_preview_pill), contentDescription = context.getString(R.string.content_desc_no_artwork), colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer), modifier = GlanceModifier.fillMaxSize())
767:                 Image(provider = ImageProvider(R.drawable.ic_music_note), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(pillSize * 0.3f))
768:             }
769:             val safetyMargin = (pillSize.value * 0.04f).dp
770:             Box(modifier = GlanceModifier.fillMaxSize().padding(top = safetyMargin, start = safetyMargin), contentAlignment = Alignment.TopStart) { DeviceIconTonal(info = info) }
771:             if (showVisualizer) {
772:                 val visualizerSize = (pillSize.value * 0.24f).dp
773:                 Box(modifier = GlanceModifier.fillMaxSize().padding(bottom = safetyMargin, end = safetyMargin), contentAlignment = Alignment.BottomEnd) { VisualizerSelector(context, info, visualizerSize) }
774:             }
775:         }
776:     }
```

La función utiliza `Image(provider = ImageProvider(albumArtBitmap!!), ...)` (Línea 764) para renderizar la imagen. El parámetro `albumArtBitmap` es un objeto `Bitmap` ya decodificado que le pasa el llamador (`provideGlance`).

---

## AI2. ¿Esa función de carga acepta una URL de internet (`https://...`), o solo un archivo local (`file://...`)?

**SOLO ARCHIVOS LOCALES (Vía Bitmap).**

El Composable de Glance no tiene capacidad para descargar imágenes desde una URL de internet de forma nativa. La arquitectura del proyecto delega la descarga al **Servicio** (`MusicNotificationListener.kt`).

1.  **En el Servicio (`MNL.kt`):** La función `findRealAlbumArt` identifica si hay una URL, la descarga (`downloadBitmapFromUrl`) y la guarda en archivos locales con nombres fijos como `album_art.webp`.
2.  **En el Widget (`MusicWidget.kt`):** La función `provideGlance` decodifica esos archivos locales usando `BitmapFactory`:
    ```kotlin
    238:                         val decoded = decodeBitmap(
    239:                             File(context.filesDir, if (needsPillAsset) ALBUM_ART_FILE else ALB_RAW_FILE),
    ...
    243:                         )
    ```
    Y luego pasa el objeto `Bitmap` resultante al Composable. Si el archivo local no existe o la descarga en el servicio falló, el widget muestra el placeholder.

---

## AI3. Valor real, actual, del campo de portada en disco para la última canción reproducida

Basado en la lógica de `processSnapshot` (Stage 2) tras el Conjunto Artwork-Diag (`7f27757`):

El campo `artworkUri` de `MusicInfo` se llena con `snapshot.artworkUri` (Línea 2559), el cual procede directamente de los metadatos de la sesión multimedia (`METADATA_KEY_ART_URI` o `METADATA_KEY_ALBUM_ART_URI`).

Para apps como **Spotify**, este valor es una **URL remota** (ej. `https://i.scdn.co/image/...`). El log de instrumentación añadido en `7f27757` lo confirma:
```kotlin
2552: InternalLogger.d(applicationContext, "[ART_TRACE] Decisión final de portada: ..., valorElegido=$finalArtworkUri")
```

**Confirmación:** En disco (DataStore), el campo de portada para canciones de streaming queda guardado como una **URL de internet**, pero el widget **ignora este texto para el dibujo** y busca en su lugar los archivos físicos (`.webp`) que el servicio debió haber descargado previamente.
