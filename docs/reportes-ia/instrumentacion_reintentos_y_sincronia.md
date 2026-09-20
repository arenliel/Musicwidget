# Reporte de Instrumentación — Reintentos de Letras y Sincronía de Portada

## Información de Commit
- **GIT_SHA:** `bdb664c40d33668e694b4edde5c8e1073a9d1d13`
- **Estado de compilación:** Exitoso (`:app:assembleDebug`)

---

## Verificación de Líneas Insertadas (Verbatim)

### 1. Letras: Inicio de sincronización pasiva
**Archivo:** `MusicNotificationListener.kt` (Línea 2471)
```kotlin
InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=${currentLyrics == null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
```

### 2. Letras: Resultado de reintento
**Archivo:** `MusicNotificationListener.kt` (Línea 2474)
```kotlin
InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Resultado de reintento: exito=${currentLyrics != null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
```

### 3. Letras: Pre-shouldResync
**Archivo:** `MusicNotificationListener.kt` (Línea 2480)
```kotlin
InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${currentLyrics == null}")
```

### 4. Letras: TTL bloqueando reintento
**Archivo:** `LyricsRepository.kt` (Línea 27)
```kotlin
android.util.Log.d("LYRICS_RETRY_TRACE", "TTL bloqueando reintento: trackKey=$trackKey, msDesdeUltimoIntento=${now - cached.timestampFetched}")
```

### 5. Letras: relaunchLyricsTicker invocado
**Archivo:** `MusicNotificationListener.kt` (Línea 2655)
```kotlin
InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${currentLyrics == null}")
```

### 6. Portada: displayedInfo leído
**Archivo:** `MusicWidget.kt` (Línea 230)
```kotlin
InternalLogger.d(context, "[ART_SYNC_TRACE] displayedInfo leído: artworkKey=${displayedInfo.artworkKey}, sessionUUID=${displayedInfo.sessionUUID}, timestamp=${System.currentTimeMillis()}")
```

### 7. Portada: Archivo escrito y confirmado
**Archivo:** `MusicNotificationListener.kt` (Líneas 3165 y 3168)
```kotlin
Log.d(TAG, "[ART_SYNC_TRACE] Archivo escrito y confirmado: fileName=$fileName, texto=$text, timestamp=${System.currentTimeMillis()}")
```

---

## Origen de `displayedInfo` en `MusicWidget.kt`

```kotlin
// Archivo: MusicWidget.kt (Líneas 225-231)
            // MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en visual.
            // Gestiona automáticamente el estado vacío y la lista negra.
            val displayedInfo = musicInfo.copy(
                notificationsEnabled = notificationsEnabled, 
                batteryOptimized = batteryOptimized
            ).toDisplayedState(context)
            InternalLogger.d(context, "[ART_SYNC_TRACE] displayedInfo leído: artworkKey=${displayedInfo.artworkKey}, sessionUUID=${displayedInfo.sessionUUID}, timestamp=${System.currentTimeMillis()}")
```

---

## Verificación de Anomalías (Cruce de Timestamps)

- **Caught in a Jam:**
    - Sesión creada: `11:29:03`
    - Fallo sincronía: `11:29:15`
    - Error Glance 1113: `11:29:49`
    - **Resultado:** Ausencia de error Glance en margen ±5s.
- **Close 2 Me:**
    - Sesión creada: `11:28:41`
    - Error Glance 1111: `11:26:58`
    - **Resultado:** Ausencia de error Glance en margen ±5s.
- **Black-Eyed Susan Climb:**
    - Sesión creada: `11:39:27`
    - **Resultado:** Ausencia de error Glance en margen ±5s.
