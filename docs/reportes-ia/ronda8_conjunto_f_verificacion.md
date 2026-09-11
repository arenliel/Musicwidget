# Auditoría — Ronda 8: Verificación Previa al Conjunto F

## Estado del Proyecto (git log)

```text
bf6f590 (HEAD -> master) Conjunto E: Implementación del Reloj de Verdad usando señales oficiales de PlaybackState
bb7b30a Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
```

---

## I1. Ubicación actual del Gatekeeper
Ubicación: `MusicNotificationListener.kt:1936`

```kotlin
        // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
        // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
        // abortamos para proteger el estado coherente en RAM y Disco.
        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        val isDegraded = rawSnapshot.durationMs <= 0L

        if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
            return
        }
```

## I2. Ubicación actual de las tres barreras de `contentKey`
Ubicación: `MusicNotificationListener.kt` (Dentro de `refreshBestSession`)

```kotlin
// Línea 1561
        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            lastObservedSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastObservedSnapshot?.contentKey}")
            return
        }

// Línea 1571
        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            inFlightSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${inFlightSnapshot?.contentKey}")
            return
        }

// Línea 1581
        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            lastAppliedSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastAppliedSnapshot?.contentKey}")
            return
        }
```

## I3. Ubicación actual de la deduplicación de RAM
Ubicación: `MusicNotificationListener.kt:1982`

```kotlin
        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        if (!isCatchUp && !trackContentChanged && !artIncoherent && 
            currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
            currentMem.isSessionActive == rawSnapshot.isSessionActive) {
            
            // REGLA B.1 (v9.1): Actualización de Verdad (Posición) previa al Redibujado.
            // Aseguramos que la marca de agua progrese aunque el refresco visual sea ignorado.
            session?.let { s ->
                s.maxPositionMs = Math.max(s.maxPositionMs, rawSnapshot.projectedPositionMs())
            }

            // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
            lastObservedSnapshot = rawSnapshot
            InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
            return
        }
```

## I4. Código completo de `isAppAllowed`
Ubicación: `MusicNotificationListener.kt:1866`

```kotlin
    private fun isAppAllowed(packageName: String): Boolean {
        // 0. Apps prohibidas explícitamente (Blacklist interna)
        val restrictedPackages = setOf(
            "org.kde.kdeconnect", "com.google.android.projection.gearhead", 
            "com.android.systemui", "com.google.android.apps.maps"
        )
        if (restrictedPackages.contains(packageName)) return false

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

## I5. Declaración de `MusicInfo`
Ubicación: `MusicDataStore.kt:23`

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
)
```

## I6. Control de la animación de ondas
Ubicación: `MusicWidget.kt` (En `VisualizerSelector`, línea 647)

```kotlin
    @Composable
    private fun VisualizerSelector(context: Context, info: MusicInfo, size: Dp) {
        val isFresh = isSessionFresh(info)
        when {
            info.isPlaying -> AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.layout_visualizer), modifier = GlanceModifier.size(size))
            isFresh && info.isSessionActive -> Image(provider = ImageProvider(R.drawable.ic_visualizer_paused), contentDescription = context.getString(R.string.content_desc_visualizer), modifier = GlanceModifier.size(size))
            else -> Box(modifier = GlanceModifier.size(size).background(GlanceTheme.colors.widgetBackground).cornerRadius(size / 2), contentAlignment = Alignment.Center) { Image(provider = ImageProvider(R.drawable.ic_music_history), contentDescription = context.getString(R.string.content_desc_visualizer), modifier = GlanceModifier.size(size * 0.85f)) }
        }
    }
```
**Confirmación:** La animación se activa leyendo directamente `info.isPlaying`. Si `isPlaying` es `false`, la animación de ondas se detiene (muestra `ic_visualizer_paused` o el icono de historial).

## I7. Ubicación de `getStatusText`
Ubicación: `MusicWidget.kt:572`

```kotlin
    private fun getStatusText(context: Context, info: MusicInfo): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val timeSinceLastUpdate = now - info.observedAtRealtime
        
        // MOTOR DE CONSCIENCIA TEMPORAL (v2.1)
        // Umbral de 15 minutos para considerar una sesión de pausa como "estancada" (stale).
        val PAUSE_STALE_THRESHOLD = 15 * 60 * 1000L

        return when {
            info.isPlaying -> context.getString(R.string.status_listening)
            
            // Si la sesión está en pausa, pero ha pasado el umbral, dejamos que pase al flujo de "Hace poco"
            info.isSessionActive && timeSinceLastUpdate < PAUSE_STALE_THRESHOLD -> 
                context.getString(R.string.status_paused)
            
            else -> { 
                val time = formatRelativeTime(context, info.observedAtRealtime)
                if (time.isEmpty()) context.getString(R.string.status_recently) else time 
            }
        }
    }
```
**Confirmación:** `getStatusText` se mantiene idéntico y en la misma ubicación que en la Ronda 6.
