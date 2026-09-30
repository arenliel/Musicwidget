package arenliel.musicwidget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class LyricsEntry(val timestampMs: Long, val text: String)
data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)

class LyricsRepository(private val context: Context) {
    private val lyricsDao = LyricsDatabase.getDatabase(context).lyricsDao()

    companion object {
        // TTL 1h para re-intentos tras un "no encontrado" (ya existía; extraído a
        // constante nombrada en Letras-Atomicas-13, sin cambio de valor ni de comportamiento).
        private const val NOT_FOUND_TTL_MS = 1 * 60 * 60 * 1000L

        // Conjunto Letras-Atomicas-13: TTL de 30 días para contenido "encontrado".
        // Antes de este conjunto no existía ningún límite — una entrada corrupta,
        // sin importar su origen, se servía para siempre. Con este límite, cualquier
        // entrada tiene una ventana acotada tras la cual el sistema vuelve a
        // consultar la fuente externa en vez de perpetuar el contenido guardado.
        private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000L

        // Conjunto Letras-Atomicas-13: un resultado con menos líneas cronometradas
        // que este umbral no se acepta como letra sincronizada completa de una
        // canción real. El umbral es deliberadamente bajo: solo necesita distinguir
        // "esto no es una letra completa" de "esto sí lo es".
        private const val MIN_PLAUSIBLE_TIMED_LINES = 3
    }

    suspend fun getLyrics(trackKey: String, artist: String, title: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Intentar desde Room
        val cached = lyricsDao.getLyrics(trackKey)
        if (cached != null) {
            val now = System.currentTimeMillis()
            if (cached.notFound) {
                // TTL 1h para re-intentos de letras (v3.0)
                if (now - cached.timestampFetched < NOT_FOUND_TTL_MS) {
                    android.util.Log.d("LYRICS_RETRY_TRACE", "TTL bloqueando reintento: trackKey=$trackKey, msDesdeUltimoIntento=${now - cached.timestampFetched}")
                    return@withContext null
                }
            } else {
                // Conjunto Letras-Atomicas-13: una entrada "encontrada" ya no se sirve
                // indefinidamente. Si supera su ventana de vigencia, se trata como
                // vencida y cae al mismo camino de re-consulta a red de más abajo, en
                // vez de perpetuar un contenido potencialmente incorrecto para siempre.
                if (now - cached.timestampFetched < FOUND_TTL_MS) {
                    lyricsDao.updateLastAccessed(trackKey, now)
                    return@withContext parseStoredLyrics(trackKey, cached.syncedLyrics ?: "", durationMs)
                }
                android.util.Log.d("LYRICS_RETRY_TRACE", "TTL de contenido vencido, re-consultando: trackKey=$trackKey, msDesdeUltimoFetch=${now - cached.timestampFetched}")
            }
        }

        // 2. Si no hay, TTL expiró, o el contenido "encontrado" venció, ir a red
        // Conjunto Letras-Atomicas-12: sin duración confirmada, la consulta a LRCLIB queda
        // ambigua entre distintas versiones/ediciones con el mismo título y artista, cada
        // una con su propia sincronización. En vez de arriesgar una coincidencia ambigua,
        // se pospone la consulta hasta que la duración real esté confirmada (llegará poco
        // después vía un nuevo relanzamiento del ciclo de letras).
        if (durationMs <= 0) {
            android.util.Log.d("LYRICS_RETRY_TRACE", "Fetch pospuesto sin duración confirmada: trackKey=$trackKey")
            return@withContext null
        }
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
            // Conjunto Letras-Atomicas-9: sin duración confirmada, un "no encontrado"
            // de LRCLIB no es confiable (catálogo con casi-duplicados por duración).
            if (durationMs > 0) {
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
            } else {
                android.util.Log.d("LYRICS_RETRY_TRACE", "Fallo sin duración confirmada (durationMs=$durationMs): no se cachea notFound, trackKey=$trackKey")
            }
            return@withContext null
        }
    }

    private fun normalizeForSearch(text: String): String {
        return text.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
    }

    // Conjunto Letras-Atomicas-13: cuenta cuántas líneas de un bloque LRC tienen un
    // timestamp válido (`[mm:ss.xx]`). Es la señal de forma usada por
    // `isPlausibleLyrics` — independiente de qué diga el texto, mide si el bloque
    // tiene la extensión mínima que tendría una letra sincronizada real.
    private fun countTimestampedLines(lrc: String): Int {
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")
        return lrc.lines().count { regex.containsMatchIn(it) }
    }

    // Conjunto Letras-Atomicas-13: filtro de plausibilidad de contenido. Se usa
    // tanto en la ingesta de resultados nuevos (`fetchFromNetwork`) como en el
    // saneamiento retroactivo (`purgeImplausibleCachedEntries`), para que ambos
    // apliquen exactamente el mismo criterio.
    private fun isPlausibleLyrics(lrc: String): Boolean {
        return countTimestampedLines(lrc) >= MIN_PLAUSIBLE_TIMED_LINES
    }

    /**
     * Conjunto Letras-Atomicas-13: saneamiento retroactivo. Recorre las entradas
     * "encontradas" ya persistidas y purga (mediante `deleteLyrics`, ya declarado
     * en el DAO pero sin uso hasta ahora) las que no superan el mismo filtro de
     * plausibilidad aplicado desde ahora a las consultas nuevas — cierra la brecha
     * para contenido que ya estaba cacheado antes de esta corrección. Segura de
     * ejecutar en cada arranque del servicio: si no hay nada que purgar, no hace nada.
     */
    suspend fun purgeImplausibleCachedEntries() = withContext(Dispatchers.IO) {
        val foundEntries = lyricsDao.getAllFoundLyrics()
        for (entry in foundEntries) {
            val lrc = entry.syncedLyrics
            if (lrc == null || !isPlausibleLyrics(lrc)) {
                android.util.Log.d("LYRICS_RETRY_TRACE", "Saneamiento: purgando entrada implausible cacheada, trackKey=${entry.trackKey}")
                lyricsDao.deleteLyrics(entry.trackKey)
            }
        }
    }

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
                // Conjunto Letras-Atomicas-5: LRCLIB es una base colaborativa y puede
                // tener entradas mal etiquetadas — verificamos que lo que devuelve
                // realmente corresponda al artista/título que pedimos antes de confiar
                // en su contenido.
                val returnedTrack = json.optString("trackName")
                val returnedArtist = json.optString("artistName")
                val matchesRequest = normalizeForSearch(returnedTrack).equals(normalizeForSearch(title), ignoreCase = true) &&
                    normalizeForSearch(returnedArtist).equals(normalizeForSearch(artist), ignoreCase = true)
                if (!matchesRequest) {
                    Log.e("LyricsRepo", "Respuesta de LRCLIB no coincide con lo solicitado: pedido=$artist|$title, recibido=$returnedArtist|$returnedTrack")
                    return@withContext null
                }
                // Conjunto Letras-Atomicas-13: metadatos correctos no garantizan
                // contenido plausible — un bloque con muy pocas líneas cronometradas
                // no es una letra sincronizada completa, aunque venga con la
                // identidad correcta. Se descarta aquí, antes de persistirse.
                val syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (syncedLyrics != null && !isPlausibleLyrics(syncedLyrics)) {
                    Log.e("LyricsRepo", "Contenido descartado por implausible (menos de $MIN_PLAUSIBLE_TIMED_LINES líneas cronometradas): artista=$artist, título=$title")
                    return@withContext null
                }
                return@withContext syncedLyrics
            }
        } catch (e: Exception) {
            Log.e("LyricsRepo", "Error fetching lyrics", e)
        } finally {
            connection?.disconnect()
        }
        null
    }

    private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        return parseLrc(trackKey, lrc, durationMs)
    }

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
}
