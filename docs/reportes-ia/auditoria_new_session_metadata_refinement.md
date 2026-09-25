# Auditoría — Confirmación final antes de unificar lyricsTrackKey

## 1. Resultados de Grep

### A. `grep -n "lyricsTrackKey =" MusicNotificationListener.kt MusicStateProvider.kt MusicWidget.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 2399: lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
  line 2461: lyricsTrackKey = finalLyricKey,
  line 2758: lyricsTrackKey = finalLyricKey,

C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt:
  line 77: lyricsTrackKey = if (lyricBelongsToSameSong) e.info.trackKey else "",
  line 89: val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyricsTrackKey,
  line 97: lyricsTrackKey = updatedLyricsTrackKey,
  line 121: lyricsTrackKey = e.trackKey
```

### B. `grep -n "class NewSession\|class MetadataRefinement" MusicStateProvider.kt`
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt:
  line 153: data class NewSession(val info: MusicInfo) : MusicUpdateEvent()
  line 154: data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: Long, val isPlaying: Boolean) : MusicUpdateEvent()
```

---

## 2. Definición completa de `NewSession` y `MetadataRefinement` (`MusicStateProvider.kt`)

```kotlin
    data class NewSession(val info: MusicInfo) : MusicUpdateEvent()
    data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: Long, val isPlaying: Boolean) : MusicUpdateEvent()
```
