package arenliel.musicwidget

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    val isFrequentArtist: Boolean = false
) {
    /**
     * DETERMINISMO DE ESTADO: Indica si el widget está en una instalación fresca (v1.7.0).
     */
    val isEmpty: Boolean get() = trackKey.isBlank()

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
    val hasPendingArtwork: Boolean = false
)

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

                durationMs =
                    prefs[DURATION_MS]
                        ?: 0L,

                history = decodeHistory(prefs[HISTORY].orEmpty()),

                playsToday = run {
                    val title = prefs[TITLE] ?: ""
                    val artist = prefs[ARTIST] ?: ""
                    val statsMap = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty())
                    statsMap["$title|$artist"]?.playsToday ?: 0
                },

                streakDays = run {
                    val title = prefs[TITLE] ?: ""
                    val artist = prefs[ARTIST] ?: ""
                    val statsMap = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty())
                    statsMap["$title|$artist"]?.streakDays ?: 0
                },

                skipStreak = run {
                    val title = prefs[TITLE] ?: ""
                    val artist = prefs[ARTIST] ?: ""
                    val json = prefs[SKIP_STREAKS].orEmpty()
                    if (json.isBlank()) 0 else {
                        runCatching {
                            val obj = JSONObject(json)
                            obj.optInt("$title|$artist", 0)
                        }.getOrDefault(0)
                    }
                },

                isFrequentArtist = run {
                    val artist = prefs[ARTIST] ?: ""
                    if (artist.isBlank()) false else {
                        val statsMap = decodeArtistStats(prefs[ARTIST_STATS].orEmpty())
                        val key = artist.trim().lowercase()
                        val stats = statsMap[key]
                        val today = java.time.LocalDate.now().toEpochDay()
                        
                        stats != null && stats.distinctDaysHeard >= 5 && (today - stats.lastPlayedEpochDay <= 14)
                    }
                }
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
                HistoryItem(
                    title = obj.getString("t"),
                    artist = obj.getString("a"),
                    packageName = obj.optString("p", ""),
                    artworkPath = obj.optString("ap", ""),
                    artworkKey = obj.optString("ak", obj.optString("k", "")),
                    trackKey = obj.optString("tk", ""),
                    timestamp = obj.getLong("ts"),
                    isSkipped = obj.optBoolean("sk", false),
                    skipStreak = obj.optInt("ss", 0),
                    playsToday = obj.optInt("pt", 0),
                    streakDays = obj.optInt("sd", 0),
                    artworkUri = obj.optString("au", ""),
                    hasPendingArtwork = obj.optBoolean("pa", false)
                )
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
            obj.put("ts", item.timestamp)
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
            prefs[ARTWORK_KEY] = info.artworkKey
            prefs[ARTWORK_URI] = info.artworkUri
            prefs[APP_ICON_KEY] = info.appIconKey
            prefs[IS_PLAYING] = info.isPlaying
            prefs[IS_SESSION_ACTIVE] = info.isSessionActive
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
             * REGLA v4.7: forceUpdate NO resetea el reloj (solo sirve para emitir datos visuales).
             * El reloj solo se mueve si la identidad cambió o si pasamos a PLAYING.
             */
            val shouldResetClock = identityChanged || (playbackStatusChanged && info.isPlaying)

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

            // 1. Buscar coincidencia previa para Validar Inmunidad
            val existingItem = oldHistory.find {
                it.title == item.title && it.artist == item.artist
            }

            if (existingItem != null) {
                InternalLogger.log(context, "LRU: Repetición detectada. Moviendo a la cima: ${item.title}")
            }

            // 2. Aplicar Escudo de Protección: Si ya fue escuchada completa, ignoramos el skip actual
            val isBlessed = existingItem != null && !existingItem.isSkipped
            
            val finalItem = if (isBlessed && item.isSkipped) {
                item.copy(isSkipped = false, skipStreak = 0)
            } else {
                item
            }

            // 3. Filtrar coincidencia previa para mover a la cima (LRU)
            val listWithoutDuplicate = oldHistory.filterNot {
                it.title == item.title && it.artist == item.artist
            }

            // 4. Insertar al principio y limitar a los últimos 10
            val newHistory = (listOf(finalItem) + listWithoutDuplicate).take(10)
            
            prefs[HISTORY] = encodeHistory(newHistory)

            // 5. Sincronización de Rachas: Si es bendecida, limpiamos la racha persistente
            if (isBlessed) {
                resetSkipStreakInternal(prefs, item.title, item.artist)
            }
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
    suspend fun updateSkipStreak(title: String, artist: String, isSkip: Boolean, isPartial: Boolean): Int {
        var newStreak = 0
        context.dataStore.edit { prefs ->
            val json = prefs[SKIP_STREAKS].orEmpty()
            val map = mutableMapOf<String, Int>()
            if (json.isNotBlank()) {
                runCatching {
                    val obj = JSONObject(json)
                    obj.keys().forEach { key -> map[key] = obj.getInt(key) }
                }
            }

            val identity = "$title|$artist"
            val currentStreak = map[identity] ?: 0

            newStreak = when {
                isSkip -> currentStreak + 1
                isPartial -> 0
                else -> 0 // Completada
            }

            if (newStreak > 0) {
                map[identity] = newStreak
            } else {
                map.remove(identity)
            }

            // Limpieza LRU básica: mantener solo los últimos 30 registros de racha
            if (map.size > 30) {
                val keysToRemove = map.keys.take(map.size - 30)
                keysToRemove.forEach { map.remove(it) }
            }

            val newObj = JSONObject()
            map.forEach { (k, v) -> newObj.put(k, v) }
            prefs[SKIP_STREAKS] = newObj.toString()
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

            // Limpieza LRU: Mantener solo las últimas 30 canciones con racha activa
            if (statsMap.size > 30) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 30)
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
        }
        return finalPlaysToday to finalStreakDays
    }

    /**
     * Actualiza la analítica de fidelidad del artista.
     * Basado en Días Distintos escuchados.
     */
    suspend fun updateArtistStats(artistName: String) {
        if (artistName.isBlank()) return
        context.dataStore.edit { prefs ->
            val statsMap = decodeArtistStats(prefs[ARTIST_STATS].orEmpty()).toMutableMap()
            val key = artistName.trim().lowercase()
            val today = java.time.LocalDate.now().toEpochDay()
            
            val existing = statsMap[key]
            val updated = if (existing == null) {
                ArtistStats(distinctDaysHeard = 1, lastPlayedEpochDay = today)
            } else {
                val isNewDay = today > existing.lastPlayedEpochDay
                existing.copy(
                    distinctDaysHeard = if (isNewDay) existing.distinctDaysHeard + 1 else existing.distinctDaysHeard,
                    lastPlayedEpochDay = today
                )
            }
            
            statsMap[key] = updated
            
            // Limpieza LRU básica (30 artistas más recientes)
            if (statsMap.size > 30) {
                val keysToRemove = statsMap.keys.take(statsMap.size - 30)
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
     * Sincronía Atómica de Analítica (v3.0): Obtiene las rachas y estatus de favorito 
     * para una canción específica sin depender del estado actual del DataStore.
     */
    suspend fun getStatsFor(title: String, artist: String): Triple<Int, Int, Boolean> {
        val prefs = context.dataStore.data.first()
        
        val playsToday = run {
            val statsMap = decodeRepeatStats(prefs[REPEAT_STATS].orEmpty())
            statsMap["$title|$artist"]?.playsToday ?: 0
        }

        val skipStreak = run {
            val json = prefs[SKIP_STREAKS].orEmpty()
            if (json.isBlank()) 0 else {
                runCatching {
                    val obj = JSONObject(json)
                    obj.optInt("$title|$artist", 0)
                }.getOrDefault(0)
            }
        }

        val isFrequent = if (artist.isBlank()) false else {
            val statsMap = decodeArtistStats(prefs[ARTIST_STATS].orEmpty())
            val key = artist.trim().lowercase()
            val stats = statsMap[key]
            val today = java.time.LocalDate.now().toEpochDay()
            stats != null && stats.distinctDaysHeard >= 5 && (today - stats.lastPlayedEpochDay <= 14)
        }

        return Triple(playsToday, skipStreak, isFrequent)
    }
}
