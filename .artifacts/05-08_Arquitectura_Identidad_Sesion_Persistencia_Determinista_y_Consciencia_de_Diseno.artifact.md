# 05/08 - Arquitectura de Identidad de Sesión, Persistencia Determinista y Consciencia de Diseño en el Music Widget: Auditoría y Refactorización Integral del Sistema de Estado Multimedia

Este documento constituye el registro técnico exhaustivo y definitivo de la sesión de trabajo iniciada el **5 de agosto de 2026**. Recoge la evolución del sistema de información de estado del widget, desde el análisis inicial de su lógica hasta la implementación de un sistema de seguridad basado en identidades digitales y optimización de layouts.

---

## 1. Contexto Inicial y Auditoría de Problemas

La sesión comenzó con el objetivo de desglosar la lógica del componente `textinfo`, encargado de mostrar el estado de la reproducción ("Escuchando", "En pausa", "Hace X horas") en las diferentes apariencias del widget.

### El Problema Identificado
Mediante una auditoría de código y la integración de feedback externo, detectamos dos fallos críticos en la arquitectura original:

1.  **Corrupción de Identidad (Apps Intrusas):** El widget reaccionaba a cualquier notificación de transporte multimedia del sistema. Si una aplicación fuera de la lista de música (ej. un navegador o una app de video) emitía un evento, "ensuciaba" el estado del widget, llegando a mostrar estados de pausa o reproducción que no pertenecían a la sesión musical real.
2.  **El Bug del "Reseteo del Reloj":** El campo `lastUpdate` (que calcula el tiempo relativo "Hace X horas") se reiniciaba indiscriminadamente. Al reabrir una app pausada hace horas, el sistema enviaba una actualización que el widget interpretaba como "Reciente", borrando la marca de tiempo original de inactividad.

---

## 2. Decisiones Arquitectónicas y Soluciones Elegidas

Para resolver estos problemas sin comprometer el rendimiento, se tomaron las siguientes decisiones de diseño:

*   **Identidad Basada en Llaves Digitales:** Reforzar el uso del `trackKey` (un hash único basado en Paquete + Título + Artista + Duración) como el único validador de que una pieza de información pertenece a la sesión activa.
*   **Filtro Temprano (Allow-list):** Mover la seguridad al inicio del pipeline. Si una aplicación no es reconocida como un reproductor válido, el sistema aborta el procesamiento antes de tocar la memoria o el disco.
*   **Persistencia Granular:** Dividir la actualización de datos en tres categorías (Identidad, Pulso y Metadatos) para que solo los cambios reales de reproducción afecten al reloj de inactividad.
*   **Layout Awareness (Consciencia de Diseño):** Eliminar redundancias visuales haciendo que el widget Standard (compacto) y el Large (expandido) gestionen el espacio de forma complementaria pero distinta.

### Decisiones Descartadas
1.  **Micro-ajustes de altura (`isShort`):** Se descartó implementar cálculos manuales de altura para ocultar textos en el widget Large. **Razón:** Era redundante e inútil, ya que el sistema de detección de colisiones global ya se encarga de cambiar el traje del widget (a Portada Completa) si el espacio vertical es insuficiente.
2.  **Cálculo de Ancho Horizontal Manual:** Se descartó ocultar el estado superior basado en el ancho disponible. **Razón:** El diseño debe ser estable; si el widget está en modo "Large", es porque los buckets de tamaño ya validaron que tiene espacio suficiente.

---

## 3. Implementación: Seguridad y Filtro de Identidad

Se modificó el servicio `MusicNotificationListener.kt` para actuar como un "portero" estricto.

### Código Completo: Filtro de Aplicaciones Permitidas
Esta función determina si una aplicación tiene permiso para interactuar con el widget basándose en su categoría de sistema y declaración de servicios.

```kotlin
    private fun isAppAllowed(packageName: String): Boolean {
        // 1. Apps conocidas que siempre permitimos (Fallback robusto)
        val commonMusicPackages = setOf(
            "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android",
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.deezer.android",
            "com.tidal.android", "com.pandora.android", "com.musicolet", "com.hiby.music"
        )
        if (commonMusicPackages.contains(packageName)) return true

        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)

            // 2. Por categoría de sistema (Android 8.0+)
            val isMediaCategory = appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
                    appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
            if (isMediaCategory) return true

            // 3. Por servicios multimedia declarados
            val mediaIntent = android.content.Intent("android.media.browse.MediaBrowserService")
            val mediaApps = pm.queryIntentServices(mediaIntent, 0).map { it.serviceInfo.packageName }
            if (mediaApps.contains(packageName)) return true

            false
        } catch (e: Exception) {
            false
        }
    }
```

### Integración en el Pipeline
Se insertó el filtro al inicio de `processSnapshot` para proteger todo el sistema:

```kotlin
        // FILTRO DE IDENTIDAD (Allow-list): Solo procesamos apps de música/video permitidas.
        if (!isAppAllowed(snapshot.packageName)) {
            Log.d(TAG, "[DIAGNOSTIC] ABORT: App ${snapshot.packageName} no es un reproductor permitido.")
            return
        }

        val currentBlacklist = musicDataStore.musicInfoFlow.first().blacklist
        if (currentBlacklist.contains(snapshot.packageName)) {
            Log.d(TAG, "[DIAGNOSTIC] ABORT: App ${snapshot.packageName} está en la lista negra.")
            return
        }
```

---

## 4. Implementación: Persistencia Determinista (`lastUpdate`)

El cambio más profundo se realizó en `MusicDataStore.kt` para evitar que el reloj de inactividad se resetee por eventos triviales o "sesiones fantasma".

### Código Completo: Lógica de Guardado con Protección de Reloj

```kotlin
    suspend fun saveMusicInfo(
        info: MusicInfo,
        forceUpdate: Boolean = false
    ) {
        context.dataStore.edit { prefs ->
            val currentTitle = prefs[TITLE] ?: DEFAULT_TITLE
            val currentArtist = prefs[ARTIST] ?: DEFAULT_ARTIST
            val currentPackageName = prefs[PACKAGE_NAME].orEmpty()
            val currentTrackKey = prefs[TRACK_KEY].orEmpty()
            val currentArtworkKey = prefs[ARTWORK_KEY].orEmpty()
            val currentArtworkUri = prefs[ARTWORK_URI].orEmpty()
            val currentAppIconKey = prefs[APP_ICON_KEY].orEmpty()
            val currentIsPlaying = prefs[IS_PLAYING] ?: false
            val currentIsSessionActive = prefs[IS_SESSION_ACTIVE] ?: false
            val currentLyric = prefs[CURRENT_LYRIC].orEmpty()
            val currentLyricsTrackKey = prefs[LYRICS_TRACK_KEY].orEmpty()
            val currentShowLyrics = prefs[SHOW_LYRICS] ?: true
            val currentPlaybackDeviceName = prefs[PLAYBACK_DEVICE_NAME].orEmpty()

            // 1. CAMBIO DE IDENTIDAD (Requiere reset de reloj)
            val identityChanged = currentTitle != info.title ||
                    currentArtist != info.artist ||
                    currentPackageName != info.packageName ||
                    currentTrackKey != info.trackKey ||
                    currentArtworkKey != info.artworkKey ||
                    currentArtworkUri != info.artworkUri

            // 2. CAMBIO DE ESTADO DE REPRODUCCIÓN (Requiere reset de reloj)
            // Solo reseteamos si el estado de Play/Pause cambió realmente
            // o si la sesión se cerró definitivamente.
            // NOTA: Si la sesión se reabre (false -> true) pero sigue en PAUSA, no reseteamos el reloj
            // para mantener el "Hace X horas" verídico.
            val playbackStatusChanged = currentIsPlaying != info.isPlaying ||
                    (currentIsSessionActive && !info.isSessionActive) ||
                    currentPlaybackDeviceName != info.playbackDeviceName

            // 3. CAMBIO DE METADATOS SECUNDARIOS (NO requiere reset de reloj)
            val metadataOnlyChanged = currentAppIconKey != info.appIconKey ||
                    currentLyric != info.currentLyric ||
                    currentLyricsTrackKey != info.lyricsTrackKey ||
                    currentShowLyrics != info.showLyrics

            val hasAnyChange = identityChanged || playbackStatusChanged || metadataOnlyChanged

            if (!hasAnyChange && !forceUpdate) {
                return@edit
            }

            prefs[TITLE] = info.title
            prefs[ARTIST] = info.artist
            prefs[PACKAGE_NAME] = info.packageName
            prefs[TRACK_KEY] = info.trackKey
            prefs[ARTWORK_KEY] = info.artworkKey
            prefs[ARTWORK_URI] = info.artworkUri
            prefs[APP_ICON_KEY] = info.appIconKey
            prefs[IS_PLAYING] = info.isPlaying
            prefs[IS_SESSION_ACTIVE] = info.isSessionActive
            prefs[CURRENT_LYRIC] = info.currentLyric
            prefs[LYRICS_TRACK_KEY] = info.lyricsTrackKey
            prefs[SHOW_LYRICS] = info.showLyrics
            prefs[PLAYBACK_DEVICE_NAME] = info.playbackDeviceName

            // EL CORAZÓN DE LA LÓGICA:
            // lastUpdate solo se modifica si la identidad cambió o el estado de reproducción cambió de forma significativa.
            if (identityChanged || playbackStatusChanged || forceUpdate) {
                prefs[LAST_UPDATE] = System.currentTimeMillis()
            }
        }
    }
```

---

## 5. Implementación: Consciencia de Diseño en la UI

Se refactorizó el componente `TextInfo` y el layout `Large` para eliminar redundancias y mejorar la claridad informativa.

### Código Completo: Función de Prioridad `TextInfo`
Esta función es el cerebro visual que decide qué texto ocupa el campo del artista según el contexto del widget.

```kotlin
    @Composable
    private fun TextInfo(context: Context, info: MusicInfo, appIconBitmap: Bitmap?, showRelativeTime: Boolean, isIconSynchronized: Boolean, maxArtistLines: Int = 1) {
        val titleSize = spDimen(R.dimen.text_size_title); val artistSize = spDimen(R.dimen.text_size_artist); val fontScale = context.resources.configuration.fontScale
        val isHugeFont = fontScale > 1.3f
        Column(modifier = GlanceModifier.clickable(actionStartActivity(context.packageManager.getLaunchIntentForPackage(info.packageName) ?: android.content.Intent(context, ArtworkDetailActivity::class.java).apply { putExtra("artwork_uri", info.artworkUri); putExtra("artwork_key", info.artworkKey) }))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                appIconBitmap?.let { icon -> if (!isHugeFont) { Image(provider = ImageProvider(icon), contentDescription = context.getString(R.string.content_desc_app_icon), colorFilter = if (isIconSynchronized) ColorFilter.tint(GlanceTheme.colors.primary) else null, modifier = GlanceModifier.size(14.dp)); Spacer(GlanceModifier.size(6.dp)) } }
                Text(text = info.title, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = titleSize, color = GlanceTheme.colors.onSurface), maxLines = 1)
            }
            val artistText = when {
                info.title == context.getString(R.string.widget_empty_title) -> info.artist

                // MODO HISTORIAL (Sesión cerrada): Solo mostramos el tiempo si el widget no tiene espacio para el estado arriba (Layout Awareness)
                showRelativeTime && !info.isSessionActive -> {
                    val time = formatRelativeTime(context, info.lastUpdate)
                    if (time.isEmpty()) context.getString(R.string.status_recently) else time
                }

                // MODO SESIÓN: Alternancia de letras (solo si hay sesión activa)
                info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> "“${info.currentLyric}”"

                // FALLBACK: Nombre del Artista original
                else -> info.artist
            }
            Text(text = artistText, style = TextStyle(fontSize = artistSize, color = if (artistText.startsWith("“")) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant, fontStyle = if (artistText.startsWith("“")) androidx.glance.text.FontStyle.Italic else androidx.glance.text.FontStyle.Normal), maxLines = if (isHugeFont) 1 else maxArtistLines)
        }
    }
```

### Código Completo: Layout Large (4x4) Simplificado
Tras eliminar el modo `isShort`, el layout Large se volvió estructuralmente estable y robusto.

```kotlin
    @Composable
    private fun Layout4x4(context: Context, info: MusicInfo, albumArtBitmap: Bitmap?, appIconBitmap: Bitmap?, isArtworkSynchronized: Boolean, isIconSynchronized: Boolean, pillSize: Dp, maxLines: Int = 1) {
        val size = LocalSize.current
        val showHistory = size.height.value >= 150f
        val widgetPadding = dimen(R.dimen.widget_padding)
        val statusTextSize = spDimen(R.dimen.text_size_status)

        Box(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
            // Estado superior siempre visible en Large
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Text(text = getStatusText(context, info), style = TextStyle(fontSize = statusTextSize, color = GlanceTheme.colors.onSurfaceVariant))
            }
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(modifier = if (showHistory) GlanceModifier.fillMaxWidth() else GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, pillSize)
                    Column(modifier = GlanceModifier.defaultWeight().padding(start = 22.dp, top = if (showHistory) 32.dp else 8.dp)) {
                        // showRelativeTime = false garantiza que no haya redundancia en el modo historial
                        TextInfo(context, info, appIconBitmap, showRelativeTime = false, isIconSynchronized = isIconSynchronized, maxArtistLines = if(showHistory) 2 else maxLines)
                    }
                }
                if (showHistory) {
                    Spacer(GlanceModifier.size(16.dp))
                    Box(modifier = GlanceModifier.defaultWeight()) { HistoryList(context, info.history) }
                }
            }
        }
    }
```

---

## 6. Resultados y Verificación Final

Al finalizar la sesión, el widget presenta un comportamiento determinista basado en los siguientes estados:

### Tabla de Comportamiento Final

| Apariencia | Modo | Campo Artista/Letra | Campo Estado (Sup. Der.) |
| :--- | :--- | :--- | :--- |
| **Standard (Pill)** | Sesión Activa | Artista / Letra (Alternando) | N/A |
| **Standard (Pill)** | Historial | **"— Hace X horas"** | N/A |
| **Large (2x4)** | Sesión Activa | Artista / Letra (Alternando) | "Escuchando" / "En pausa" |
| **Large (2x4)** | Historial | **Nombre del Artista** | **"Hace X horas"** |
| **Full Bleed** | Sesión Activa | Artista / Letra (Alternando) | N/A |
| **Full Bleed** | Historial | **"— Hace X horas"** | N/A |

### Resumen de la Arquitectura de Seguridad
Toda la información visual está ahora blindada por:
1.  **Filtro de Confianza:** Bloquea apps intrusas.
2.  **Validación de Llaves Digitales:** Asegura que la portada e icono coincidan con el `trackKey`.
3.  **Persistencia Granular:** Protege la veracidad del tiempo relativo `lastUpdate`.

---
*Fin del registro técnico de la sesión.*
