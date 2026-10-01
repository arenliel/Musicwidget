# Reporte - Conjunto Letras-Atomicas-14

## 1. Salida de compilación (app:assembleDebug)
```
BUILD SUCCESSFUL in 1s
```

## 2. Busqueda de normalizeForSearch
`git grep -rn "normalizeForSearch" app/src/main`
```
(empty)
```

## 3. Usos de URLEncoder.encode en LyricsRepository.kt
`git grep -n "URLEncoder.encode" app/src/main/java/arenliel/musicwidget/LyricsRepository.kt`
```
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:228:            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
app/src/main/java/arenliel/musicwidget/LyricsRepository.kt:229:            val encodedTitle = URLEncoder.encode(title, "UTF-8")
```

## 4. Mensaje de Commit
```
Conjunto Letras-Atomicas-14: validacion de identidad sin distinguir tildes ni mayusculas; titulo y artista sin reformatear; solo un no encontrado confirmado se cachea; una sola consulta por cancion a la vez
```

## 5. Salida de `git show --stat HEAD`
```
commit df7dd6ae2fcbed47deee2b6f40e3c7b37605b837 (HEAD -> master, origin/master)
Author: arenliel <alvz.angel12@gmail.com>
Date:   Wed Sep 30 02:35:03 2026 -0400

    Conjunto Letras-Atomicas-14: validacion de identidad sin distinguir tildes ni mayusculas; titulo y artista sin reformatear; solo un no encontrado confirmado se cachea; una sola consulta por cancion a la vez

 .../java/arenliel/musicwidget/LyricsRepository.kt  | 161 ++++++++++++++++-----
 1 file changed, 124 insertions(+), 37 deletions(-)
```

## 6. Salida de líneas eliminadas (`git show HEAD -- app/src/main/java/arenliel/musicwidget/LyricsRepository.kt | grep "^-"`)
```
--- a/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt
-        val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
-
-        if (networkResult != null) {
-            lyricsDao.insertLyrics(
-                LyricsEntity(
-                    trackKey = trackKey,
-                    syncedLyrics = networkResult,
-                    plainLyrics = null,
-                    timestampFetched = now,
-                    lastAccessed = now,
-                    notFound = false
-            )
-            return@withContext parseLrc(trackKey, networkResult, durationMs)
-        } else {
-            // Conjunto Letras-Atomicas-9: sin duración confirmada, un "no encontrado"
-            // de LRCLIB no es confiable (catálogo con casi-duplicados por duración).
-            if (durationMs > 0) {
-            } else {
-                android.util.Log.d("LYRICS_RETRY_TRACE", "Fallo sin duración confirmada (durationMs=$durationMs): no se cachea notFound, trackKey=$trackKey")
-            return@withContext null
-    private fun normalizeForSearch(text: String): String {
-        return text.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
-    private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
-            val cleanArtist = URLEncoder.encode(normalizeForSearch(artist), "UTF-8")
-            val cleanTitle = URLEncoder.encode(normalizeForSearch(title), "UTF-8")
-            val urlString = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle$durationParam"
-
-
-            if (connection.responseCode == 200) {
-                val matchesRequest = normalizeForSearch(returnedTrack).equals(normalizeForSearch(title), ignoreCase = true) &&
-                    normalizeForSearch(returnedArtist).equals(normalizeForSearch(artist), ignoreCase = true)
-                    return@withContext null
-                if (syncedLyrics != null && !isPlausibleLyrics(syncedLyrics)) {
-                    return@withContext null
-                return@withContext syncedLyrics
-        null
```

## 7. GIT_SHA
```
df7dd6ae2fcbed47deee2b6f40e3c7b37605b837
```
