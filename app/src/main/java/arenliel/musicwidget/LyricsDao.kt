package arenliel.musicwidget

import androidx.room.*

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackKey = :trackKey")
    suspend fun getLyrics(trackKey: String): LyricsEntity?

    // Conjunto Letras-Atomicas-13: enumera las entradas "encontradas" existentes
    // para que el saneamiento retroactivo pueda evaluarlas contra el filtro de
    // plausibilidad de contenido.
    @Query("SELECT * FROM lyrics_cache WHERE notFound = 0")
    suspend fun getAllFoundLyrics(): List<LyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics_cache WHERE trackKey = :trackKey")
    suspend fun deleteLyrics(trackKey: String)
}
