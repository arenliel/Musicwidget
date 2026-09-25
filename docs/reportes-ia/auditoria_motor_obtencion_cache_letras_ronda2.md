# AUDITORÍA — MOTOR DE OBTENCIÓN Y CACHÉ DE LETRAS (RONDA 2: CUERPO VERBATIM)

## 1. `suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult?`
```kotlin
    suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Intentar desde Room
        val cached = lyricsDao.getLyrics(trackKey)
        if (cached != null) {
            val now = System.currentTimeMillis()
            if (cached.notFound) {
                // TTL 1h para re-intentos de letras (v3.0)
                if (now - cached.timestampFetched < 1 * 60 * 60 * 1000L) {
                    android.util.Log.d("LYRICS_RETRY_TRACE", "TTL bloqueando reintento: trackKey=$trackKey, msDesdeUltimoIntento=${now - cached.timestampFetched}")
                    return@withContext null
                }
            } else {
                lyricsDao.updateLastAccessed(trackKey, now)
                return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
            }
        }

        // 2. Si no hay o TTL expiró, ir a red
        val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
        val now = System.currentTimeMillis()
        
        if (networkResult != null) {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = networkResult,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = false
                )
            )
            return@withContext parseLrc(trackKey, networkResult, durationMs)
        } else {
            lyricsDao.insertLyrics(
                LyricsEntity(
                    trackKey = trackKey,
                    syncedLyrics = null,
                    plainLyrics = null,
                    timestampFetched = now,
                    lastAccessed = now,
                    notFound = true
                )
            )
            return@withContext null
        }
    }
```

## 2. `private fun normalizeForSearch(text: String): String`
```kotlin
    private fun normalizeForSearch(text: String): String {
        return text.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
    }
```

## 3. `private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String?`
```kotlin
    private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val cleanArtist = URLEncoder.encode(normalizeForSearch(artist), "UTF-8")
            val cleanTitle = URLEncoder.encode(normalizeForSearch(title), "UTF-8")
            val durationParam = if (durationSec > 0) "&duration=$durationSec" else ""
            val urlString = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle$durationParam"
            
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "MusicWidgetAndroidApp (https://github.com/arenliel/musicwidget)")
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                return@withContext json.optString("syncedLyrics").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e("LyricsRepo", "Error fetching lyrics", e)
        } finally {
            connection?.disconnect()
        }
        null
    }
```

## 4. `private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult`
```kotlin
    private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        return parseLrc(trackKey, lrc, durationMs)
    }
```

## 5. `fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult`
```kotlin
    fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        val allEntries = mutableListOf<LyricsEntry>()
        val lines = lrc.split("\n")
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        
        for (line in lines) {
            val match = regex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msPart = match.groupValues[3]
                val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
                val totalMs = (min * 60 + sec) * 1000 + ms
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) allEntries.add(LyricsEntry(totalMs, text))
            }
        }

        return LyricsResult(trackKey, allEntries)
    }
```
