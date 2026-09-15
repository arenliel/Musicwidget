# Verificación — Cierre Real del Conjunto Artwork-2

---

## AF1. Estadística real y completa del commit

**Comando:** `git show 079837e --stat`

**Resultado:**
```text
commit 079837eec31cf3d3f8a53f7da81c259934f2ad79 (HEAD -> master)
Author: arenliel <alvz.angel12@gmail.com>
Date:   Tue Sep 15 14:48:59 2026 -0400

    Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI

 .../musicwidget/MusicNotificationListener.kt       |  10 +-
 docs/reportes-ia/artwork_busqueda_historial.md     | 141 +++++++++++++
 docs/reportes-ia/artwork_ronda5.md                 | 101 +++++++++
 docs/reportes-ia/artwork_ronda6.md                 | 232 +++++++++++++++++++++
 docs/reportes-ia/artwork_ronda7.md                 |  76 +++++++
 docs/reportes-ia/artwork_ronda8.md                 |  38 ++++
 docs/reportes-ia/artwork_ronda9.md                 |  47 +++++
 7 files changed, 644 insertions(+), 1 deletion(-)
```

---

## AF2. Lectura directa del bloque actual

**Comando:** `sed -n '2540,2565p' MusicNotificationListener.kt`

**Resultado:**
```kotlin
2540:                     val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2541:                     val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
2542: 
2543:                     val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
2544: 
2545:                     // REGLA Artwork-1: Conservar identidad de portada si no hubo resolución
2546:                     val (finalArtworkKey, finalArtworkUri) = if (artIncoherent) {
2547:                         val sessionUUID = session?.sessionUUID
2548:                         val uri = if (resolvedArtwork != null && sessionUUID != null) {
2549:                             ArtworkStorageManager.saveHistoryArtwork(applicationContext, resolvedArtwork, sessionUUID)
2550:                         } else if (snapshot.trackKey == currentInfo.trackKey) {
2551:                             currentInfo.artworkUri
2552:                         } else {
2553:                             ""
2554:                         }
2555:                         snapshot.artworkKey to uri
2556:                     } else {
2557:                         currentInfo.artworkKey to currentInfo.artworkUri
2558:                     }
2559: 
2560:                     val finalMusicInfo = MusicInfo(
2561:                         title = snapshot.title,
2562:                         artist = snapshot.artist,
2563:                         packageName = snapshot.packageName,
2564:                         album = snapshot.album ?: "",
2565:                         trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
```
