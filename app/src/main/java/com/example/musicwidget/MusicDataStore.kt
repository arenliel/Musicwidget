package arenliel.musicwidget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "music_prefs"
)

data class MusicInfo(
    val title: String,
    val artist: String,
    val packageName: String,

    /*
     * Identidad estable de la pista.
     *
     * Permite distinguir entre dos canciones que tengan
     * el mismo título y artista.
     */
    val trackKey: String = "",

    /*
     * Identidad de la portada actualmente asociada
     * a la pista.
     *
     * Es especialmente útil para Spotify, donde la URI
     * puede apuntar a un recurso remoto.
     */
    val artworkKey: String = "",

    /*
     * URI original de la portada (si está disponible).
     */
    val artworkUri: String = "",

    /*
     * Se mantiene por compatibilidad con el modelo anterior.
     *
     * Representa el momento en que se aplicó la última
     * actualización real.
     */
    val lastUpdate: Long = 0L,

    /*
     * Identidad del icono de la aplicación (procedente de la notificación).
     */
    val appIconKey: String = "",

    /*
     * Línea de letra actual a mostrar (Showcase).
     */
    val currentLyric: String = "",

    /*
     * Identidad de la pista a la que pertenecen las letras actuales.
     */
    val lyricsTrackKey: String = "",

    /*
     * Control maestro para habilitar/deshabilitar la función de letras.
     */
    val showLyrics: Boolean = true,

    /*
     * Lista de aplicaciones ignoradas por el usuario.
     */
    val blacklist: Set<String> = emptySet(),

    /*
     * Estado de reproducción actual.
     */
    val isPlaying: Boolean = false,

    /*
     * Indica si hay una sesión multimedia activa en el sistema.
     * Sirve para distinguir entre "Pausado" y "Reciente/Cerrado".
     */
    val isSessionActive: Boolean = true
)

class MusicDataStore(
    private val context: Context
) {

    companion object {

        private val TITLE =
            stringPreferencesKey(
                "title"
            )

        private val ARTIST =
            stringPreferencesKey(
                "artist"
            )

        private val PACKAGE_NAME =
            stringPreferencesKey(
                "package_name"
            )

        private val TRACK_KEY =
            stringPreferencesKey(
                "track_key"
            )

        private val ARTWORK_KEY =
            stringPreferencesKey(
                "artwork_key"
            )

        private val ARTWORK_URI =
            stringPreferencesKey(
                "artwork_uri"
            )

        private val APP_ICON_KEY =
            stringPreferencesKey(
                "app_icon_key"
            )

        private val LAST_UPDATE =
            longPreferencesKey(
                "last_update"
            )

        private val BLACKLIST =
            stringSetPreferencesKey(
                "blacklist"
            )

        private val IS_PLAYING =
            booleanPreferencesKey(
                "is_playing"
            )

        private val IS_SESSION_ACTIVE =
            booleanPreferencesKey(
                "is_session_active"
            )

        private val CURRENT_LYRIC =
            stringPreferencesKey(
                "current_lyric"
            )

        private val LYRICS_TRACK_KEY =
            stringPreferencesKey(
                "lyrics_track_key"
            )

        private val SHOW_LYRICS =
            booleanPreferencesKey(
                "show_lyrics"
            )

        private const val DEFAULT_TITLE =
            "No track"

        private const val DEFAULT_ARTIST =
            "Unknown artist"
    }

    /**
     * Información musical persistida.
     *
     * DataStore emite automáticamente cuando el contenido
     * persistido cambia.
     */
    val musicInfoFlow: Flow<MusicInfo> =
        context.dataStore.data.map { prefs ->

            MusicInfo(

                title =
                    prefs[TITLE]
                        ?: DEFAULT_TITLE,

                artist =
                    prefs[ARTIST]
                        ?: DEFAULT_ARTIST,

                packageName =
                    prefs[PACKAGE_NAME]
                        .orEmpty(),

                trackKey =
                    prefs[TRACK_KEY]
                        .orEmpty(),

                artworkKey =
                    prefs[ARTWORK_KEY]
                        .orEmpty(),

                artworkUri =
                    prefs[ARTWORK_URI]
                        .orEmpty(),

                appIconKey =
                    prefs[APP_ICON_KEY]
                        .orEmpty(),

                lastUpdate =
                    prefs[LAST_UPDATE]
                        ?: 0L,

                blacklist =
                    prefs[BLACKLIST]
                        ?: emptySet(),

                isPlaying =
                    prefs[IS_PLAYING]
                        ?: false,

                isSessionActive =
                    prefs[IS_SESSION_ACTIVE]
                        ?: false,

                currentLyric =
                    prefs[CURRENT_LYRIC]
                        .orEmpty(),

                lyricsTrackKey =
                    prefs[LYRICS_TRACK_KEY]
                        .orEmpty(),

                showLyrics =
                    prefs[SHOW_LYRICS]
                        ?: true
            )
        }

    /**
     * Guarda la información de la canción actual.
     *
     * @param info Información a guardar.
     * @param forceUpdate Si es true, fuerza la actualización del timestamp incluso
     *                    si los metadatos son idénticos. Útil para notificar cambios
     *                    en archivos externos (artwork).
     */
    suspend fun saveMusicInfo(
        info: MusicInfo,
        forceUpdate: Boolean = false
    ) {

        context.dataStore.edit { prefs ->

            val currentTitle =
                prefs[TITLE]
                    ?: DEFAULT_TITLE

            val currentArtist =
                prefs[ARTIST]
                    ?: DEFAULT_ARTIST

            val currentPackageName =
                prefs[PACKAGE_NAME]
                    .orEmpty()

            val currentTrackKey =
                prefs[TRACK_KEY]
                    .orEmpty()

            val currentArtworkKey =
                prefs[ARTWORK_KEY]
                    .orEmpty()

            val currentArtworkUri =
                prefs[ARTWORK_URI]
                    .orEmpty()

            val currentAppIconKey =
                prefs[APP_ICON_KEY]
                    .orEmpty()

            val currentIsPlaying =
                prefs[IS_PLAYING]
                    ?: false

            val currentIsSessionActive =
                prefs[IS_SESSION_ACTIVE]
                    ?: false

            val currentLyric =
                prefs[CURRENT_LYRIC]
                    .orEmpty()

            val currentLyricsTrackKey =
                prefs[LYRICS_TRACK_KEY]
                    .orEmpty()

            val currentShowLyrics =
                prefs[SHOW_LYRICS]
                    ?: true

            val hasMusicChanged =
                currentTitle != info.title ||
                        currentArtist != info.artist ||
                        currentPackageName !=
                        info.packageName ||
                        currentTrackKey !=
                        info.trackKey ||
                        currentArtworkKey !=
                        info.artworkKey ||
                        currentArtworkUri !=
                        info.artworkUri ||
                        currentAppIconKey !=
                        info.appIconKey ||
                        currentIsPlaying !=
                        info.isPlaying ||
                        currentIsSessionActive !=
                        info.isSessionActive ||
                        currentLyric !=
                        info.currentLyric ||
                        currentLyricsTrackKey !=
                        info.lyricsTrackKey ||
                        currentShowLyrics !=
                        info.showLyrics

            /*
             * Si nada ha cambiado y no se requiere actualización forzada,
             * salimos para evitar ruido en el Flow.
             */
            if (!hasMusicChanged && !forceUpdate) {
                return@edit
            }

            /*
             * Solo actualizamos los valores que corresponden
             * a la información musical.
             */
            prefs[TITLE] =
                info.title

            prefs[ARTIST] =
                info.artist

            prefs[PACKAGE_NAME] =
                info.packageName

            prefs[TRACK_KEY] =
                info.trackKey

            prefs[ARTWORK_KEY] =
                info.artworkKey

            prefs[ARTWORK_URI] =
                info.artworkUri

            prefs[APP_ICON_KEY] =
                info.appIconKey

            prefs[IS_PLAYING] =
                info.isPlaying

            prefs[IS_SESSION_ACTIVE] =
                info.isSessionActive

            prefs[CURRENT_LYRIC] =
                info.currentLyric

            prefs[LYRICS_TRACK_KEY] =
                info.lyricsTrackKey

            prefs[SHOW_LYRICS] =
                info.showLyrics

            /*
             * lastUpdate representa una actualización real.
             *
             * Nunca se modifica si no hubo cambios.
             */
            prefs[LAST_UPDATE] =
                System.currentTimeMillis()
        }
    }

    /**
     * Actualiza la blacklist de aplicaciones.
     *
     * La operación es idempotente:
     *
     * - Añadir una aplicación ya existente no cambia nada.
     * - Eliminar una aplicación inexistente no cambia nada.
     */
    suspend fun updateBlacklist(
        packageName: String,
        add: Boolean
    ) {

        if (packageName.isBlank()) {
            return
        }

        context.dataStore.edit { prefs ->

            val current =
                prefs[BLACKLIST]
                    ?.toMutableSet()
                    ?: mutableSetOf()

            val changed =
                if (add) {

                    current.add(
                        packageName
                    )

                } else {

                    current.remove(
                        packageName
                    )
                }

            /*
             * Si la operación no cambió el conjunto,
             * no escribimos de nuevo en DataStore.
             */
            if (!changed) {
                return@edit
            }

            prefs[BLACKLIST] =
                current
        }
    }
}
