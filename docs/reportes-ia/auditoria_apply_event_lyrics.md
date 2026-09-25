# Auditoría — MusicStateProvider.applyEvent

## 1. Resultado de Grep
```text
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:
  line 2948: if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt:
  line 33: suspend fun applyEvent(event: MusicUpdateEvent): Boolean = mutationMutex.withLock {
  line 39: is MusicUpdateEvent.LyricTick -> reconcileLyric(current, event)
  line 110: private fun reconcileLyric(current: MusicInfo, e: MusicUpdateEvent.LyricTick): MusicInfo {
  line 152: sealed class MusicUpdateEvent {
  line 156: data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/old_mnl.kt:
  line 2704: if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
```

---

## 2. Firma y Cuerpo Completo de `applyEvent` (`MusicStateProvider.kt`)

```kotlin
    suspend fun applyEvent(event: MusicUpdateEvent): Boolean = mutationMutex.withLock {
        val current = _musicInfoState.value
        val next = when (event) {
            is MusicUpdateEvent.NewSession -> reconcileNewSession(current, event)
            is MusicUpdateEvent.MetadataRefinement -> reconcileRefinement(current, event)
            is MusicUpdateEvent.ArtworkResolved -> reconcileArtwork(current, event)
            is MusicUpdateEvent.LyricTick -> reconcileLyric(current, event)
            is MusicUpdateEvent.SessionEnded -> reconcileEnd(current, event)
            is MusicUpdateEvent.StatusUpdate -> reconcileStatus(current, event)
            is MusicUpdateEvent.ClearVisualHistory -> current.copy(history = emptyList())
        }

        // Full-object equality check — this intentionally catches ANY field change, including
        // isPlaying, so a real play/pause transition always triggers a UI notification.
        if (next == current) return@withLock false
        
        _musicInfoState.value = next
        return@withLock true
    }
```

---

## 3. Definición Completa de `MusicUpdateEvent.LyricTick`

```kotlin
data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
```
