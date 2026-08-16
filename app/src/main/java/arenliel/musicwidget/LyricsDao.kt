package arenliel.musicwidget

import androidx.room.*

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
