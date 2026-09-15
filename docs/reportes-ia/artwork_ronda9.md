# Auditoría — Ronda 9 (Portadas): ¿Qué Identificador de Sesión Está Disponible Durante una Corrección en Caliente?

**Confirmación de Git Log:**
```
e54d9d0 (HEAD -> master) Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
998c2ef Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
```

---

## AD1. ¿Qué variable representa el identificador de sesión actual, disponible en el mismo punto donde se calcula `finalArtworkUri`?

Ubicada en `MusicNotificationListener.kt` (Línea 2572):

La variable que contiene el identificador único de la sesión actual es **`session?.sessionUUID`**.

**Evidencia de código (Líneas 2552 - 2575):**

```kotlin
2552:                     val finalMusicInfo = MusicInfo(
2553:                         title = snapshot.title,
2554:                         artist = snapshot.artist,
2555:                         packageName = snapshot.packageName,
2556:                         album = snapshot.album ?: "",
2557:                         trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
2558:                         artworkKey = finalArtworkKey,
2559:                         artworkUri = finalArtworkUri,
2560:                         appIconKey = savedAppIconKey ?: "",
2561:                         isPlaying = isPlaying,
2562:                         isSessionActive = snapshot.isSessionActive,
2563:                         currentLyric = finalLyric,
2564:                         lyricsTrackKey = finalLyricKey,
2565:                         playbackDeviceName = snapshot.playbackDeviceName,
2566:                         playbackDeviceType = snapshot.playbackDeviceType,
2567:                         durationMs = snapshot.durationMs,
2568:                         history = currentInfo.history,
2569:                         playsToday = playsToday,
2570:                         skipStreak = skipStreak,
2571:                         isFrequentArtist = isFrequent,
2572:                         sessionUUID = session?.sessionUUID ?: "",
2573:                         isPendingCommit = false,
2574:                         lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
2575:                     )
```

Este identificador (`sessionUUID`) es de tipo `String` y se encuentra disponible en el mismo ámbito de ejecución donde se construye el objeto `finalMusicInfo`.
