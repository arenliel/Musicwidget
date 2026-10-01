package arenliel.musicwidget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

data class LyricsEntry(val timestampMs: Long, val text: String)
data class LyricsResult(val trackKey: String, val allEntries: List<LyricsEntry>)

class LyricsRepository(private val context: Context) {
    private val lyricsDao = LyricsDatabase.getDatabase(context).lyricsDao()

    // Conjunto Letras-Atomicas-14: resultado de una consulta a la red. Distingue
    // entre lo que LRCLIB confirmó (`Found`, `NotFound`) y lo que no pudo
    // confirmarse (`Unconfirmed`: rechazo de validación de identidad, error de red,
    // timeout o código HTTP inesperado). Solo `NotFound` puede persistirse como
    // "no encontrado" — un resultado sin confirmar no es evidencia de que la letra
    // no exista.
    private sealed class FetchOutcome {
        data class Found(val lrc: String) : FetchOutcome()
        object NotFound : FetchOutcome()
        object Unconfirmed : FetchOutcome()
    }

    // Conjunto Letras-Robustez-1: ámbito PROPIO del repositorio para las consultas de red. Una
    // consulta compartida ya no pertenece a quien la pidió primero: si ese llamador se cancela
    // (el ciclo de letras se relanza varias veces en ráfaga al cambiar de canción), la consulta
    // sigue hasta terminar y guarda su resultado; los demás llamadores reciben el resultado real.
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Conjunto Letras-Atomicas-14 / Letras-Robustez-1: consultas de red en curso, por trackKey.
    // Si ya hay una consulta en curso para la misma canción, las demás esperan su resultado en
    // vez de lanzar una consulta propia.
    private val inFlightFetches = ConcurrentHashMap<String, Deferred<FetchOutcome>>()

    // Conjunto Letras-Atomicas-14: hasta cuándo (epoch ms) no se vuelve a consultar
    // una canción cuyo último resultado quedó sin confirmar. Solo en memoria, nunca
    // se persiste en Room.
    private val unconfirmedCooldownUntil = ConcurrentHashMap<String, Long>()

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

        // Conjunto Letras-Atomicas-14: espera mínima, solo en memoria, antes de volver
        // a consultar una canción cuyo último resultado quedó sin confirmar. Evita que
        // los disparos en ráfaga del ciclo de letras repitan la misma consulta fallida.
        private const val UNCONFIRMED_COOLDOWN_MS = 30_000L
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

        // Conjunto Letras-Atomicas-14: si el último resultado de esta canción quedó sin
        // confirmar hace menos de UNCONFIRMED_COOLDOWN_MS, no se vuelve a consultar aún.
        val cooldownEnd = unconfirmedCooldownUntil[trackKey]
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            android.util.Log.d("LYRICS_RETRY_TRACE", "Cooldown por consulta sin confirmar, sin nueva consulta: trackKey=$trackKey, msRestantes=${cooldownEnd - System.currentTimeMillis()}")
            return@withContext null
        }

        return@withContext when (val outcome = fetchCoalesced(trackKey, artist, title, durationMs / 1000)) {
            is FetchOutcome.Found -> parseLrc(trackKey, outcome.lrc, durationMs)
            // Conjunto Letras-Robustez-1: la persistencia (Room) y el cooldown los realiza la propia
            // consulta compartida (ver persistOutcome), no cada llamador.
            is FetchOutcome.NotFound -> null
            is FetchOutcome.Unconfirmed -> null
        }
    }

    // Conjunto Letras-Robustez-1: solo una consulta de red por trackKey a la vez, y esa consulta es
    // del repositorio, no del llamador que la pidió primero. La consulta corre en `fetchScope` y
    // guarda su propio resultado al terminar (persistOutcome); quien la pide solo espera. Si un
    // llamador se cancela mientras espera (el ciclo de letras se relanza en ráfaga), se cancela
    // únicamente su espera: la consulta continúa y los demás reciben su resultado real. Una
    // cancelación nunca se traduce en `Unconfirmed`.
    private suspend fun fetchCoalesced(trackKey: String, artist: String, title: String, durationSec: Long): FetchOutcome {
        val candidate = fetchScope.async(start = CoroutineStart.LAZY) {
            val outcome = fetchFromNetwork(artist, title, durationSec)
            try {
                persistOutcome(trackKey, outcome)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LyricsRepo", "Error guardando el resultado de la consulta de letras", e)
            }
            outcome
        }
        val existing = inFlightFetches.putIfAbsent(trackKey, candidate)
        val shared: Deferred<FetchOutcome>
        if (existing != null) {
            candidate.cancel()
            android.util.Log.d("LYRICS_RETRY_TRACE", "Consulta ya en curso para esta canción, se espera su resultado: trackKey=$trackKey")
            shared = existing
        } else {
            candidate.invokeOnCompletion { inFlightFetches.remove(trackKey, candidate) }
            shared = candidate
        }
        shared.start()
        return shared.await()
    }

    // Conjunto Letras-Robustez-1: guarda el resultado de una consulta de red. Solo un `NotFound`
    // confirmado se persiste como "no encontrado" (Letras-Atomicas-14); un `Unconfirmed` no
    // persiste nada y activa el cooldown en memoria. Se ejecuta dentro de la propia consulta
    // compartida, una sola vez por consulta, sin depender de que algún llamador siga vivo.
    private suspend fun persistOutcome(trackKey: String, outcome: FetchOutcome) {
        val now = System.currentTimeMillis()
        when (outcome) {
            is FetchOutcome.Found -> {
                unconfirmedCooldownUntil.remove(trackKey)
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        trackKey = trackKey,
                        syncedLyrics = outcome.lrc,
                        plainLyrics = null,
                        timestampFetched = now,
                        lastAccessed = now,
                        notFound = false
                    )
                )
            }
            is FetchOutcome.NotFound -> {
                unconfirmedCooldownUntil.remove(trackKey)
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
            }
            is FetchOutcome.Unconfirmed -> {
                unconfirmedCooldownUntil[trackKey] = now + UNCONFIRMED_COOLDOWN_MS
                android.util.Log.d("LYRICS_RETRY_TRACE", "Consulta sin confirmar: no se cachea notFound, trackKey=$trackKey")
            }
        }
    }

    // Conjunto Letras-Robustez-1: cierra el ámbito de consultas de red. Se invoca al destruirse
    // el servicio.
    fun shutdown() {
        fetchScope.cancel()
    }

    // Conjunto Letras-Atomicas-14: forma canónica usada SOLO para comparar nombres al
    // validar la respuesta de LRCLIB — ignora mayúsculas/minúsculas y marcas
    // diacríticas (tildes). Nunca se usa para construir la consulta que se envía.
    private fun foldForComparison(text: String): String {
        val decomposed = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{M}+"), "").lowercase()
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

    private suspend fun fetchFromNetwork(artist: String, title: String, durationSec: Long): FetchOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Conjunto Letras-Atomicas-14: artista y título se envían tal como llegan,
            // sin ninguna limpieza ni reformateo.
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val durationParam = if (durationSec > 0) "&duration=$durationSec" else ""
            val urlString = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle$durationParam"

            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "MusicWidgetAndroidApp (https://github.com/arenliel/musicwidget)")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                // Conjunto Letras-Atomicas-5: LRCLIB es una base colaborativa y puede
                // tener entradas mal etiquetadas — verificamos que lo que devuelve
                // realmente corresponda al artista/título que pedimos antes de confiar
                // en su contenido.
                // Conjunto Letras-Atomicas-14: la comparación ignora mayúsculas y tildes
                // (LRCLIB también busca sin distinguir tildes), y un rechazo aquí queda
                // como `Unconfirmed`, no como "no encontrado".
                val returnedTrack = json.optString("trackName")
                val returnedArtist = json.optString("artistName")
                val matchesRequest = foldForComparison(returnedTrack) == foldForComparison(title) &&
                    foldForComparison(returnedArtist) == foldForComparison(artist)
                if (!matchesRequest) {
                    Log.e("LyricsRepo", "Respuesta de LRCLIB no coincide con lo solicitado: pedido=$artist|$title, recibido=$returnedArtist|$returnedTrack")
                    Log.d("LYRICS_RETRY_TRACE", "Sin confirmar: motivo=identidad_no_coincide, pedido=$artist|$title, recibido=$returnedArtist|$returnedTrack")
                    return@withContext FetchOutcome.Unconfirmed
                }
                // Conjunto Letras-Atomicas-13: metadatos correctos no garantizan
                // contenido plausible — un bloque con muy pocas líneas cronometradas
                // no es una letra sincronizada completa, aunque venga con la
                // identidad correcta. Se descarta aquí, antes de persistirse.
                val syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (syncedLyrics == null) {
                    return@withContext FetchOutcome.NotFound
                }
                if (!isPlausibleLyrics(syncedLyrics)) {
                    Log.e("LyricsRepo", "Contenido descartado por implausible (menos de $MIN_PLAUSIBLE_TIMED_LINES líneas cronometradas): artista=$artist, título=$title")
                    return@withContext FetchOutcome.NotFound
                }
                return@withContext FetchOutcome.Found(syncedLyrics)
            }
            if (responseCode == 404) {
                return@withContext FetchOutcome.NotFound
            }
            Log.e("LyricsRepo", "Respuesta inesperada de LRCLIB: código=$responseCode")
            Log.d("LYRICS_RETRY_TRACE", "Sin confirmar: motivo=http_$responseCode, trackName=$title")
        } catch (e: Exception) {
            Log.e("LyricsRepo", "Error fetching lyrics", e)
            Log.d("LYRICS_RETRY_TRACE", "Sin confirmar: motivo=excepcion_${e.javaClass.simpleName}, trackName=$title")
        } finally {
            connection?.disconnect()
        }
        FetchOutcome.Unconfirmed
    }

    private fun parseStoredLyrics(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        return parseLrc(trackKey, lrc, durationMs)
    }

    fun parseLrc(trackKey: String, lrc: String, durationMs: Long): LyricsResult {
        // Conjunto Letras-Robustez-1: la interpretación del LRC y la garantía de línea de tiempo
        // coherente viven en LyricsTimeline (lógica pura, con pruebas unitarias).
        val parsed = LyricsTimeline.parse(lrc)
        if (parsed.discardedCount > 0) {
            android.util.Log.d("LYRICS_RETRY_TRACE", "LRC saneado: lineasDescartadas=${parsed.discardedCount}, lineasConservadas=${parsed.entries.size}, trackKey=$trackKey")
        }
        return LyricsResult(trackKey, parsed.entries)
    }
}
