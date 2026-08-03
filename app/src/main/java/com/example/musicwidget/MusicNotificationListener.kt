package arenliel.musicwidget

import android.app.Notification
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MusicNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var musicDataStore: MusicDataStore

    private val serviceJob = SupervisorJob()

    private val serviceScope =
        CoroutineScope(
            Dispatchers.IO + serviceJob
        )

    /*
     * Protege las escrituras de archivos.
     */
    private val fileMutex = Mutex()

    /*
     * Protege la creación y eliminación de trabajos de artwork
     * en vuelo.
     */
    private val artworkInFlightMutex = Mutex()

    /*
     * Garantiza que solo una Zona de Commit (disco + DataStore) 
     * se ejecute a la vez en todo el servicio.
     */
    private val commitMutex = Mutex()

    /*
     * Callbacks registrados para cada MediaController activo.
     */
    private val controllerCallbacks =
        mutableMapOf<
                MediaController,
                MediaController.Callback
                >()

    /*
     * Job utilizado para agrupar ráfagas de callbacks.
     */
    private var pendingRefreshJob: Job? = null

    /*
     * Controller seleccionado actualmente.
     */
    private var selectedController: MediaController? = null

    private data class LyricsEntry(val timestampMs: Long, val text: String)
    private data class LyricsResult(val trackKey: String, val entries: List<LyricsEntry>)

    private var currentLyrics: LyricsResult? = null
    private var lyricsUpdateJob: Job? = null
    private var lyricsFetchJob: Job? = null

    /*
     * Snapshot más reciente observado.
     *
     * Sirve para evitar procesar repetidamente la misma metadata
     * mientras Spotify está enviando varios callbacks consecutivos.
     */
    private var lastObservedSnapshot: MediaSnapshot? = null

    /*
     * Snapshot cuya actualización terminó correctamente.
     */
    private var lastAppliedSnapshot: MediaSnapshot? = null

    /*
     * Snapshot actualmente en proceso.
     */
    private var inFlightSnapshot: MediaSnapshot? = null

    /*
     * Generation monotónica.
     *
     * Cada snapshot nuevo recibe una generación.
     *
     * Si una operación antigua termina después de que llegue
     * un snapshot más reciente, se descarta.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    /*
     * Artwork actualmente descargándose/resolviéndose.
     *
     * La clave es artworkKey y el valor es un Deferred compartido.
     *
     * Esto permite que varias solicitudes simultáneas de la misma
     * portada esperen el mismo resultado.
     *
     * Ejemplo:
     *
     * artworkKey X
     *      |
     *      +-- solicitud A ----\
     *      |                    \
     *      +-- solicitud B ------> mismo Deferred
     *      |                    /
     *      +-- solicitud C ----/
     *               |
     *               v
     *          UNA descarga
     */
    private val artworkInFlight =
        mutableMapOf<
                String,
                Deferred<Bitmap?>
                >()

    /*
     * Artwork guardado actualmente en disco.
     */
    private var savedArtworkKey: String? = null

    /*
     * Icono de la app guardado actualmente en disco.
     */
    private var savedAppIconKey: String? = null

    /*
     * Último título enviado a la preview para evitar duplicados innecesarios.
     */
    private var lastPreviewTitle: String? = null

    /*
     * Marca de tiempo de la última publicación de preview.
     * Android 15 limita esta API a ~2 veces por hora.
     */
    private var lastPreviewUpdate: Long = 0L

    /*
     * Cache de artwork en memoria.

     * La clave es artworkUri o un fallback estable.
     */
    private val artworkCache =
        object : LruCache<String, Bitmap>(
            ARTWORK_CACHE_SIZE_KB
        ) {

            override fun sizeOf(
                key: String,
                value: Bitmap
            ): Int {
                return value.byteCount / 1024
            }
        }

    private val sessionsChangedListener =
        MediaSessionManager
            .OnActiveSessionsChangedListener { controllers ->

                updateActiveSessions(
                    controllers
                )
            }

    private data class MediaSnapshot(
        val packageName: String,
        val title: String,
        val artist: String,
        val album: String?,
        val mediaId: String?,
        val artworkUri: String?,
        val playbackState: Int,
        val isSessionActive: Boolean,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val recordedAt: Long = System.currentTimeMillis()
    ) {

        /*
         * Identidad lógica de la pista.
         */
        val trackKey: String
            get() = buildString {

                append(packageName)
                append('|')

                append(mediaId.orEmpty())
                append('|')

                append(title)
                append('|')

                append(artist)
                append('|')

                append(album.orEmpty())
            }

        /*
         * Identidad del artwork.
         */
        val artworkKey: String
            get() =
                artworkUri
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: buildString {

                        append(packageName)
                        append('|')

                        append(mediaId.orEmpty())
                        append('|')

                        append(title)
                        append('|')

                        append(artist)
                        append('|')

                        append(album.orEmpty())
                    }

        /*
         * Identidad completa del snapshot.
         */
        val contentKey: String
            get() =
                "$trackKey|$artworkKey|$playbackState"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[DIAGNOSTIC] SERVICE_LIFECYCLE: onCreate - Process started")

        mediaSessionManager =
            getSystemService(
                Context.MEDIA_SESSION_SERVICE
            ) as MediaSessionManager

        musicDataStore =
            MusicDataStore(
                applicationContext
            )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        /*
         * Al conectar, sincronizamos el estado del artwork guardado.
         */
        serviceScope.launch {
            val keyFile = File(filesDir, ALBUM_ART_KEY_FILE)
            if (keyFile.exists()) {
                runCatching {
                    savedArtworkKey = keyFile.readText().trim().takeIf { it.isNotEmpty() }
                }
            }
            val iconKeyFile = File(filesDir, APP_ICON_KEY_FILE)
            if (iconKeyFile.exists()) {
                runCatching {
                    savedAppIconKey = iconKeyFile.readText().trim().takeIf { it.isNotEmpty() }
                }
            }
            
            // Publicar la previsualización real al inicio (Respetando el Rate Limit de Android 15)
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    GlanceAppWidgetManager(applicationContext).setWidgetPreviews(MusicWidgetReceiver::class)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo publicar la preview inicial", e)
                }
            }
        }

        val componentName =
            ComponentName(
                this,
                MusicNotificationListener::class.java
            )

        mediaSessionManager
            .addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                componentName
            )

        val initialControllers =
            mediaSessionManager
                .getActiveSessions(
                    componentName
                )

        updateActiveSessions(
            initialControllers
        )
    }

    /*
     * La notificación multimedia actúa como disparador.
     *
     * MediaSession sigue siendo la fuente principal de metadata.
     */
    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        val notification =
            sbn.notification

        if (
            notification.category ==
            Notification.CATEGORY_TRANSPORT
        ) {

            requestRefresh(
                fast = true,
                reason = "media_notification"
            )
        }
    }

    private fun updateActiveSessions(
        newControllers: List<MediaController>?
    ) {

        /*
         * Desregistramos callbacks antiguos.
         */
        controllerCallbacks
            .forEach {
                    (controller, callback) ->

                runCatching {
                    controller
                        .unregisterCallback(
                            callback
                        )
                }
            }

        controllerCallbacks.clear()

        val controllers =
            newControllers.orEmpty()

        /*
         * Si el controller seleccionado desapareció,
         * seleccionaremos otro.
         */
        if (
            selectedController != null &&
            controllers.none {
                it.sessionToken ==
                        selectedController
                            ?.sessionToken
            }
        ) {

            selectedController =
                null
        }

        /*
         * Registramos callbacks para las sesiones activas.
         */
        controllers.forEach { controller ->

            val callback =
                object : MediaController.Callback() {

                    override fun onMetadataChanged(
                        metadata: MediaMetadata?
                    ) {

                        requestRefresh(
                            reason = "metadata"
                        )
                    }

                    override fun onPlaybackStateChanged(
                        state: PlaybackState?
                    ) {

                        requestRefresh(
                            reason = "playback_state"
                        )
                    }

                    override fun onSessionDestroyed() {

                        if (
                            selectedController
                                ?.sessionToken ==
                            controller.sessionToken
                        ) {

                            selectedController =
                                null
                        }

                        requestRefresh(
                            reason =
                                "session_destroyed"
                        )
                    }
                }

            runCatching {

                controller.registerCallback(
                    callback
                )

                controllerCallbacks[
                    controller
                ] = callback

            }.onFailure { error ->

                Log.w(
                    TAG,
                    "No se pudo registrar callback para " +
                            controller.packageName,
                    error
                )
            }
        }

        requestRefresh(
            reason =
                "active_sessions_changed"
        )
    }

    /*
     * Punto único de entrada para las actualizaciones.
     *
     * Agrupa ráfagas de callbacks consecutivos.
     */
    private fun requestRefresh(
        fast: Boolean = false,
        reason: String
    ) {

        pendingRefreshJob?.cancel()

        pendingRefreshJob =
            serviceScope.launch {

                delay(
                    if (fast) {
                        FAST_DEBOUNCE_MS
                    } else {
                        NORMAL_DEBOUNCE_MS
                    }
                )

                /*
                 * Ventana adicional para que Spotify termine
                 * de actualizar title/artist/artwork.
                 */
                if (!fast) {
                    delay(
                        METADATA_STABILIZATION_MS
                    )
                }

                try {

                    refreshBestSession(
                        reason
                    )

                } catch (
                    e: CancellationException
                ) {

                    /*
                     * Cancelación esperada.
                     *
                     * No se registra como error.
                     */
                    throw e

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Error actualizando sesión multimedia",
                        e
                    )
                }
            }
    }

    private suspend fun refreshBestSession(
        reason: String
    ) {

        val componentName =
            ComponentName(
                this,
                MusicNotificationListener::class.java
            )

        val activeSessions =
            mediaSessionManager
                .getActiveSessions(
                    componentName
                )

        if (
            activeSessions.isEmpty()
        ) {
            // No hay sesiones activas: Marcar como RECIENTE (History)
            selectedController = null
            
            // Si teníamos una canción, la mantenemos pero desactivamos la sesión
            lastAppliedSnapshot?.let { last ->
                val snapshot = last.copy(
                    playbackState = PlaybackState.STATE_NONE,
                    isSessionActive = false
                )
                processSnapshot(null, null, snapshot, "no_active_sessions")
            }
            return
        }

        val controller =
            selectBestController(
                activeSessions
            )
                ?: return

        val metadata =
            controller.metadata
                ?: return

        val snapshot =
            createSnapshot(
                controller,
                metadata
            )
                ?: return

        /*
         * Si ya observamos exactamente este snapshot,
         * no iniciamos otro procesamiento.
         */
        if (
            snapshot.contentKey ==
            lastObservedSnapshot
                ?.contentKey
        ) {

            return
        }

        /*
         * Registramos inmediatamente el snapshot observado.
         *
         * No esperamos a que termine la descarga.
         */
        // lastObservedSnapshot = snapshot // MOVIDO A COMMIT_ZONE PARA EVITAR SNAPSHOTS HUÉRFANOS

        /*
         * Si exactamente este snapshot está actualmente
         * en proceso, no lo duplicamos.
         */
        if (
            snapshot.contentKey ==
            inFlightSnapshot
                ?.contentKey
        ) {

            return
        }

        /*
         * Si ya está aplicado, no hay nada que hacer.
         */
        if (
            snapshot.contentKey ==
            lastAppliedSnapshot
                ?.contentKey
        ) {

            return
        }

        processSnapshot(
            controller =
                controller,
            metadata =
                metadata,
            snapshot =
                snapshot,
            reason =
                reason
        )
    }

    private fun selectBestController(
        activeSessions:
        List<MediaController>
    ): MediaController? {

        /*
         * 1. Mantener el controller actual si sigue reproduciendo.
         */
        selectedController
            ?.let { current ->

                val currentActive =
                    activeSessions
                        .firstOrNull {

                            it.sessionToken ==
                                    current.sessionToken
                        }

                if (
                    currentActive != null &&
                    currentActive
                        .playbackState
                        ?.state ==
                    PlaybackState.STATE_PLAYING
                ) {

                    selectedController =
                        currentActive

                    return currentActive
                }
            }

        /*
         * 2. Si solo hay una sesión reproduciendo,
         * elegirla.
         */
        val playingSessions =
            activeSessions.filter {

                it.playbackState?.state ==
                        PlaybackState.STATE_PLAYING
            }

        if (
            playingSessions.size == 1
        ) {

            selectedController =
                playingSessions.first()

            return selectedController
        }

        /*
         * 3. Si hay varias sesiones reproduciendo,
         * conservar la actual si todavía existe.
         */
        selectedController
            ?.let { current ->

                activeSessions
                    .firstOrNull {

                        it.sessionToken ==
                                current.sessionToken
                    }
                    ?.let { existing ->

                        selectedController =
                            existing

                        return existing
                    }
            }

        /*
         * 4. Fallback.
         */
        val fallback =
            playingSessions
                .firstOrNull()
                ?: activeSessions
                    .firstOrNull()

        selectedController =
            fallback

        return fallback
    }

    private fun createSnapshot(
        controller: MediaController,
        metadata: MediaMetadata
    ): MediaSnapshot? {

        val title =
            metadata
                .getString(
                    MediaMetadata.METADATA_KEY_TITLE
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val artist =
            metadata
                .getString(
                    MediaMetadata.METADATA_KEY_ARTIST
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Unknown Artist"

        val album =
            metadata.getString(
                MediaMetadata.METADATA_KEY_ALBUM
            )

        val mediaId =
            metadata.getString(
                MediaMetadata.METADATA_KEY_MEDIA_ID
            )

        val artworkUri =
            metadata
                .getString(
                    MediaMetadata.METADATA_KEY_ART_URI
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: metadata
                    .getString(
                        MediaMetadata
                            .METADATA_KEY_ALBUM_ART_URI
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }

        val playbackState =
            controller
                .playbackState
                ?.state
                ?: PlaybackState.STATE_NONE

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = controller.playbackState?.position ?: 0L

        return MediaSnapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            mediaId = mediaId,
            artworkUri = artworkUri,
            playbackState = playbackState,
            isSessionActive = true,
            durationMs = duration,
            positionMs = position,
            recordedAt = System.currentTimeMillis()
        )
    }

    private suspend fun processSnapshot(
        controller: MediaController?,
        metadata: MediaMetadata?,
        snapshot: MediaSnapshot,
        reason: String
    ) {

        /*
         * Creamos una nueva generación de forma atómica.
         */
        val myGeneration =
            generation.incrementAndGet()

        val previousSnapshot =
            lastAppliedSnapshot

        val trackChanged =
            previousSnapshot
                ?.trackKey !=
                    snapshot.trackKey

        val artworkChanged =
            previousSnapshot
                ?.artworkKey !=
                    snapshot.artworkKey

        Log.d(
            TAG,
            "[DIAGNOSTIC] processSnapshot START #$myGeneration | Track=${snapshot.title} | reason=$reason"
        )

        /*
         * Marcamos como in-flight.
         */
        inFlightSnapshot =
            snapshot

        try {

            /*
             * 1. Resolución de recursos visuales (Fase Cancelable).
             * No tocamos el DataStore ni el disco todavía.
             */
            var resolvedArtwork: Bitmap? = null
            var resolvedAppIcon: Bitmap? = null
            var resolvedIconKey: String? = null

            if (controller != null && metadata != null && 
                (trackChanged || artworkChanged || savedArtworkKey == null)) {
                
                // A. Portada
                resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
                    resolveArtworkDeduplicated(
                        controller = controller,
                        metadata = metadata,
                        artworkKey = snapshot.artworkKey,
                        generation = myGeneration
                    )
                }

                // B. Icono de app
                resolvedAppIcon = resolveAppIcon(snapshot.packageName)
                resolvedIconKey = resolvedAppIcon?.let { "${snapshot.packageName}_${System.currentTimeMillis()}" }

                // D. Letras (LRCLIB)
                if (trackChanged) {
                    lyricsUpdateJob?.cancel()
                    lyricsFetchJob?.cancel()
                    currentLyrics = null
                    
                    lyricsFetchJob = serviceScope.launch {
                        val entries = fetchLyrics(snapshot.artist, snapshot.title, snapshot.durationMs / 1000)
                        if (entries != null && isActive) {
                            currentLyrics = LyricsResult(snapshot.trackKey, entries)
                            startLyricsShowcase(snapshot)
                        }
                    }
                } else if (snapshot.playbackState == PlaybackState.STATE_PLAYING) {
                    // DETECCIÓN DE SEEK (Salto de tiempo)
                    val lastSnapshot = lastAppliedSnapshot
                    if (lastSnapshot != null) {
                        val elapsedSinceLast = System.currentTimeMillis() - lastSnapshot.recordedAt
                        val expectedPos = lastSnapshot.positionMs + elapsedSinceLast
                        val actualPos = snapshot.positionMs
                        
                        if (Math.abs(expectedPos - actualPos) > 3000) {
                            Log.d(TAG, "[LYRICS] Salto de tiempo detectado. Resincronizando...")
                            startLyricsShowcase(snapshot)
                        } else if (lyricsUpdateJob?.isActive != true) {
                            startLyricsShowcase(snapshot)
                        }
                    } else if (lyricsUpdateJob?.isActive != true) {
                        startLyricsShowcase(snapshot)
                    }
                } else if (snapshot.isSessionActive) {
                    // ESTADO PAUSADO: Iniciamos ciclo de alternancia lenta
                    if (lyricsUpdateJob?.isActive != true) {
                        startPausedLyricsCycle(snapshot)
                    }
                } else {
                    // SESIÓN DESTRUIDA: Limpieza absoluta
                    lyricsUpdateJob?.cancel()
                    lyricsUpdateJob = null
                    currentLyrics = null
                }
            }

            /*
             * Punto de Control de Generación (Filtro Temprano):
             * Si llegó un snapshot más nuevo mientras suspendíamos, salimos.
             */
            val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
            if (myGeneration != generation.get() && !isStillRelevant) {
                Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
                return
            }

            /*
             * 2. ACTUALIZACIÓN ATÓMICA (Fase No Cancelable / Zona de Commit).
             * Aquí realizamos las operaciones irreversibles en disco y DataStore.
             * Usamos commitMutex para evitar colisiones entre múltiples ejecuciones.
             */
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                commitMutex.withLock {

                    /*
                     * Punto de Control de Generación (Filtro Crítico dentro de Lock):
                     * Verificamos de nuevo tras adquirir el mutex por si llegó algo nuevo.
                     */
                    val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
                    if (myGeneration != generation.get() && !isStillRelevantInLock) {
                        Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
                        return@withLock
                    }

                    // A. Persistencia en Disco (Solo si hubo cambios o es necesario)
                    if (controller != null && metadata != null && 
                        (trackChanged || artworkChanged || savedArtworkKey == null)) {
                        
                        if (resolvedArtwork != null) {
                            saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE)
                            saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                            savedArtworkKey = snapshot.artworkKey
                        } else if (trackChanged || artworkChanged) {
                            // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
                            saveBitmapToFile(getPlaceholderBitmap(), ALBUM_ART_FILE)
                            saveTextToFile("", ALBUM_ART_KEY_FILE)
                            savedArtworkKey = null
                        }

                        if (resolvedAppIcon != null && resolvedIconKey != null) {
                            saveBitmapToFile(resolvedAppIcon, APP_ICON_FILE)
                            saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
                            savedAppIconKey = resolvedIconKey
                        } else if (trackChanged) {
                            // Blindaje: Si el track cambia y no hay icono nuevo, limpiamos el anterior
                            // para evitar mostrar el icono de la app anterior (Efecto "Un solo cuerpo")
                            saveTextToFile("", APP_ICON_KEY_FILE)
                            savedAppIconKey = null
                        }
                    }

                // B. Guardado en DataStore (Estado completo y final)
                val currentInfo = musicDataStore.musicInfoFlow.first()
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                
                // Conservamos la letra si la sesión sigue activa (aunque esté pausada)
                val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                
                val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                val finalMusicInfo = MusicInfo(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    packageName = snapshot.packageName,
                    trackKey = snapshot.trackKey,
                    artworkKey = snapshot.artworkKey,
                    artworkUri = snapshot.artworkUri ?: "",
                    appIconKey = savedAppIconKey ?: "",
                    isPlaying = isPlaying,
                    isSessionActive = snapshot.isSessionActive,
                    currentLyric = finalLyric,
                    lyricsTrackKey = finalLyricKey
                )

                musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = true)

                /*
                 * Notificación única al widget con todos los recursos listos.
                 */
                Log.d(TAG, "[DIAGNOSTIC] COMMIT_ZONE: #${myGeneration} | Track=${snapshot.title} | ArtSync=${resolvedArtwork != null} | IconSync=${resolvedAppIcon != null}")

                // --- RESTAURACIÓN DE PREVIEW OFICIAL GLANCE (Sincronización Viva Estable) ---
                if (Build.VERSION.SDK_INT >= 35 && snapshot.playbackState == PlaybackState.STATE_PLAYING) {
                    val now = System.currentTimeMillis()
                    val titleChanged = snapshot.title != lastPreviewTitle
                    
                    // Publicamos respetando el Rate Limit de Android 15 (~2/hora)
                    if (titleChanged && (now - lastPreviewUpdate > 20 * 60 * 1000L)) {
                        lastPreviewUpdate = now
                        lastPreviewTitle = snapshot.title
                        serviceScope.launch {
                            try {
                                GlanceAppWidgetManager(applicationContext).setWidgetPreviews(MusicWidgetReceiver::class)
                                Log.d(TAG, "[DIAGNOSTIC] PREVIEW_PUSH_SUCCESS: ${snapshot.title}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Fallo al publicar preview", e)
                            }
                        }
                    }
                }

                MusicWidget().updateAll(applicationContext)

                    /*
                     * Marcamos como aplicado para el tracking interno.
                     */
                    lastAppliedSnapshot =
                        snapshot

                    /*
                     * Actualizamos el último observado solo tras un commit exitoso.
                     * Esto garantiza que si una actualización es cancelada antes del commit,
                     * la siguiente no sea ignorada por error como un duplicado falso.
                     */
                    lastObservedSnapshot = snapshot
                    lastCommittedInfo = finalMusicInfo
                }
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "[DIAGNOSTIC] CANCELLED: #$myGeneration aborted during resolution")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error en pipeline atómico #$myGeneration", e)
        } finally {

            /*
             * Solo limpiamos inFlight si sigue siendo
             * este mismo snapshot.
             */
            if (
                inFlightSnapshot
                    ?.contentKey ==
                snapshot.contentKey
            ) {

                inFlightSnapshot =
                    null
            }
        }
    }

    /*
     * Resuelve artwork usando:
     *
     * 1. Cache de memoria.
     * 2. Deferred compartido en artworkInFlight.
     * 3. Descarga/resolución real.
     *
     * Varias solicitudes simultáneas de la misma portada
     * comparten exactamente el mismo Deferred.
     */
    private fun startPausedLyricsCycle(snapshot: MediaSnapshot) {
        lyricsUpdateJob?.cancel()
        val lyrics = currentLyrics ?: return

        lyricsUpdateJob = serviceScope.launch {
            // Buscamos la línea que quedó en el momento de la pausa
            val pausedPos = snapshot.positionMs
            val lastEntry = lyrics.entries.lastOrNull { it.timestampMs <= pausedPos }
            if (lastEntry == null) return@launch

            var showLyric = true
            while (isActive) {
                val current = musicDataStore.musicInfoFlow.first()
                if (current.trackKey != snapshot.trackKey) break

                musicDataStore.saveMusicInfo(
                    info = current.copy(
                        currentLyric = if (showLyric) lastEntry.text else "",
                        lyricsTrackKey = snapshot.trackKey
                    ),
                    forceUpdate = false
                )
                MusicWidget().updateAll(applicationContext)

                showLyric = !showLyric // Alternamos para el siguiente ciclo
                delay(60000L) // Alternancia cada 60 segundos (Bajo consumo)
            }
        }
    }

    private fun normalizeForSearch(text: String): String {
        return text.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
    }

    private suspend fun fetchLyrics(artist: String, title: String, duration: Long): List<LyricsEntry>? = withContext(Dispatchers.IO) {
        try {
            // Normalización suave para mejorar la tasa de éxito (ej. quitar "(Remastered)")
            val cleanArtist = URLEncoder.encode(normalizeForSearch(artist), "UTF-8")
            val cleanTitle = URLEncoder.encode(normalizeForSearch(title), "UTF-8")
            
            val durationParam = if (duration > 0) "&duration=$duration" else ""
            val urlString = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle$durationParam"
            
            val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "MusicWidgetAndroidApp (https://github.com/arenliel/musicwidget)")
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val lrc = json.optString("syncedLyrics")
                if (lrc.isNotBlank()) return@withContext parseLrc(lrc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LYRICS] Error en petición", e)
        }
        null
    }

    private fun parseLrc(lrc: String): List<LyricsEntry> {
        val entries = mutableListOf<LyricsEntry>()
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
                if (text.isNotBlank()) entries.add(LyricsEntry(totalMs, text))
            }
        }
        return entries
    }

    private fun startLyricsShowcase(snapshot: MediaSnapshot) {
        lyricsUpdateJob?.cancel()
        val lyrics = currentLyrics ?: return
        if (snapshot.playbackState != PlaybackState.STATE_PLAYING || snapshot.durationMs <= 0) return

        lyricsUpdateJob = serviceScope.launch {
            val duration = snapshot.durationMs
            // Hitos de porcentaje
            val milestones = listOf(0.05, 0.30, 0.60, 0.85)
            val completedMilestones = mutableSetOf<Double>()

            while (isActive) {
                val currentPos = snapshot.positionMs + (System.currentTimeMillis() - snapshot.recordedAt)
                val currentProgress = currentPos.toDouble() / duration

                // Buscar hito de entrada
                val currentMilestone = milestones.firstOrNull { 
                    currentProgress >= it && !completedMilestones.contains(it) 
                }

                if (currentMilestone != null) {
                    completedMilestones.add(currentMilestone)
                    
                    var linesShown = 0
                    while (linesShown < 4 && isActive) {
                        val pos = snapshot.positionMs + (System.currentTimeMillis() - snapshot.recordedAt)
                        val entryIndex = lyrics.entries.indexOfLast { it.timestampMs <= pos }
                        
                        if (entryIndex != -1) {
                            val entry = lyrics.entries[entryIndex]
                            updateLyricInWidget(snapshot, entry.text)
                            linesShown++
                            
                            val nextEntry = lyrics.entries.getOrNull(entryIndex + 1)
                            val waitTime: Long = if (nextEntry != null) {
                                (nextEntry.timestampMs - pos).coerceIn(2500L, 8000L)
                            } else 5000L
                            
                            delay(waitTime)
                        } else {
                            delay(2000L)
                        }
                    }
                    // Reset tras el bloque de 4 estrofas
                    updateLyricInWidget(snapshot, "")
                }
                
                delay(10000L) // Comprobación de hito cada 10 seg
            }
        }
    }

    private suspend fun updateLyricInWidget(snapshot: MediaSnapshot, lyric: String) {
        val current = musicDataStore.musicInfoFlow.first()
        if (current.trackKey == snapshot.trackKey) {
            musicDataStore.saveMusicInfo(
                info = current.copy(currentLyric = lyric, lyricsTrackKey = snapshot.trackKey),
                forceUpdate = false
            )
            MusicWidget().updateAll(applicationContext)
        }
    }

    private fun resolveAppIcon(packageName: String): Bitmap? {
        return try {
            val notifications = getActiveNotifications()
            // 1. Buscar notificación con EXTRA_MEDIA_SESSION (Prioridad máxima)
            val mediaNotif = notifications.firstOrNull { 
                it.packageName == packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) 
            } ?: notifications.firstOrNull { it.packageName == packageName }

            mediaNotif?.notification?.smallIcon?.loadDrawable(this)?.toBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo icono de app para $packageName", e)
            null
        }
    }

    private suspend fun resolveArtworkDeduplicated(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String,
        generation: Long
    ): Bitmap? {

        /*
         * 1. Cache rápida.
         */
        artworkCache
            .get(artworkKey)
            ?.let { bitmap ->

                Log.d(
                    TAG,
                    "Artwork cache hit: $artworkKey"
                )

                return bitmap
            }

        /*
         * 2. Obtenemos o creamos un Deferred compartido.
         */
        val deferred =
            getOrCreateArtworkDeferred(
                controller =
                    controller,
                metadata =
                    metadata,
                artworkKey =
                    artworkKey,
                generation =
                    generation
            )

        /*
         * 3. Esperamos el resultado.
         *
         * Si otra actualización pidió la misma portada,
         * ambas esperan este mismo Deferred.
         */
        return deferred.await()
    }

    /*
     * Obtiene un Deferred existente o crea uno nuevo.
     *
     * Esta función es el núcleo de la deduplicación de descargas.
     */
    private suspend fun getOrCreateArtworkDeferred(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String,
        generation: Long
    ): Deferred<Bitmap?> {

        artworkInFlightMutex.withLock {

            /*
             * Comprobamos de nuevo la cache dentro del lock.
             */
            artworkCache
                .get(artworkKey)
                ?.let { bitmap ->

                    return CompletableDeferred(
                        bitmap
                    )
                }

            /*
             * Si ya existe una operación para esta clave,
             * reutilizamos exactamente el mismo Deferred.
             */
            artworkInFlight[
                artworkKey
            ]?.let { existing ->

                if (
                    existing.isActive
                ) {

                    Log.d(
                        TAG,
                        "Artwork ya está en vuelo; " +
                                "reutilizando Deferred: " +
                                artworkKey
                    )

                    return existing
                }

                /*
                 * Si terminó pero no quedó eliminado,
                 * limpiamos la entrada.
                 */
                artworkInFlight.remove(
                    artworkKey
                )
            }

            /*
             * Creamos UNA única operación.
             *
             * Se utiliza async sobre serviceScope para que el Deferred
             * sea compartido por todos los consumidores.
             */
            val deferred =
                serviceScope.async {

                    try {

                        val bitmap =
                            findRealAlbumArt(
                                controller =
                                    controller,
                                metadata =
                                    metadata,
                                artworkKey =
                                    artworkKey
                            )

                        /*
                         * Solo guardamos en cache si la operación
                         * sigue siendo válida o si el artworkKey sigue siendo
                         * el que la aplicación está observando.
                         */
                        val isStillRelevant = artworkKey == lastObservedSnapshot?.artworkKey

                        if (
                            isActive &&
                            (generation ==
                            this@MusicNotificationListener
                                .generation.get() || isStillRelevant) &&
                            bitmap != null
                        ) {

                            artworkCache.put(
                                artworkKey,
                                bitmap
                            )
                        }

                        bitmap

                    } catch (
                        e: CancellationException
                    ) {

                        throw e

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Error resolviendo artwork",
                            e
                        )

                        null

                    } finally {

                        /*
                         * Eliminamos la operación del mapa.
                         *
                         * Solo la eliminamos si sigue siendo
                         * exactamente la misma instancia.
                         */
                        artworkInFlightMutex.withLock {

                            val current =
                                artworkInFlight[
                                    artworkKey
                                ]

                            if (
                                current ===
                                coroutineContext[Job]
                            ) {

                                artworkInFlight.remove(
                                    artworkKey
                                )
                            }
                        }
                    }
                }

            artworkInFlight[
                artworkKey
            ] = deferred

            return deferred
        }
    }

    private suspend fun findRealAlbumArt(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String
    ): Bitmap? =
        withContext(
            Dispatchers.IO
        ) {

            val minArtDimension =
                MIN_ART_DIMENSION

            Log.d(
                TAG,
                "---- Resolviendo portada ----"
            )

            /*
             * 1. ART bitmap directo.
             */
            metadata
                .getBitmap(
                    MediaMetadata.METADATA_KEY_ART
                )
                ?.let { bitmap ->

                    if (
                        isValidArtwork(
                            bitmap,
                            minArtDimension
                        )
                    ) {
                        return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
                    }
                }

            /*
             * 2. ALBUM_ART bitmap directo.
             */
            metadata
                .getBitmap(
                    MediaMetadata.METADATA_KEY_ALBUM_ART
                )
                ?.let { bitmap ->

                    if (
                        isValidArtwork(
                            bitmap,
                            minArtDimension
                        )
                    ) {
                        return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
                    }
                }

            /*
             * 3. DISPLAY_ICON bitmap directo (Refuerzo de Claude).
             */
            metadata
                .getBitmap(
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON
                )
                ?.let { bitmap ->

                    if (
                        isValidArtwork(
                            bitmap,
                            minArtDimension
                        )
                    ) {
                        return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
                    }
                }

            /*
             * 3. NotificationListener como fallback.
             */
            try {

                val mediaNotification =
                    getActiveNotifications()
                        .firstOrNull { sbn ->

                            sbn.packageName ==
                                    controller.packageName &&
                                    sbn.notification.category ==
                                    Notification.CATEGORY_TRANSPORT
                        }

                if (
                    mediaNotification != null
                ) {
                    val notifTitle = mediaNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

                    /*
                     * Verificamos que el título de la notificación coincida con el que buscamos.
                     * Algunas apps (como Metrolist) actualizan el título antes que el icono.
                     */
                    if (notifTitle != null && notifTitle.equals(metadata.getString(MediaMetadata.METADATA_KEY_TITLE), ignoreCase = true)) {
                        /*
                         * LargeIcon.
                         */
                        val largeIconBitmap =
                            mediaNotification
                                .notification
                                .getLargeIcon()
                                ?.loadDrawable(
                                    this@MusicNotificationListener
                                )
                                ?.toBitmap()

                        if (
                            largeIconBitmap != null &&
                            isValidArtwork(
                                largeIconBitmap,
                                minArtDimension
                            )
                        ) {
                            return@withContext ensureMaxDimension(largeIconBitmap, MAX_ART_DIMENSION)
                        }

                        /*
                         * EXTRA_PICTURE.
                         */
                        @Suppress("DEPRECATION")
                        val picture =
                            mediaNotification
                                .notification
                                .extras
                                .getParcelable<Bitmap>(
                                    Notification.EXTRA_PICTURE
                                )

                        if (
                            picture != null &&
                            isValidArtwork(
                                picture,
                                minArtDimension
                            )
                        ) {
                            return@withContext ensureMaxDimension(picture, MAX_ART_DIMENSION)
                        }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Fallo consultando notificación activa",
                    e
                )
            }

            /*
             * 4. ART_URI.
             */
            metadata
                .getString(
                    MediaMetadata.METADATA_KEY_ART_URI
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { uri ->
                    decodeAlbumArtUri(uri)?.let { bitmap ->
                        if (isValidArtwork(bitmap, minArtDimension)) {
                            return@withContext bitmap
                        }
                    }
                }

            /*
             * 5. ALBUM_ART_URI.
             */
            metadata
                .getString(
                    MediaMetadata
                        .METADATA_KEY_ALBUM_ART_URI
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { uri ->
                    decodeAlbumArtUri(uri)?.let { bitmap ->
                        if (isValidArtwork(bitmap, minArtDimension)) {
                            return@withContext bitmap
                        }
                    }
                }

            null
        }

    private fun ensureMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
        
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (ratio > 1f) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }

        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun isValidArtwork(
        bitmap: Bitmap,
        minDimension: Int
    ): Boolean {

        return !bitmap.isRecycled &&
                bitmap.width >= minDimension &&
                bitmap.height >= minDimension
    }

    private fun decodeAlbumArtUri(
        uriString: String
    ): Bitmap? {

        /*
         * Spotify Media API URI.
         *
         * Se transforma a CDN.
         */
        if (
            uriString.startsWith(
                SPOTIFY_MEDIA_API_PREFIX
            )
        ) {

            val hash =
                Uri.decode(
                    uriString
                )
                    .substringAfterLast(":")
                    .substringBefore("?")

            if (
                hash.isNotBlank()
            ) {
                return downloadBitmapFromUrl(
                    "$SPOTIFY_CDN_PREFIX$hash"
                )
            }
        }

        /*
         * URL directa.
         */
        if (
            uriString.startsWith(
                "http://"
            ) ||
            uriString.startsWith(
                "https://"
            )
        ) {

            return downloadBitmapFromUrl(
                uriString
            )
        }

        /*
         * Content URI genérico.
         */
        return try {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al decodificar URI: $uriString", e)
            null
        }
    }

    private fun downloadBitmapFromUrl(
        urlString: String
    ): Bitmap? {

        var connection:
                HttpURLConnection? =
            null

        return try {

            connection =
                URL(urlString)
                    .openConnection()
                        as HttpURLConnection

            connection.connectTimeout =
                NETWORK_CONNECT_TIMEOUT_MS

            connection.readTimeout =
                NETWORK_READ_TIMEOUT_MS

            connection.instanceFollowRedirects =
                true

            connection.doInput =
                true

            connection.useCaches =
                true

            connection.connect()

            if (
                connection.responseCode !in
                200..299
            ) {

                Log.w(
                    TAG,
                    "HTTP ${connection.responseCode} " +
                            "descargando artwork"
                )

                return null
            }

            connection.inputStream.use { input ->
                decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
            }

        } catch (
            e: CancellationException
        ) {

            throw e

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Fallo descargando artwork: " +
                        urlString,
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }

    private fun decodeSampledBitmapFromStream(
        inputStream: java.io.InputStream,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val start = System.currentTimeMillis()
        // No podemos leer el stream dos veces fácilmente, así que lo copiamos a un buffer
        val buffer = inputStream.readBytes()
        
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(buffer, 0, buffer.size, options)

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        
        val bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.size, options)
        val duration = System.currentTimeMillis() - start
        
        if (bitmap != null) {
            Log.d(TAG, "ARTWORK: source=STREAM original=${originalWidth}x${originalHeight} final=${bitmap.width}x${bitmap.height} decode=${duration}ms cache=false")
        }
        
        return bitmap
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getPlaceholderBitmap():
            Bitmap {

        return try {

            val drawable =
                ContextCompat.getDrawable(
                    applicationContext,
                    R.drawable.ic_music_note
                )

            drawable?.toBitmap()
                ?: Bitmap.createBitmap(
                    1,
                    1,
                    Bitmap.Config.ARGB_8888
                )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "No se pudo crear placeholder",
                e
            )

            Bitmap.createBitmap(
                1,
                1,
                Bitmap.Config.ARGB_8888
            )
        }
    }

    private suspend fun saveTextToFile(
        text: String,
        fileName: String
    ) =
        withContext(
            Dispatchers.IO
        ) {

            fileMutex.withLock {

                try {

                    val finalFile =
                        File(
                            filesDir,
                            fileName
                        )

                    val tempFile =
                        File(
                            filesDir,
                            "$fileName.tmp"
                        )

                    tempFile.writeText(text)

                    try {

                        Files.move(
                            tempFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption
                                .REPLACE_EXISTING,
                            StandardCopyOption
                                .ATOMIC_MOVE
                        )

                    } catch (
                        _: Exception
                    ) {

                        Files.move(
                            tempFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption
                                .REPLACE_EXISTING
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Error escribiendo archivo de texto " +
                                fileName,
                        e
                    )
                }
            }
        }

    private suspend fun saveBitmapToFile(
        bitmap: Bitmap,
        fileName: String
    ) =
        withContext(
            Dispatchers.IO
        ) {

            fileMutex.withLock {

                var scaledBitmap:
                        Bitmap? =
                    null

                try {

                    val maxDimension =
                        MAX_ART_DIMENSION

                    val outputBitmap =
                        if (
                            bitmap.width >
                            maxDimension ||
                            bitmap.height >
                            maxDimension
                        ) {

                            val ratio =
                                bitmap.width.toFloat() /
                                        bitmap.height.toFloat()

                            val newWidth: Int
                            val newHeight: Int

                            if (
                                ratio > 1f
                            ) {

                                newWidth =
                                    maxDimension

                                newHeight =
                                    (
                                            maxDimension /
                                                    ratio
                                            )
                                        .toInt()
                                        .coerceAtLeast(
                                            1
                                        )

                            } else {

                                newHeight =
                                    maxDimension

                                newWidth =
                                    (
                                            maxDimension *
                                                    ratio
                                            )
                                        .toInt()
                                        .coerceAtLeast(
                                            1
                                        )
                            }

                            scaledBitmap =
                                Bitmap.createScaledBitmap(
                                    bitmap,
                                    newWidth,
                                    newHeight,
                                    true
                                )

                            scaledBitmap!!

                        } else {

                            bitmap
                        }

                    // Aplicar forma de píldora y rotación si es el artwork principal
                    val processedBitmap = if (fileName == ALBUM_ART_FILE) {
                        val density = applicationContext.resources.displayMetrics.density
                        val pillWidthPx = (110 * density).toInt()
                        ImageUtils.createRotatedPillBitmap(
                            source = outputBitmap,
                            rotationDegrees = -28f,
                            targetWidth = pillWidthPx,
                            heightRatio = 0.9f
                        )
                    } else {
                        null
                    }

                    val bitmapToSave = processedBitmap ?: outputBitmap

                    val finalFile =
                        File(
                            filesDir,
                            fileName
                        )

                    val tempFile =
                        File(
                            filesDir,
                            "$fileName.tmp"
                        )

                    FileOutputStream(
                        tempFile
                    ).use { output ->
                        bitmapToSave.compress(
                            Bitmap.CompressFormat.WEBP_LOSSY,
                            85,
                            output
                        )

                        output.fd.sync()
                    }

                    // Liberar el bitmap procesado una vez guardado
                    if (processedBitmap != null && processedBitmap !== outputBitmap) {
                        processedBitmap.recycle()
                    }

                    try {

                        Files.move(
                            tempFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption
                                .REPLACE_EXISTING,
                            StandardCopyOption
                                .ATOMIC_MOVE
                        )

                    } catch (
                        _: Exception
                    ) {

                        Files.move(
                            tempFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption
                                .REPLACE_EXISTING
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Error escribiendo archivo " +
                                fileName,
                        e
                    )

                } finally {

                    if (
                        scaledBitmap != null &&
                        scaledBitmap !== bitmap &&
                        !scaledBitmap!!
                            .isRecycled
                    ) {

                        scaledBitmap!!
                            .recycle()
                    }
                }
            }
        }

    override fun onDestroy() {

        /*
         * Cancelamos primero la pipeline.
         */
        pendingRefreshJob?.cancel()

        /*
         * Cancelamos las operaciones de artwork.
         *
         * Al estar dentro de serviceScope, la cancelación de
         * serviceJob también las cancelaría, pero lo hacemos
         * explícitamente para dejar el estado claro.
         */
        artworkInFlightMutex.tryLock().let { locked ->

            if (locked) {

                try {

                    artworkInFlight
                        .values
                        .forEach { deferred ->

                            deferred.cancel()
                        }

                    artworkInFlight.clear()

                } finally {

                    artworkInFlightMutex.unlock()
                }
            }
        }

        /*
         * Quitamos listener de sesiones.
         */
        mediaSessionManager
            .removeOnActiveSessionsChangedListener(
                sessionsChangedListener
            )

        /*
         * Desregistramos callbacks.
         */
        controllerCallbacks
            .forEach {
                    (controller, callback) ->

                runCatching {

                    controller
                        .unregisterCallback(
                            callback
                        )
                }
            }

        controllerCallbacks.clear()

        selectedController =
            null

        lastObservedSnapshot =
            null

        lastAppliedSnapshot =
            null

        inFlightSnapshot =
            null

        savedArtworkKey =
            null

        artworkCache.evictAll()
        lyricsUpdateJob?.cancel()

        Log.d(TAG, "[DIAGNOSTIC] SERVICE_LIFECYCLE: onDestroy - Process ending")
        serviceJob.cancel()

        super.onDestroy()
    }

    companion object {

        private const val TAG =
            "MusicListener"

        /*
         * Memoria volátil del último estado para evitar parpadeos de carga en el widget.
         */
        private var lastCommittedInfo: MusicInfo? = null

        fun getLatestMusicInfo(): MusicInfo? = lastCommittedInfo

        private const val ALBUM_ART_FILE =
            "album_art.webp"

        private const val ALBUM_ART_KEY_FILE =
            "album_art.key"

        private const val APP_ICON_FILE =
            "app_icon.webp"

        private const val APP_ICON_KEY_FILE =
            "app_icon.key"

        private const val MIN_ART_DIMENSION =
            100

        private const val MAX_ART_DIMENSION =
            512

        /*
         * Debounce normal.
         */
        private const val NORMAL_DEBOUNCE_MS =
            150L

        /*
         * Las notificaciones de transporte pueden reaccionar
         * ligeramente más rápido.
         */
        private const val FAST_DEBOUNCE_MS =
            100L

        /*
         * Tiempo adicional para que Spotify termine de actualizar
         * todos sus campos de metadata. Se aumenta a 400ms para evitar
         * capturar estados de transición (texto nuevo con portada vieja).
         */
        private const val METADATA_STABILIZATION_MS =
            400L

        private const val NETWORK_CONNECT_TIMEOUT_MS =
            3000

        private const val NETWORK_READ_TIMEOUT_MS =
            3000

        /*
         * Cache de artwork: 8 MB.
         */
        private const val ARTWORK_CACHE_SIZE_KB =
            8 * 1024

        /*
         * Límite de espera para la resolución de artwork.
         */
        private const val ARTWORK_TIMEOUT_MS =
            7000L

        private const val SPOTIFY_MEDIA_API_PREFIX =
            "content://com.spotify.mobile.android.mediaapi"

        private const val SPOTIFY_CDN_PREFIX =
            "https://i.scdn.co/image/"
    }
}