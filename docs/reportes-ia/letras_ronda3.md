# Auditoría — Ronda 3: Multiplicidad de Guardianes sobre `currentLyric`/`lyricsTrackKey`

Confirmando el estado del proyecto:
```
6e5278f (HEAD -> master) Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
77ff7a9 Conjunto F.1: Andamiaje del estado Cargando (Buffering)
bf6f590 Conjunto E: Implementación del Reloj de Verdad usando señales oficiales de PlaybackState
bb7b30a Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
```

---

## M1. Código completo del bloque "FAST-TRACK SSOT" / construcción de `finalMusicInfo` en Stage 2

Este bloque reside en `processSnapshot` dentro de `MusicNotificationListener.kt`. Muestra cómo se construye el objeto de persistencia y cómo se reconcilia con la RAM.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2517-2568)

```kotlin
                    val currentInfo = musicDataStore.musicInfoFlow.first()
                    val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                    val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                    
                    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                    val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                    val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)

                    val finalMusicInfo = MusicInfo(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        packageName = snapshot.packageName,
                        album = snapshot.album ?: "",
                        trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                        artworkKey = snapshot.artworkKey,
                        artworkUri = snapshot.artworkUri ?: "",
                        appIconKey = savedAppIconKey ?: "",
                        isPlaying = isPlaying,
                        isSessionActive = snapshot.isSessionActive,
                        currentLyric = finalLyric,
                        lyricsTrackKey = finalLyricKey,
                        playbackDeviceName = snapshot.playbackDeviceName,
                        playbackDeviceType = snapshot.playbackDeviceType,
                        durationMs = snapshot.durationMs,
                        history = currentInfo.history,
                        playsToday = playsToday,
                        skipStreak = skipStreak,
                        isFrequentArtist = isFrequent,
                        sessionUUID = session?.sessionUUID ?: "",
                        isPendingCommit = false,
                        lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                    )

                    // 1. Sincronía Atómica: Disco -> RAM -> UI
                    val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
                    
                    if (artworkTimedOut) {
                        Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
                    }
                    
                    // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
                    // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
                    val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo))

                    // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
                    // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
                    
                    if (changedDisco || isSessionEnded || changedRAM) {
                        if (isSessionEnded) {
                            uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
                        } else {
                            uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                        }
                    }
```

---

## M2. ¿De dónde lee el Composable del widget su `MusicInfo`?

El widget utiliza un mecanismo de prioridad de memoria sobre disco para evitar latencia de I/O, pero aplica una transformación final antes de pintar.

**Archivo:** [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt) (Líneas 196-228)

```kotlin
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = MusicDataStore(context)
        
        provideContent {
            // FAST-TRACK SSOT (v2.0): Priorizamos la memoria sobre el disco
            val memInfo by MusicStateProvider.musicInfoState.collectAsState()
            val diskInfo by dataStore.musicInfoFlow.collectAsState(
                initial = MusicNotificationListener.getLatestMusicInfo() ?: MusicInfo(title = "", artist = "", packageName = "")
            )

            val musicInfo = memInfo ?: diskInfo
            
            // ... (evaluación de sensor y assets) ...

            // MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en visual.
            // Gestiona automáticamente el estado vacío y la lista negra.
            val displayedInfo = musicInfo.copy(
                notificationsEnabled = notificationsEnabled, 
                batteryOptimized = batteryOptimized
            ).toDisplayedState(context)
            
            // ... (resto de provideContent) ...
        }
    }
```

**Confirmación:** Lee prioritariamente de `MusicStateProvider` (RAM). Si es nulo, cae a `dataStore.musicInfoFlow` (Disco). Crucialmente, aplica `toDisplayedState(context)` antes de asignar los datos a las variables de dibujo (`displayedInfo`).

---

## M3. Código completo de la "purga por desincronización de sesión"

Esta lógica reside en el "Motor de Presentación" de `MusicDataStore.kt`.

**Archivo:** [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt) (Líneas 186-208)

```kotlin
    /**
     * MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en el estado visual para el widget.
     * Centraliza la lógica de "Estado Vacío" y filtrado de lista negra.
     */
    fun toDisplayedState(context: Context): MusicInfo {
        return if (title.isEmpty() || blacklist.contains(packageName)) {
            this.copy(
                title = context.getString(R.string.widget_empty_title),
                artist = context.getString(R.string.widget_empty_subtitle),
                packageName = "",
                trackKey = "",
                artworkKey = "",
                artworkUri = "",
                appIconKey = "",
                isPlaying = false,
                isSessionActive = false,
                currentLyric = "",
                lyricsTrackKey = ""
            )
        } else {
            this
        }
    }
```

---

## M4. Estructura de datos de una entrada de letra sincronizada

Declaración de la clase utilizada para almacenar cada verso.

**Archivo:** [LyricsRepository.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt) (Línea 14)

```kotlin
data class LyricsEntry(val timestampMs: Long, val text: String)
```

**Confirmación:** La estructura **no tiene timestamp de fin**. La duración de un verso es implícita (dura hasta que el siguiente verso comienza). Esto explica por qué el widget depende exclusivamente del ticker visual para "limpiar" la pantalla mediante la lógica de silencios inteligentes de 15 segundos.

---
**Nota:** Este documento contiene únicamente evidencia de código extraída mediante auditoría técnica. No se han realizado cambios funcionales.
