package arenliel.musicwidget

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LyricsEntity::class], version = 1, exportSchema = false)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao

    companion object {
        @Volatile
        private var INSTANCE: LyricsDatabase? = null

        fun getDatabase(context: Context): LyricsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LyricsDatabase::class.java,
                    "lyrics_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
