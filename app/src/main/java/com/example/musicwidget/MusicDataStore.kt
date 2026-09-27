package arenliel.musicwidget

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(
    name = "music_prefs"
)

data class MusicInfo(
    val title: String,
    val artist: String,
    val packageName: String,
    val album: String = "", // AGREGADO (Bloque B)

    /*
     * Identidad estable de la pista.
     *
     * Permite distinguir entre dos canciones que tengan
     * el mismo título y artista.
     */
    val trackKey: String = "",

    /*
     * Identificador único de la sesión (v5.2).
     * Inmutable durante todo el ciclo de vida de la pista.
     */
    val sessionUUID: String = "",

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
     * Representa el momento (Epoch) en que se aplicó la última
     * actualización real. Usado para UI relativa.
     */
    val lastUpdateEpoch: Long = 0L,

    /**
     * Marca de tiempo monotónica del hardware.
     * Usada exclusivamente para extrapolación de progreso.
     */
    val observedAtRealtime: Long = 0L,

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
     * Indica si el permiso de NotificationListenerService está concedido.
     */
    val notificationsEnabled: Boolean = true,

    /*
     * Indica si la aplicación está en la lista blanca de optimización de batería.
     */
    val batteryOptimized: Boolean = true,

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
    val isSessionActive: Boolean = true,

    /*
     * Indica si el reproductor está cargando contenido (buffering).
     */
    val isBuffering: Boolean = false,

    /*
     * Nombre del dispositivo de salida actual (ej. "Sony WH-1000XM4", "Altavoz del teléfono").
     */
    val playbackDeviceName: String = "",

    /*
     * Tipo de dispositivo de salida actual (ej. AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).
     */
    val playbackDeviceType: Int = 0,

    /*
     * Duración total de la pista en milisegundos.
     */
    val durationMs: Long = 0L,

    /*
     * Historial de reproducción reciente.
     */
    val history: List<HistoryItem> = emptyList(),

    /*
     * Analítica de repetición para la canción actual.
     */
    val playsToday: Int = 0,
    val streakDays: Int = 0,
    val skipStreak: Int = 0,

    /*
     * Indica si el artista actual es considerado "Frecuente" (Corazón ❤️).
     */
    val isFrequentArtist: Boolean = false,

    /*
     * Indica si esta sesión fue interrumpida bruscamente (onSessionDestroyed)
     * y está esperando una reconciliación o un cierre definitivo (v6.7).
     */
    val isPendingCommit: Boolean = false,

    /*
     * Último progreso máximo alcanzado antes de la interrupción.
     */
    val lastMaxPositionMs: Long = 0L,

    /*
     * Versión del esquema de identidad (v7.0).
     * Permite migrar registros antiguos a la nueva sanitización.
     */
    val identitySchemaVersion: Int = 0,

    /*
     * Conjunto Identidad-Atómica-Presentación-1: indica si esta identidad está
     * 100% lista para mostrarse (metadatos + portada + las 4 métricas confirmados).
     * Default `true` a propósito: solo la vía rápida de MusicStateProvider (canción
     * recién cambiada, aún sin resolver) lo pone en `false` explícitamente — nada
     * más en el proyecto necesita preocuparse por este campo.
     */
    val isPresentationReady: Boolean = true
) {
    /**
     * DETERMINISMO DE ESTADO: Indica si el widget está en una instalación fresca (v1.7.0).
     */
    val isEmpty: Boolean get() = trackKey.isBlank()

    /**
     * Identidad de sesión (v9.0): Centralizada.
     */
    val sessionIdentity: String
        get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)

    /**
     * MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en el estado visual para el widget.
     * Centraliza la lógica de "Estado Vacío" y filtrado de lista negra.
     */
    fun toDisplayedState(context: Context): MusicInfo {
        return if (title.isEmpty() || blacklist.contains(packageName)) {
            this.copy(
                title = context.getString(R.string.widget_empty_title),
                artist = context.getString(R.string.widget_empty_subtitle),
                packageName = "",
                trackKey = "",
                artworkKey = "",
                artworkUri = "",
                appIconKey = "",
                isPlaying = false,
                isSessionActive = false,
                currentLyric = "",
                lyricsTrackKey = ""
            )
        } else {
            this
        }
    }
}

data class HistoryItem(
    val title: String,
    val artist: String,
    val album: String = "", // AGREGADO (Bloque B)
    val durationMs: Long = 0L, // AGREGADO (Bloque B)
    val packageName: String,
    val artworkPath: String,
    val artworkKey: String,
    val trackKey: String,
    val timestamp: Long,
    val isSkipped: Boolean = false,
    val skipStreak: Int = 0,
    val playsToday: Int = 0,
    val streakDays: Int = 0,
    val artworkUri: String = "",
    val hasPendingArtwork: Boolean = false,
    val identitySchemaVersion: Int = 0 // AGREGADO (Bloque B)
) {
    /**
     * Identidad de sesión (v9.0): Centralizada y normalizada.
     * Fuente de verdad para filtros de redundancia y LRU.
     */
    val sessionIdentity: String
        get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)

    /**
     * Identidad lógica de la pista para reconstrucción.
     */
    val canonicalTrackKey: String
        get() = "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
}

/**
 * Estadísticas de repetición persistidas por canción.
 */
data class RepeatStats(
    val playsToday: Int = 0,
    val lastPlayedEpochDay: Long = 0L,
    val streakDays: Int = 0
)

/**
 * Estadísticas de fidelidad por artista.
 */
data class ArtistStats(
    val distinctDaysHeard: Int = 0,
    val lastPlayedEpochDay: Long = 0L
)

/**
 * Registro de una canción "Bendecida": ya se escuchó completa o parcialmente
 * (nunca saltada) al menos una vez, dentro de la ventana de vigencia.
 */
data class BlessedSong(
    val lastCompletedEpochDay: Long = 0L
)

/**
 * Identidad visual de la racha/repetición.
 */
enum class RepeatBadge {
    NONE,
    HOT_TODAY,      // Umbral: 3 veces hoy
    ONGOING_STREAK  // Umbral: 3 días seguidos
}

/**
 * Motor Maestro de Umbrales: Determina si una canción merece un badge.
 */
fun badgeFor(stats: RepeatStats?, todayEpochDay: Long): RepeatBadge {
    if (stats == null) return RepeatBadge.NONE
    
    // Si el gap es mayor a 1 día, la racha se rompió lógicamente
    val gap = todayEpochDay - stats.lastPlayedEpochDay
    if (gap > 1) return RepeatBadge.NONE

    return when {
        stats.streakDays >= 3 -> RepeatBadge.ONGOING_STREAK
        gap == 0L && stats.playsToday >= 3 -> RepeatBadge.HOT_TODAY
        else -> RepeatBadge.NONE
    }
}

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

        private val SESSION_UUID =
            stringPreferencesKey(
                "session_uuid"
            )

        private val IS_PENDING_COMMIT =
            booleanPreferencesKey(
                "is_pending_commit"
            )

        private val LAST_MAX_POSITION_MS =
            longPreferencesKey(
                "last_max_pos"
            )

        private val IDENTITY_SCHEMA_VERSION =
            intPreferencesKey(
                "identity_v"
            )

        private val ALBUM = stringPreferencesKey("album")
        const val CURRENT_IDENTITY_VERSION = 2

        private val LAST_UPDATE_EPOCH =
            longPreferencesKey(
                "last_update_epoch"
            )

        private val OBSERVED_AT_REALTIME =
            longPreferencesKey(
                "observed_at_realtime"
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

        private val PLAYBACK_DEVICE_NAME =
            stringPreferencesKey(
                "playback_device_name"
            )

        private val PLAYBACK_DEVICE_TYPE =
            androidx.datastore.preferences.core.intPreferencesKey(
                "playback_device_type"
            )

        private val DURATION_MS =
            longPreferencesKey(
                "duration_ms"
            )

        private val HISTORY =
            stringPreferencesKey(
                "history"
            )

        private val SKIP_STREAKS =
            stringPreferencesKey(
                "skip_streaks"
            )

        private val REPEAT_STATS =
            stringPreferencesKey(
                "repeat_stats"
            )

        private val ARTIST_STATS =
            stringPreferencesKey(
                "artist_stats"
            )

        private val BLESSED_SONGS =
            stringPreferencesKey(
                "blessed_songs"
            )

        /**
         * REGLA F.1 (Orden v9): Control de reseteo masivo de rachas.
         * Activarlo únicamente bajo confirmación del usuario para sanear conteos del Incidente K.
         */
        const val GLOBAL_SKIP_STREAK_RESET_ENABLED = false

        /**
         * Función canónica de normalización Unicode NFC (v9.0).
         */
        fun normalize(text: String?): String {
            if (text == null) return ""
            return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).trim().lowercase()
        }

        /**
         * Generador de identidad de sesión único para todo el proyecto (v9.0).
         */
        fun computeSessionIdentity(packageName: String, title: String, artist: String): String {
            return "$packageName|${normalize(title)}|${normalize(artist)}"
        }

        private const val DEFAULT_TITLE =
            ""

        private const val DEFAULT_ARTIST =
            ""
    }

    /**
     * Información musical persistida.
     *
     * DataStore emite automáticamente cuando el contenido
     * persistido cambia.
     */
    val musicInfoFlow: Flow<MusicInfo> =
        context.dataStore.data.map { prefs ->
            // Conjunto Corrección-Carrera-Stats-1 (Ronda 2) + Identidad-Atómica-Presentación-1:
            // los 4 campos de estadística comparten una sola función con getStatsFor — antes
            // streakDays tenía aquí su propia copia de la consulta a REPEAT_STATS, separada de
            // playsToday/skipStreak/isFrequentArtist, sin ninguna razón para estar aparte.
            val stats =
                computeStatsFrom(prefs, prefs[TITLE] ?: DEFAULT_TITLE, prefs[ARTIST] ?: DEFAULT_ARTIST)

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

                sessionUUID =
                    prefs[SESSION_UUID]
                        .orEmpty(),

                lastUpdateEpoch =
                    prefs[LAST_UPDATE_EPOCH]
                        ?: 0L,

                observedAtRealtime =
                    prefs[OBSERVED_AT_REALTIME]
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
                        ?: true,

                playbackDeviceName =
                    prefs[PLAYBACK_DEVICE_NAME]
                        .orEmpty(),

                playbackDeviceType =
                    prefs[PLAYBACK_DEVICE_TYPE]
                        ?: 0,

                durationMs = prefs[DURATION_MS] ?: 0L,
                album = prefs[ALBUM].orEmpty(),
                isPendingCommit = prefs[IS_PENDING_COMMIT] ?: false,
                lastMaxPositionMs = prefs[LAST_MAX_POSITION_MS] ?: 0L,
                identitySchemaVersion = prefs[IDENTITY_SCHEMA_VERSION] ?: 0,
                history = run {
                    val rawHistory = decodeHistory(prefs[HISTORY].orEmpty())
                    if (GLOBAL_SKIP_STREAK_RESET_ENABLED) {
                        rawHistory.map { it.copy(skipStreak = 0) }
                    } else rawHistory
                },

                playsToday = stats.playsToday,

                streakDays = stats.streakDays,

                skipStreak = stats.skipStreak,

                isFrequentArtist = stats.isFrequentArtist
            )
        }

    private fun decodeArtistStats(json: String): Map<String, ArtistStats> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, ArtistStats>()
            obj.keys().forEach { key ->
                val inner = obj.getJSONObject(key)
                map[key] = ArtistStats(
                    distinctDaysHeard = inner.getInt("dd"),
                    lastPlayedEpochDay = inner.getLong("lp")
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun decodeBlessedSongs(json: String): Map<String, BlessedSong> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, BlessedSong>()
            obj.keys().forEach { key ->
                val inner = obj.getJSONObject(key)
                map[key] = BlessedSong(lastCompletedEpochDay = inner.getLong("lp"))
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun decodeRepeatStats(json: String): Map<String, RepeatStats> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, RepeatStats>()
            obj.keys().forEach { key ->
                val inner = obj.getJSONObject(key)
                map[key] = RepeatStats(
                    playsToday = inner.getInt("pt"),
                    lastPlayedEpochDay = inner.getLong("lp"),
                    streakDays = inner.getInt("sd")
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun decodeHistory(json: String): List<HistoryItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val item = HistoryItem(
                    title = obj.getString("t"),
                    artist = obj.getString("a"),
                    packageName = obj.optString("p", ""),
                    artworkPath = obj.optString("ap", ""),
                    artworkKey = obj.optString("ak", obj.optString("k", "")),
                    trackKey = obj.optString("tk", ""),
                    album = obj.optString("al", ""),
                    durationMs = obj.optLong("dm", 0L),
                    timestamp = obj.getLong("ts"),
                    identitySchemaVersion = obj.optInt("v", 0),
                    isSkipped = obj.optBoolean("sk", false),
                    skipStreak = obj.optInt("ss", 0),
                    playsToday = obj.optInt("pt", 0),
                    streakDays = obj.optInt("sd", 0),
                    artworkUri = obj.optString("au", ""),
                    hasPendingArtwork = obj.optBoolean("pa", false)
                )

                // MIGRACIÓN v8.0 (Bloque B.6): Normalización retroactiva
                if (item.identitySchemaVersion < CURRENT_IDENTITY_VERSION) {
                    item.copy(
                        trackKey = item.canonicalTrackKey,
                        identitySchemaVersion = CURRENT_IDENTITY_VERSION
                    )
                } else {
                    item
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeHistory(history: List<HistoryItem>): String {
        val array = JSONArray()
        history.forEach { item ->
            val obj = JSONObject()
            obj.put("t", item.title)
            obj.put("a", item.artist)
            obj.put("p", item.packageName)
            obj.put("ap", item.artworkPath)
            obj.put("ak", item.artworkKey)
            obj.put("tk", item.trackKey)
            obj.put("al", item.album)
            obj.put("dm", item.durationMs)
            obj.put("ts", item.timestamp)
            obj.put("v", item.identitySchemaVersion)
            obj.put("sk", item.isSkipped)
            obj.put("ss", item.skipStreak)
            obj.put("pt", item.playsToday)
            obj.put("sd", item.streakDays)
            obj.put("au", item.artworkUri)
            obj.put("pa", item.hasPendingArtwork)
            array.put(obj)
        }
        return array.toString()
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
    ): Boolean {
        var changed = false
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

            val currentPlaybackDeviceName =
                prefs[PLAYBACK_DEVICE_NAME]
                    .orEmpty()

            val currentPlaybackDeviceType =
                prefs[PLAYBACK_DEVICE_TYPE]
                    ?: 0

            val currentDurationMs =
                prefs[DURATION_MS]
                    ?: 0L

            // 1. CAMBIO DE IDENTIDAD (Requiere reset de reloj)
            val identityChanged = currentTitle != info.title ||
                    currentArtist != info.artist ||
                    currentPackageName != info.packageName ||
                    currentTrackKey != info.trackKey ||
                    currentArtworkKey != info.artworkKey ||
                    currentArtworkUri != info.artworkUri ||
                    currentDurationMs != info.durationMs

            // 2. CAMBIO DE ESTADO DE REPRODUCCIÓN (Requiere reset de reloj)
            // Solo reseteamos si:
            // - El estado de Play/Pause cambió realmente.
            // - La sesión se cerró (isSessionActive: true -> false).
            // - El dispositivo de salida cambió.
            // NOTA: Si la sesión se reabre (false -> true) pero sigue en PAUSA, no reseteamos el reloj
            // para mantener el "Hace X horas" verídico.
            val playbackStatusChanged = currentIsPlaying != info.isPlaying ||
                    (currentIsSessionActive && !info.isSessionActive) ||
                    currentPlaybackDeviceName != info.playbackDeviceName ||
                    currentPlaybackDeviceType != info.playbackDeviceType

            // 3. CAMBIO DE METADATOS SECUNDARIOS (NO requiere reset de reloj)
            val metadataOnlyChanged = currentAppIconKey != info.appIconKey ||
                    currentLyric != info.currentLyric ||
                    currentLyricsTrackKey != info.lyricsTrackKey ||
                    currentShowLyrics != info.showLyrics

            val hasAnyChange = identityChanged || playbackStatusChanged || metadataOnlyChanged

            /*
             * Si nada ha cambiado y no se requiere actualización forzada,
             * salimos para evitar ruido en el Flow.
             */
            if (!hasAnyChange && !forceUpdate) {
                return@edit
            }

            changed = true
            /*
             * Actualizamos todos los valores.
             */
            prefs[TITLE] = info.title
            prefs[ARTIST] = info.artist
            prefs[PACKAGE_NAME] = info.packageName
            prefs[TRACK_KEY] = info.trackKey
            prefs[SESSION_UUID] = info.sessionUUID
            prefs[ARTWORK_KEY] = info.artworkKey
            prefs[ARTWORK_URI] = info.artworkUri
            prefs[APP_ICON_KEY] = info.appIconKey
            prefs[IS_PLAYING] = info.isPlaying
            prefs[IS_SESSION_ACTIVE] = info.isSessionActive
            prefs[IS_PENDING_COMMIT] = info.isPendingCommit
            prefs[LAST_MAX_POSITION_MS] = info.lastMaxPositionMs
            prefs[IDENTITY_SCHEMA_VERSION] = CURRENT_IDENTITY_VERSION
            prefs[ALBUM] = info.album
            prefs[CURRENT_LYRIC] = info.currentLyric
            prefs[LYRICS_TRACK_KEY] = info.lyricsTrackKey
            prefs[SHOW_LYRICS] = info.showLyrics
            prefs[PLAYBACK_DEVICE_NAME] = info.playbackDeviceName
            prefs[PLAYBACK_DEVICE_TYPE] = info.playbackDeviceType
            prefs[DURATION_MS] = info.durationMs

            /*
             * lastUpdateEpoch representa una actualización real de la sesión (Epoch).
             * observedAtRealtime representa el anclaje monotónico.
             *
             * REGLA v6.6: Reseteamos el reloj ante cambios de identidad O cambios de estado 
             * de reproducción (incluyendo el paso a PAUSA) para que el umbral de 15 min 
             * cuente desde el momento exacto de la inactividad.
             */
            val sessionIdentityChangedForClock = currentTitle != info.title ||
                    currentArtist != info.artist ||
                    currentPackageName != info.packageName

            val oldShouldResetClock = identityChanged || playbackStatusChanged
            // Conjunto Cierre-Diferido-No-Resetea-Reloj-1: un cierre DIFERIDO
            // (isPendingCommit=true, ver "REGLA v6.7" en onSessionDestroyed) fuerza
            // isPlaying=false E isSessionActive=false de forma incondicional, sin que el
            // usuario haya interactuado ni la sesión haya terminado de verdad — es un estado
            // explícitamente NO confirmado ("si no resucita tras Doze, la archivaremos
            // tarde"). Resetear aquí el reloj visible equivale a resolver ese estado
            // pendiente antes de tiempo. playbackStatusChanged en sí NO se toca (sigue
            // determinando hasAnyChange normalmente, así que el flag isPendingCommit y la
            // posición máxima se siguen persistiendo); solo se excluye de la decisión de
            // resetear el reloj mientras el cierre siga sin confirmar.
            val shouldResetClock = sessionIdentityChangedForClock || (playbackStatusChanged && !info.isPendingCommit)
            InternalLogger.d(context, "[IDENTITY_TRACE] Paso7_shouldResetClock: viejo=$oldShouldResetClock, nuevo=$shouldResetClock, coincide=${oldShouldResetClock == shouldResetClock}, sessionIdChanged=$sessionIdentityChangedForClock, playbackChanged=$playbackStatusChanged")

            if (shouldResetClock) {
                prefs[LAST_UPDATE_EPOCH] = System.currentTimeMillis()
                prefs[OBSERVED_AT_REALTIME] = android.os.SystemClock.elapsedRealtime()
            }
        }
        return changed
    }

    /**
     * Añade un item al historial de forma independiente.
     * Implementa estrategia LRU e Inmunidad de Estatus (Canción Bendecida).
     */
    suspend fun addToHistory(item: HistoryItem) {
        context.dataStore.edit { prefs ->
            val currentHistoryJson = prefs[HISTORY].orEmpty()
            val oldHistory = decodeHistory(currentHistoryJson)

            // 1. Buscar coincidencia previa usando sessionIdentity (Bloque C.2)
            val existingItem = oldHistory.find {
                it.sessionIdentity == item.sessionIdentity
            }

            if (existingItem != null) {
                InternalLogger.log(context, "LRU: Repetición detectada. Moviendo a la cima: ${item.title}")
            }

            // Conjunto Bendecida-1: se eliminó el segundo mecanismo de "Bendecida" que vivía
            // aquí (comparaba contra este mismo historial, limitado a solo 10 canciones).
            // commitToHistory ya consultó la fuente única de verdad
            // (musicDataStore.updateBlessedStatus) antes de construir `item` — item.isSkipped
            // ya refleja el perdón correcto, así que se usa `item` tal cual, sin reevaluarlo
            // ni volver a limpiar la racha de saltos aquí (updateSkipStreak ya la resetea sola
            // cuando isSkipped es false).

            // 2. Filtrar coincidencia previa para mover a la cima (LRU) - Usar sessionIdentity
            val listWithoutDuplicate = oldHistory.filterNot {
                it.sessionIdentity == item.sessionIdentity
            }

            // 3. Insertar al principio y limitar a los últimos 10
            val newHistory = (listOf(item) + listWithoutDuplicate).take(10)
            
            prefs[HISTORY] = encodeHistory(newHistory)
        }
    }

    /**
     * Actualiza quirúrgicamente el estado de una portada pendiente en el historial.
     */
    suspend fun updateHistoryItemArtworkStatus(trackKey: String, timestamp: Long, isPending: Boolean) {
        context.dataStore.edit { prefs ->
            val currentHistoryJson = prefs[HISTORY].orEmpty()
            val oldHistory = decodeHistory(currentHistoryJson)
            
            val newHistory = oldHistory.map { item ->
                if (item.trackKey == trackKey && item.timestamp == timestamp) {
                    val localUri = if (!isPending) Uri.fromFile(java.io.File(item.artworkPath)).toString() else item.artworkUri
                    Log.d("DATASTORE_MUTATION", "Actualizando URI de portada para trackKey: $trackKey -> Nueva URI: $localUri (isPending: $isPending)")
                    item.copy(hasPendingArtwork = isPending, artworkUri = localUri)
                } else {
                    item
                }
            }
            
            prefs[HISTORY] = encodeHistory(newHistory)
        }
    }

    private fun resetSkipStreakInternal(prefs: androidx.datastore.preferences.core.MutablePreferences, title: String, artist: String) {
        val json = prefs[SKIP_STREAKS].orEmpty()
        if (json.isBlank()) return
        runCatching {
            val obj = JSONObject(json)
            val identity = "$title|$artist"
            if (obj.has(identity)) {
                obj.remove(identity)
                prefs[SKIP_STREAKS] = obj.toString()
            }
        }
    }

    /**
     * Actualiza y persiste la racha de skips para una canción.
     * @return La racha actualizada.
     */
    suspend fun updateSkipStreak(title: String, artist: String, isSkip: Boolean): Int {
        var newStreak = 0
        context.dataStore.edit { prefs ->
            val json = prefs[SKIP_STREAKS].orEmpty()
            // Conjunto Bendecida-1: el formato cambia de "identity -> count" a
            // "identity -> {c: count, lp: lastSkippedEpochDay}". Antes no existía ninguna fecha
            // asociada a la racha de saltos, así que un salto de hace meses contaba igual que
            // uno de ayer. Ahora, si pasaron más de 14 días desde el último salto de esa
            // canción, la racha se trata como si empezara de cero.
            val map = mutableMapOf<String, Pair<Int, Long>>() // count to lastSkippedEpochDay
            if (json.isNotBlank()) {
                runCatching {
                    val obj = JSONObject(json)
                    obj.keys().forEach { key ->
                        val inner = obj.getJSONObject(key)
                        map[key] = inner.getInt("c") to inner.getLong("lp")
                    }
                }
            }

            val identity = "$title|$artist"
            val today = java.time.LocalDate.now().toEpochDay()
            val existing = map[identity]
            val gapDays = existing?.let { today - it.second }
            val currentStreak = if (existing != null && (gapDays == null || gapDays <= 14)) existing.first else 0
            InternalLogger.log(context, "[STREAK_TRACE] SkipStreak lectura: identity=$identity, existeAnterior=${existing != null}, gapDias=$gapDays, streakUsado=$currentStreak, expiroPorVencimiento=${existing != null && gapDays != null && gapDays > 14}")

            newStreak = when {
                isSkip -> currentStreak + 1
                else -> 0 // Completada o Parcial: Resetear perdón
            }

            if (newStreak > 0) {
                map[identity] = newStreak to today
            } else {
                map.remove(identity)
            }

            // Limpieza LRU básica: mantener solo los últimos 200 registros de racha
            if (map.size > 200) {
                val keysToRemove = map.keys.take(map.size - 200)
                keysToRemove.forEach { map.remove(it) }
            }

            val newObj = JSONObject()
            map.forEach { (k, v) ->
                val inner = JSONObject()
                inner.put("c", v.first)
                inner.put("lp", v.second)
                newObj.put(k, inner)
            }
            prefs[SKIP_STREAKS] = newObj.toString()
            InternalLogger.log(context, "[STREAK_TRACE] SkipStreak guardado: identity=$identity, newStreak=$newStreak, tamañoMapa=${map.size}")
        }
        return newStreak
    }

    /**
     * Actualiza y persiste las estadísticas de repetición para una canción.
     * @return Las estadísticas actualizadas.
     */
    /**
     * Actualiza y persiste las estadísticas de repetición para una canción.
     * @return Las estadísticas actualizadas.
     */
    suspend fun updateRepeatStats(title: String, artist: String, isSkip: Boolean): Pair<Int, Int> {
        var finalPlaysToday = 0
        var finalStreakDays = 0
        context.dataStore.edit { prefs ->
            val statsMap = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty()).toMutableMap()
            val identity = "$title|$artist"
            val existing = statsMap[identity]
            val today = java.time.LocalDate.now().toEpochDay()

            val updated = if (isSkip) {
                // El skip mata la racha inmediatamente
                RepeatStats(playsToday = 0, lastPlayedEpochDay = today, streakDays = 0)
            } else if (existing == null) {
                // Primera vez que suena
                RepeatStats(playsToday = 1, lastPlayedEpochDay = today, streakDays = 1)
            } else {
                when (today - existing.lastPlayedEpochDay) {
                    0L -> existing.copy(playsToday = existing.playsToday + 1) // Mismo día
                    1L -> existing.copy(playsToday = 1, lastPlayedEpochDay = today, streakDays = existing.streakDays + 1) // Día consecutivo
                    else -> RepeatStats(playsToday = 1, lastPlayedEpochDay = today, streakDays = 1) // Hueco temporal, reset
                }
            }

            finalPlaysToday = updated.playsToday
            finalStreakDays = updated.streakDays

            if (updated.playsToday > 0 || updated.streakDays > 0) {
                statsMap[identity] = updated
            } else {
                statsMap.remove(identity)
            }

            // Limpieza LRU: Mantener solo las últimas 200 canciones con racha activa
            if (statsMap.size > 200) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 200)
                keysToRemove.forEach { statsMap.remove(it) }
            }

            val newObj = JSONObject()
            statsMap.forEach { (k, v) ->
                val inner = JSONObject()
                inner.put("pt", v.playsToday)
                inner.put("lp", v.lastPlayedEpochDay)
                inner.put("sd", v.streakDays)
                newObj.put(k, inner)
            }
            prefs[REPEAT_STATS] = newObj.toString()
            InternalLogger.log(context, "[STREAK_TRACE] RepeatStats guardado: identity=$identity, playsToday=$finalPlaysToday, streakDays=$finalStreakDays, tamañoMapa=${statsMap.size}")
        }
        return finalPlaysToday to finalStreakDays
    }

    /**
     * Actualiza la analítica de fidelidad del artista.
     * Basado en Días Distintos escuchados.
     */
    /**
     * Fuente única de verdad para "Bendecida". Identifica la canción solo por
     * título+artista (igual que la racha de saltos y de repetición) — nunca por
     * trackKey, que incluye duración y puede variar levemente entre reproducciones
     * de la misma canción.
     *
     * Devuelve si la canción YA estaba bendecida ANTES de esta escucha (para decidir
     * si perdonar un salto de hoy). Luego actualiza la caja: si esta escucha NO fue un
     * salto (usa el veredicto crudo, sin perdón — igual que la racha de repetición),
     * renueva o crea la bendición. Un salto perdonado hoy no renueva la bendición por
     * sí mismo — eso fabricaría protección sin una escucha real de por medio.
     */
    suspend fun updateBlessedStatus(title: String, artist: String, isCompleted: Boolean): Boolean {
        var wasBlessed = false
        context.dataStore.edit { prefs ->
            val statsMap = decodeBlessedSongs(prefs[BLESSED_SONGS].orEmpty()).toMutableMap()
            val identity = "$title|$artist"
            val today = java.time.LocalDate.now().toEpochDay()

            val existing = statsMap[identity]
            val gapDays = existing?.let { today - it.lastCompletedEpochDay }
            wasBlessed = existing != null && (gapDays == null || gapDays <= 14)
            InternalLogger.log(context, "[STREAK_TRACE] Bendecida lectura: identity=$identity, existeAnterior=${existing != null}, gapDias=$gapDays, wasBlessed=$wasBlessed, expiroPorVencimiento=${existing != null && gapDays != null && gapDays > 14}")

            // Conjunto Bendecida-2: solo una escucha COMPLETA (>=85%) renueva la bendición —
            // antes, cualquier cosa que no fuera un salto (incluyendo parciales, 40%-85%)
            // también la renovaba, más generoso de lo que se pretendía originalmente.
            if (isCompleted) {
                statsMap[identity] = BlessedSong(lastCompletedEpochDay = today)
            }

            if (statsMap.size > 200) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 200)
                keysToRemove.forEach { statsMap.remove(it) }
            }

            val newObj = JSONObject()
            statsMap.forEach { (k, v) ->
                val inner = JSONObject()
                inner.put("lp", v.lastCompletedEpochDay)
                newObj.put(k, inner)
            }
            prefs[BLESSED_SONGS] = newObj.toString()
            InternalLogger.log(context, "[STREAK_TRACE] Bendecida guardado: identity=$identity, seRenovoEstaVez=$isCompleted, tamañoMapa=${statsMap.size}")
        }
        return wasBlessed
    }

    suspend fun updateArtistStats(artistName: String) {
        if (artistName.isBlank()) return
        context.dataStore.edit { prefs ->
            val statsMap = decodeArtistStats(prefs[ARTIST_STATS].orEmpty()).toMutableMap()
            val key = artistName.trim().lowercase()
            val today = java.time.LocalDate.now().toEpochDay()
            
            val existing = statsMap[key]
            // Conjunto Bendecida-1: `existing` nunca se reiniciaba por vencimiento — solo sumaba
            // días para siempre, sin importar cuánto tiempo hubiera pasado desde la última vez.
            // Esto hacía que ampliar la ventana de lectura (14→30 días, ver otros cambios) no
            // cambiara nada de fondo: el contador de "días distintos" nunca dependía realmente
            // del tiempo. Ahora, si pasaron más de 30 días, la racha empieza de nuevo en 1, en
            // vez de seguir sumando sobre una fidelidad ya vieja.
            val gapDays = existing?.let { today - it.lastPlayedEpochDay }
            InternalLogger.log(context, "[STREAK_TRACE] ArtistStats lectura: artist=$key, existeAnterior=${existing != null}, gapDias=$gapDays, reinicioPorVencimiento=${existing != null && gapDays != null && gapDays > 30}")
            val updated = if (existing == null || (gapDays != null && gapDays > 30)) {
                ArtistStats(distinctDaysHeard = 1, lastPlayedEpochDay = today)
            } else {
                val isNewDay = today > existing.lastPlayedEpochDay
                existing.copy(
                    distinctDaysHeard = if (isNewDay) existing.distinctDaysHeard + 1 else existing.distinctDaysHeard,
                    lastPlayedEpochDay = today
                )
            }
            
            statsMap[key] = updated
            
            // Limpieza LRU básica (200 artistas más recientes)
            if (statsMap.size > 200) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 200)
                keysToRemove.forEach { statsMap.remove(it) }
            }
            
            val newObj = JSONObject()
            statsMap.forEach { (k, v) ->
                val inner = JSONObject()
                inner.put("dd", v.distinctDaysHeard)
                inner.put("lp", v.lastPlayedEpochDay)
                newObj.put(k, inner)
            }
            prefs[ARTIST_STATS] = newObj.toString()
            InternalLogger.log(context, "[STREAK_TRACE] ArtistStats guardado: artist=$key, distinctDaysHeard=${updated.distinctDaysHeard}, lastPlayedEpochDay=${updated.lastPlayedEpochDay}, tamañoMapa=${statsMap.size}")
        }
    }

    /**
     * Actualización quirúrgica de letras.
     * NO toca el estado de reproducción ni otros metadatos para evitar conflictos de concurrencia.
     * @return true si la letra cambió realmente.
     */
    suspend fun updateLyricsOnly(lyric: String, trackKey: String): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            // Solo escribimos si el trackKey coincide y la letra cambió para evitar recomposiciones innecesarias
            val currentTrack = prefs[TRACK_KEY] ?: ""
            val currentLyric = prefs[CURRENT_LYRIC] ?: ""
            if (currentTrack == trackKey && currentLyric != lyric) {
                prefs[CURRENT_LYRIC] = lyric
                prefs[LYRICS_TRACK_KEY] = trackKey
                changed = true
            }
        }
        return changed
    }

    /**
     * Limpia el historial de reproducción.
     */
    suspend fun clearHistory() {
        context.dataStore.edit { prefs ->
            prefs[HISTORY] = encodeHistory(emptyList())
        }
    }

    /**
     * Actualización quirúrgica del dispositivo de salida.
     * Solo se utiliza cuando el hardware cambia sin que cambie la música.
     */
    suspend fun updatePlaybackDevice(name: String, type: Int) {
        context.dataStore.edit { prefs ->
            val currentName = prefs[PLAYBACK_DEVICE_NAME] ?: ""
            val currentType = prefs[PLAYBACK_DEVICE_TYPE] ?: 0
            val isPlaying = prefs[IS_PLAYING] ?: false

            if (currentName != name || currentType != type) {
                prefs[PLAYBACK_DEVICE_NAME] = name
                prefs[PLAYBACK_DEVICE_TYPE] = type

                // REGLA v4.7: Solo reseteamos el reloj si el hardware cambia MIENTRAS suena.
                if (isPlaying) {
                    prefs[LAST_UPDATE_EPOCH] = System.currentTimeMillis()
                    prefs[OBSERVED_AT_REALTIME] = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }

    /**
     * Limpia la información de la sesión activa (Purga Proactiva v2.2).
     * Mantiene intacto el historial y las estadísticas de usuario.
     */
    suspend fun clearActiveSession() {
        context.dataStore.edit { prefs ->
            prefs[TITLE] = ""
            prefs[ARTIST] = ""
            prefs[PACKAGE_NAME] = ""
            prefs[TRACK_KEY] = ""
            prefs[ARTWORK_KEY] = ""
            prefs[ARTWORK_URI] = ""
            prefs[APP_ICON_KEY] = ""
            prefs[IS_PLAYING] = false
            prefs[IS_SESSION_ACTIVE] = false
            prefs[CURRENT_LYRIC] = ""
            prefs[LYRICS_TRACK_KEY] = ""
            // REGLA v4.7: No reseteamos el reloj al purgar (mantenemos inactividad previa)
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

    /**
     * Conjunto Identidad-Atómica-Presentación-1: antes un Triple<Int, Int, Boolean>. Se agrega
     * streakDays (racha de repetición diaria) como 4to valor — vive en el mismo registro
     * (RepeatStats) que playsToday, pero nunca se propagaba hasta la tarjeta "sonando ahora".
     */
    data class SongStats(
        val playsToday: Int,
        val skipStreak: Int,
        val isFrequentArtist: Boolean,
        val streakDays: Int
    )

    /**
     * Sincronía Atómica de Analítica (v3.0): Obtiene las rachas y estatus de favorito 
     * para una canción específica sin depender del estado actual del DataStore.
     */
    suspend fun getStatsFor(title: String, artist: String): SongStats {
        val prefs = context.dataStore.data.first()
        val stats = computeStatsFrom(prefs, title, artist)

        val artistGapDebug = if (artist.isNotBlank()) {
            decodeArtistStats(prefs[ARTIST_STATS].orEmpty())[artist.trim().lowercase()]?.let { java.time.LocalDate.now().toEpochDay() - it.lastPlayedEpochDay }
        } else null
        android.util.Log.d("STREAK_TRACE", "Lectura getStatsFor: identity=$title|$artist, playsToday=${stats.playsToday}, skipStreak=${stats.skipStreak}, isFrequent=${stats.isFrequentArtist}, streakDays=${stats.streakDays}, artistGapDias=$artistGapDebug")

        return stats
    }

    /**
     * Conjunto Corrección-Carrera-Stats-1 (Ronda 2): única fuente de verdad para
     * playsToday/skipStreak/isFrequentArtist, usada tanto por musicInfoFlow como por
     * getStatsFor — antes cada uno tenía su propia copia, y una (skipStreak en musicInfoFlow)
     * quedó desactualizada respecto al formato real de SKIP_STREAKS sin que nada lo detectara.
     * Conjunto Identidad-Atómica-Presentación-1: se agrega streakDays, mismo criterio — evitar
     * una segunda copia de la misma consulta a REPEAT_STATS (ver musicInfoFlow, Cambio 3).
     */
    private fun computeStatsFrom(prefs: Preferences, title: String, artist: String): SongStats {
        val repeatStats = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty())["$title|$artist"]

        val playsToday = repeatStats?.playsToday ?: 0
        val streakDays = repeatStats?.streakDays ?: 0

        val skipStreak = run {
            val json = prefs[SKIP_STREAKS].orEmpty()
            if (json.isBlank()) 0 else {
                runCatching {
                    val obj = JSONObject(json)
                    val inner = obj.optJSONObject("$title|$artist")
                    if (inner != null) {
                        val today = java.time.LocalDate.now().toEpochDay()
                        val gap = today - inner.getLong("lp")
                        if (gap <= 14) inner.getInt("c") else 0
                    } else 0
                }.getOrDefault(0)
            }
        }

        val isFrequent = if (artist.isBlank()) false else {
            val statsMap = decodeArtistStats(prefs[ARTIST_STATS].orEmpty())
            val key = artist.trim().lowercase()
            val stats = statsMap[key]
            val today = java.time.LocalDate.now().toEpochDay()
            stats != null && stats.distinctDaysHeard >= 5 && (today - stats.lastPlayedEpochDay <= 30)
        }

        return SongStats(playsToday, skipStreak, isFrequent, streakDays)
    }
}
