# AUDITORÍA — MOTOR DE OBTENCIÓN Y CACHÉ DE LETRAS (RONDA 1: MAPEO)

## 1. Resultados de Grep por Términos

### A. "Lyrics" (case-insensitive)
*(Nota: Se reportan las principales coincidencias clave en archivos de código fuente .kt)*
- `LyricsDao.kt:6`: `interface LyricsDao {`
- `LyricsDatabase.kt:9`: `abstract class LyricsDatabase : RoomDatabase() {`
- `LyricsEntity.kt:6`: `@Entity(tableName = "lyrics_cache")`
- `LyricsRepository.kt:14`: `data class LyricsEntry(val timestampMs: Long, val text: String)`
- `MusicDataStore.kt:82`: `val lyricsTrackKey: String = ""`
- `MusicNotificationListener.kt:63`: `private lateinit var lyricsRepository: LyricsRepository`

### B. "lyricsRepository" (case-insensitive)
- `MusicNotificationListener.kt:63`: `private lateinit var lyricsRepository: LyricsRepository`
- `MusicNotificationListener.kt:615`: `lyricsRepository = LyricsRepository(applicationContext)`
- `MusicNotificationListener.kt:2587`: `val result = lyricsRepository.getLyrics(...)`
- `MusicNotificationListener.kt:2847`: `lyricsRepository.getLyrics(...)`

### C. "LyricsResult" (case-insensitive)
- `LyricsRepository.kt:15`: `data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)`
- `LyricsRepository.kt:20`: `suspend fun getLyrics(...): LyricsResult?`
- `LyricsRepository.kt:99`: `private fun parseStoredLyrics(...): LyricsResult`
- `LyricsRepository.kt:103`: `fun parseLrc(...): LyricsResult`
- `MusicNotificationListener.kt:231`: `private var currentLyrics: LyricsResult? = null`
- `MusicNotificationListener.kt:2862`: `private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult)`
- `MusicNotificationListener.kt:2934`: `private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult)`

### D. "allEntries" (case-insensitive)
- `LyricsRepository.kt:104`: `val allEntries = mutableListOf<LyricsEntry>()`
- `LyricsRepository.kt:117`: `if (text.isNotBlank()) allEntries.add(LyricsEntry(totalMs, text))`
- `LyricsRepository.kt:121`: `return LyricsResult(trackKey, allEntries)`
- `MusicNotificationListener.kt:2881`: `val entry = lyricsRes.allEntries.lastOrNull { ... }`
- `MusicNotificationListener.kt:2887`: `val entryIdx = lyricsRes.allEntries.indexOf(entry)`
- `MusicNotificationListener.kt:2888`: `val next = if (entryIdx != -1 && entryIdx < lyricsRes.allEntries.size - 1) lyricsRes.allEntries[entryIdx + 1] else null`
- `MusicNotificationListener.kt:2919`: `} else if (lyricsRes.allEntries.isNotEmpty()) {`
- `MusicNotificationListener.kt:2925`: `val firstEntry = lyricsRes.allEntries.first()`
- `MusicNotificationListener.kt:2943`: `var lastEntry = lyricsRes.allEntries.lastOrNull { ... }`
- `MusicNotificationListener.kt:2945`: `lastEntry = lyricsRes.allEntries.firstOrNull()`

---

## 2. Firmas Declaradas en los Archivos Involucrados

### A. `LyricsDao.kt`
- `interface LyricsDao`
- `suspend fun getLyrics(trackKey: String): LyricsEntity?`
- `suspend fun insertLyrics(lyrics: LyricsEntity)`
- `suspend fun updateLastAccessed(trackKey: String, timestamp: Long)`
- `suspend fun purgeOldLyrics(threshold: Long)`
- `suspend fun deleteLyrics(trackKey: String)`

### B. `LyricsDatabase.kt`
- `abstract class LyricsDatabase : RoomDatabase()`
- `abstract fun lyricsDao(): LyricsDao`
- `fun getDatabase(context: Context): LyricsDatabase`

### C. `LyricsEntity.kt`
- `data class LyricsEntity(val trackKey: String, val syncedLyrics: String?, val plainLyrics: String?, val timestampFetched: Long, val lastAccessed: Long, val notFound: Boolean = false)`

### D. `LyricsRepository.kt`
- `data class LyricsEntry(val timestampMs: Long, val text: String)`
- `data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)`
- `class LyricsRepository(private val context: Context)`
- `suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult?`
- `private fun normalizeForSearch(text: String): String`
- `private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): String?`
- `private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult`
- `fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult`

---

## 3. Resultados de Grep por `@Entity` y `@Dao`

- `LyricsDao.kt:5`: `@Dao`
- `LyricsEntity.kt:6`: `@Entity(tableName = "lyrics_cache")`

---

## 4. Definición Completa de `@Entity` en Archivos de Letras

### `LyricsEntity.kt`
```kotlin
@Entity(tableName = "lyrics_cache")
data class LyricsEntity(
    @PrimaryKey val trackKey: String,
    val syncedLyrics: String?, // Texto LRC crudo
    val plainLyrics: String?,
    val timestampFetched: Long,
    val lastAccessed: Long,
    val notFound: Boolean = false // TTL 24h para re-intentos
)
```

---

## 5. Definición Completa de `@Dao` en Archivos de Letras

### `LyricsDao.kt`
```kotlin
@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackKey = :trackKey")
    suspend fun getLyrics(trackKey: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("UPDATE lyrics_cache SET lastAccessed = :timestamp WHERE trackKey = :trackKey")
    suspend fun updateLastAccessed(trackKey: String, timestamp: Long)

    @Query("DELETE FROM lyrics_cache WHERE lastAccessed < :threshold")
    suspend fun purgeOldLyrics(threshold: Long)

    @Query("DELETE FROM lyrics_cache WHERE trackKey = :trackKey")
    suspend fun deleteLyrics(trackKey: String)
}
```

---

## 6. Llamadas de Red Relacionadas con Letras

- `LyricsRepository.kt:78`: `val urlString = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle$durationParam"`
- `LyricsRepository.kt:84`: `connection.setRequestProperty("User-Agent", "MusicWidgetAndroidApp (https://github.com/arenliel/musicwidget)")`
- `LyricsRepository.kt:66`: `val connection = URL(urlString).openConnection() as HttpURLConnection` (Uso nativo de `HttpURLConnection` sin Retrofit ni OkHttp).
