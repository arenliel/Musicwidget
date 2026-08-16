package arenliel.musicwidget

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_cache")
data class LyricsEntity(
    @PrimaryKey val trackKey: String,
    val syncedLyrics: String?, // Texto LRC crudo
    val plainLyrics: String?,
    val timestampFetched: Long,
    val lastAccessed: Long,
    val notFound: Boolean = false // TTL 24h para re-intentos
)
