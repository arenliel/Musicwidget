# Evidencia Cruda — Estudio de Letras y Dispatcher de UI

## 1. Resultados de Grep

### A. `grep -n "updateLyricInWidget" MusicNotificationListener.kt MusicWidget.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 2595: updateLyricInWidget(MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist), "")
  line 2882: updateLyricInWidget(myTrackKey, entry.text)
  line 2900: updateLyricInWidget(myTrackKey, "")
  line 2914: updateLyricInWidget(myTrackKey, "")
  line 2938: updateLyricInWidget(myTrackKey, text)
  line 2945: private fun updateLyricInWidget(trackKey: String, lyric: String) {
```

### B. `grep -n "currentLyric\|lyricText\|displayedLyric\|letraActual\|letra_actual" MusicWidget.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt:
  line 741: info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && MusicDataStore.computeSessionIdentity(info.packageName, info.title, info.artist) == info.lyricsTrackKey -> "“${info.currentLyric}”"
```

### C. `grep -n "class StatusUpdate\|UpdateEvent.StatusUpdate\|fun.*StatusUpdate" MusicNotificationListener.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 640: val changed = MusicStateProvider.applyEvent(MusicUpdateEvent.StatusUpdate(
  line 646: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 734: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 1163: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 1328: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 1466: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 1592: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 2029: val bufferingEvent = MusicUpdateEvent.StatusUpdate(
  line 2036: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 2419: MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType, isBuffering = false)
  line 2423: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 2790: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 2949: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
  line 3413: uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
```

### D. `grep -n "fun provideGlance\|GlanceStateDefinition\|class.*WidgetState\|data class MusicInfo" MusicWidget.kt MusicNotificationListener.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt:
  line 192: override suspend fun provideGlance(context: Context, id: GlanceId) {
```

### E. `grep -n "TextInfo" MusicWidget.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt:
  line 139: private const val TEXT_SPACERS_TOTAL_DP = 6f   // Suma de spacers en TextInfo (4dp + 2dp)
  line 383: TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, isStatusLabelVisible = isStatusLabelVisible, part = part)
  line 409: TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, isStatusLabelVisible = isStatusLabelVisible, part = part)
  line 413: TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, isStatusLabelVisible = isStatusLabelVisible, part = part)
  line 635: Box(modifier = GlanceModifier.defaultWeight()) { TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = 2, isStatusLabelVisible = false) }
  line 693: private fun TextInfo(context: Context, info: MusicInfo, appIconBitmap: Bitmap?, showRelativeTime: Boolean, isIconSynchronized: Boolean, maxArtistLines: Int, isStatusLabelVisible: Boolean = true, part: TextPart = TextPart.ALL) {
```

---

## 2. Código Verbatim y Completo

### A. Cuerpo completo de `updateLyricInWidget` (`MusicNotificationListener.kt`)
```kotlin
    private fun updateLyricInWidget(trackKey: String, lyric: String) {
        // Relevo Atómico (v4.0)
        serviceScope.launch {
            if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
        }
    }
```

### B. Manejador/Dispatcher que procesa `UpdateEvent.StatusUpdate` y registra `[DIAGNOSTIC] UI_DISPATCHER` (`MusicNotificationListener.kt`)
```kotlin
    @OptIn(FlowPreview::class)
    private fun startUiUpdateDispatcher() {
        serviceScope.launch {
            uiUpdateFlow
                .debounce(150L) // Consolidación estricta de ráfagas (v5.3)
                .collect { event ->
                    // REGLA DE ORO (v2.9): Hiato total en reposo para ahorro de batería (Batería Cero)
                    if (!isWidgetPotentiallyVisible()) {
                        InternalLogger.d(applicationContext, "[DIAGNOSTIC] UI_DISPATCHER: Widget no visible. Postponing update.")
                        hasPendingUpdates = true
                        // Hallazgo v3.5: Cancelación física del Ticker en reposo
                        lyricsUpdateJob?.cancel()
                        return@collect
                    }

                    // Hallazgo v3.5: Recuperación proactiva del Ticker al despertar (ACTION_USER_PRESENT indirecto)
                    if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
                        relaunchLyricsTicker("screen_wake")
                    }

                    InternalLogger.d(applicationContext, "[DIAGNOSTIC] UI_DISPATCHER: Ejecutando actualización atómica de Glance (Event=$event)")
                    runCatching {
                        MusicWidget.updateAll(applicationContext)
                    }.onFailure { e ->
                        Log.w(TAG, "Fallo al actualizar Glance (Posible desincronización de widget info)", e)
                    }
                }
        }
    }
```

### C. Composable `TextInfo` (`MusicWidget.kt`)
```kotlin
    @Composable
    private fun TextInfo(context: Context, info: MusicInfo, appIconBitmap: Bitmap?, showRelativeTime: Boolean, isIconSynchronized: Boolean, maxArtistLines: Int, isStatusLabelVisible: Boolean = true, part: TextPart = TextPart.ALL) {
        val titleSize = spDimen(R.dimen.text_size_title); val artistSize = spDimen(R.dimen.text_size_artist)
        val fontScale = context.resources.configuration.fontScale; val isHugeFont = fontScale > 1.3f
        
        // Bloque de metadatos con anclaje dinámico según el segmento (v1.6.0)
        Column(
            modifier = GlanceModifier.clickable(actionStartActivity(context.packageManager.getLaunchIntentForPackage(info.packageName) ?: android.content.Intent(context, ArtworkDetailActivity::class.java).apply { putExtra("artwork_uri", info.artworkUri); putExtra("artwork_key", info.artworkKey) })),
            verticalAlignment = if (part == TextPart.BOTTOM) Alignment.Top else Alignment.Bottom
        ) {
            if (part == TextPart.ALL || part == TextPart.TOP) {
                // 1. PlaybackStatusIndicator (Supresión en estado vacío)
                if (isStatusLabelVisible && !info.isEmpty) { 
                    PlaybackStatusIndicator(info, context)
                    Spacer(GlanceModifier.size(4.dp)) 
                }
                
                // 2. Row con icono de la app y Título de la canción
                Row(verticalAlignment = Alignment.CenterVertically) {
                    appIconBitmap?.let { icon -> 
                        if (!isHugeFont && !info.isEmpty) { 
                            Image(provider = ImageProvider(icon), contentDescription = context.getString(R.string.content_desc_app_icon), colorFilter = if (isIconSynchronized) ColorFilter.tint(GlanceTheme.colors.primary) else null, modifier = GlanceModifier.size(14.dp))
                            Spacer(GlanceModifier.size(6.dp)) 
                        } 
                    }
                    Text(
                        text = info.title, 
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = titleSize, color = GlanceTheme.colors.onSurface), 
                        maxLines = 1
                    )
                }
            }
            
            if (part == TextPart.ALL) {
                Spacer(GlanceModifier.size(2.dp))
            }
            
            if (part == TextPart.ALL || part == TextPart.BOTTOM) {
                // 3. Texto de Artista/Letras/Tiempo
                val isSnapshot = showRelativeTime && !info.isSessionActive
                val artistText = when {
                    info.isEmpty -> info.artist
                    info.title == context.getString(R.string.widget_empty_title) -> info.artist
                    isSnapshot && !isStatusLabelVisible -> { val time = formatRelativeTime(context, info.lastUpdateEpoch); if (time.isEmpty()) info.artist else "${info.artist} • $time" }
                    // Conjunto Letras-Atomicas-1: `info.trackKey` (4 partes, incluye duración)
                    // nunca debió compararse contra `info.lyricsTrackKey` (en realidad
                    // sessionIdentity, 3 partes, sin duración) — casi nunca podían coincidir.
                    // Se calcula la identidad de negocio fresca, igual que ya hace
                    // reconcileLyric() al validar una letra entrante.
                    info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && MusicDataStore.computeSessionIdentity(info.packageName, info.title, info.artist) == info.lyricsTrackKey -> "“${info.currentLyric}”"
                    else -> info.artist
                }
                
                // INDICADOR DE FIDELIDAD DEL ARTISTA (Corazón ❤️)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isSnapshot && !artistText.startsWith("“") && info.isFrequentArtist) {
                        Image(
                            provider = ImageProvider(R.drawable.favorite_24px),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(12.dp)
                        )
                        Spacer(modifier = GlanceModifier.size(4.dp))
                    }
                    
                    Text(
                        text = artistText, 
                        style = TextStyle(
                            fontSize = artistSize, 
                            color = if (artistText.startsWith("“")) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant, 
                            fontStyle = if (artistText.startsWith("“")) androidx.glance.text.FontStyle.Italic else androidx.glance.text.FontStyle.Normal
                        ), 
                        maxLines = maxArtistLines 
                    )
                }
            }
        }
    }
```

### D. Definición de la estructura de datos `MusicInfo` (`MusicDataStore.kt`)
```kotlin
data class MusicInfo(
    val title: String,
    val artist: String,
    val packageName: String,
    val album: String = "",
    val trackKey: String = "",
    val sessionUUID: String = "",
    val artworkKey: String = "",
    val artworkUri: String = "",
    val lastUpdateEpoch: Long = 0L,
    val observedAtRealtime: Long = 0L,
    val appIconKey: String = "",
    val currentLyric: String = "",
    val lyricsTrackKey: String = "",
    val showLyrics: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val batteryOptimized: Boolean = true,
    val blacklist: Set<String> = emptySet(),
    val isPlaying: Boolean = false,
    val isSessionActive: Boolean = true,
    val isBuffering: Boolean = false,
    val playbackDeviceName: String = "",
    val playbackDeviceType: Int = 0,
    val durationMs: Long = 0L,
    val history: List<HistoryItem> = emptyList(),
    val playsToday: Int = 0,
    val streakDays: Int = 0,
    val skipStreak: Int = 0,
    val isFrequentArtist: Boolean = false,
    val isPendingCommit: Boolean = false,
    val lastMaxPositionMs: Long = 0L,
    val identitySchemaVersion: Int = 0
) {
    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank()
    val coreKey: String get() = "${MusicDataStore.normalize(title)}|${MusicDataStore.normalize(artist)}"
}
```

### E. Definición de Contenedores de Estado
1. **Memoria efímera (RAM / SSOT)**: `MusicStateProvider` administra `_musicInfoState` (`MutableStateFlow<MusicInfo>`), actualizado atómicamente en memoria mediante `applyEvent(...)`.
2. **Persistencia (Disco)**: `MusicDataStore` (`musicInfoFlow`, respaldado por Jetpack DataStore Preferences).
