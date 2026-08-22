package arenliel.musicwidget

import android.app.ActivityManager
import android.app.Notification
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import android.widget.RemoteViews
import org.json.JSONArray
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlin.math.max

class MusicNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var musicDataStore: MusicDataStore
    private lateinit var lyricsRepository: LyricsRepository

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
     * ACTIVE WATCHER (v4.3): Job para la persistencia proactiva de portadas.
     * Permite capturar la imagen en el instante en que la pista cruza el umbral de 5s.
     */
    private var eagerCacheJob: Job? = null

    /*
     * GATING FLAG (v4.3.3): Indica que existen actualizaciones postergadas por pantalla apagada.
     */
    private var hasPendingUpdates = false

    /*
     * CACHÉ VOLÁTIL DE PORTADAS (v4.3.1): Almacena rutas de archivos pre-procesados.
     * Evita la redundancia de I/O durante la transición de historial.
     */
    private val eagerArtworkPaths = ConcurrentHashMap<String, String>()

    /*
     * CACHÉ DE MEMORIA INMEDIATA (v4.4): Captura el Bitmap al segundo 0.
     * Desacopla la carátula de los metadatos dinámicos del sistema para evitar Race Conditions.
     */
    private val memoryArtworkCache = ConcurrentHashMap<String, Bitmap>()

    /*
     * BÓVEDA DE ICONOS (v2.3): Persistencia volátil del mejor icono por paquete.
     * Evita el parpadeo visual al cambiar de pista en la misma aplicación.
     */
    private val iconVault = mutableMapOf<String, Pair<Bitmap, Int>>()

    /*
     * Controller seleccionado actualmente.
     */
    private var selectedController: MediaController? = null

    /*
     * Caché de audio para evitar refrescos constantes.
     */
    private var cachedAudioDeviceName: String = "Altavoz del teléfono"
    private var cachedAudioDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            syncPlaybackDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            syncPlaybackDevice()
        }
    }

    private var currentLyrics: LyricsResult? = null
    private var lyricsUpdateJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private var unlockPollingJob: Job? = null

    /*
     * Flow para procesar eventos de Seek con compensación de latencia.
     */
    private val seekEventFlow = MutableSharedFlow<Triple<MediaSnapshot, Long, Long>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /*
     * Eventos de actualización para el despacho adaptativo (v2.0).
     */
    private sealed class UpdateEvent {
        data class IdentityChange(val trackKey: String) : UpdateEvent()
        object StatusUpdate : UpdateEvent()
    }

    /*
     * Flow para centralizar y consolidar actualizaciones de la interfaz (Atómico).
     */
    private val uiUpdateFlow = MutableSharedFlow<UpdateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /*
     * Snapshot más reciente observado.
     *
     * Sirve para evitar procesar repetidamente la misma metadata
     * mientras Spotify está enviando varios callbacks consecutivos.
     */
    private var lastObservedSnapshot: MediaSnapshot? = null

    /*
     * Snapshot cuya actualización terminó correctamente (Estado Visual).
     */
    private var lastAppliedSnapshot: MediaSnapshot? = null

    /*
     * Snapshot más reciente procesado por la lógica de negocio (Estado Lógico).
     * Permite que el historial y la deduplicación funcionen con la pantalla apagada.
     */
    private var lastLogicalSnapshot: MediaSnapshot? = null

    /*
     * Snapshot actualmente en proceso.
     */
    private var inFlightSnapshot: MediaSnapshot? = null

    /*
     * Control de visibilidad para Screen-Gated Rendering.
     */
    @Volatile
    private var isPresentationDirty: Boolean = false
    private var pendingSnapshot: MediaSnapshot? = null

    /*
     * DEDUPLICADOR DE EVENTOS (Capa de Negocio)
     * Evita que ráfagas de callbacks procesen la misma canción y resultado varias veces.
     */
    private var lastProcessedTrack: String? = null
    private var lastProcessedOutcome: String? = null

    private sealed class HistoryEvent {
        data class TrackStarted(val snapshot: MediaSnapshot, val bufferPath: String?) : HistoryEvent()
        data class TrackEnded(val trackKey: String, val finalSnapshot: MediaSnapshot) : HistoryEvent()
    }

    private val historyChannel = Channel<HistoryEvent>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

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
    private var currentIconTier: Int = TIER_NONE

    /*
     * Receptor dinámico para estados de pantalla.
     */
    private val dynamicScreenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    onDisplayBecameUnavailable()
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    // Cancelar cualquier sondeo previo para evitar duplicados
                    unlockPollingJob?.cancel()

                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager

                    if (isWidgetPotentiallyVisible()) {
                        onDisplayFullyVisible()
                    } else if (km.isKeyguardLocked) {
                        // Arrancar sondeo acotado si está bloqueado (recuperación proactiva)
                        unlockPollingJob = serviceScope.launch {
                            try {
                                repeat(15) {
                                    if (!km.isKeyguardLocked) {
                                        onDisplayFullyVisible()
                                        return@launch
                                    }
                                    delay(1000)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isWidgetPotentiallyVisible(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun onDisplayBecameUnavailable() {
        Log.d(TAG, "[GATING] Display unavailable. Closing gate.")
        InternalLogger.log(applicationContext, "GATING: Pantalla apagada. Compuerta CERRADA.")
        lyricsUpdateJob?.cancel()
        unlockPollingJob?.cancel()
    }

    private fun onDisplayFullyVisible() {
        Log.d(TAG, "[GATING] Display fully visible. Triggering Wake-up Sync.")
        InternalLogger.log(applicationContext, "GATING: Desbloqueo detectado. Forzando reprocesamiento de sesión.")
        
        if (hasPendingUpdates) {
            Log.d(TAG, "[GATING] Aplicando actualizaciones postergadas a Glance.")
            hasPendingUpdates = false
            serviceScope.launch {
                MusicWidget.updateAll(applicationContext)
            }
        }

        // Sincronización de recuperación:
        // Forzamos al proceso a descargar recursos que se omitieron durante el bloqueo.
        serviceScope.launch {
            refreshBestSession(reason = "catch_up_render")
            // PASO 4: Iniciar reconciliación del historial pendiente
            reconcilePendingHistoryArtworks()
        }
    }

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

    private sealed class ArtworkSource {
        data class Bitmap(val bitmap: android.graphics.Bitmap) : ArtworkSource()
        data class Uri(val uri: String) : ArtworkSource()
        data object Placeholder : ArtworkSource()
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
        val playbackDeviceName: String,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val recordedAt: Long = System.currentTimeMillis(),
        val artworkSource: ArtworkSource = ArtworkSource.Placeholder,
        val firstObservedAt: Long = recordedAt,
        val maxPositionMs: Long = positionMs,
        val observedAtRealtime: Long = SystemClock.elapsedRealtime(),
        val playbackDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    ) {
        /*
         * Identidad de sesión (Inmune a refinamientos de álbum/duración).
         */
        val sessionIdentity: String
            get() = "$packageName|$title|$artist"

        /*
         * Identidad lógica de la pista (Hash robusto).
         */
        val trackKey: String
            get() = buildString {
                append(title)
                append('|')
                append(artist)
                append('|')
                append(durationMs)
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
                    ?: trackKey

        /*
         * Identidad completa del snapshot.
         * Incluye la posición redondeada para detectar Seeks significativos.
         */
        val contentKey: String
            get() = "$trackKey|$artworkKey|$playbackState|${positionMs / 1000}"
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

        lyricsRepository = LyricsRepository(applicationContext)

        cleanupHistoryBuffer()
        restoreIdempotencyShield()
        startHistoryWorker()
        startSeekEventProcessor()
        startUiUpdateDispatcher()
        startBlacklistObserver()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        syncPlaybackDevice()
    }

    private fun syncPlaybackDevice() {
        val (name, type) = getPlaybackDeviceInfo(applicationContext)
        if (cachedAudioDeviceName != name || cachedAudioDeviceType != type) {
            cachedAudioDeviceName = name
            cachedAudioDeviceType = type
            
            // Si hay una sesión activa, actualizamos quirúrgicamente a través del DataStore
            serviceScope.launch {
                musicDataStore.updatePlaybackDevice(name, type)
                
                // SYNC RAM (v4.0): Relevo Atómico de Dispositivo
            val current = MusicStateProvider.current()
            val changed = MusicStateProvider.applyEvent(MusicUpdateEvent.StatusUpdate(
                isPlaying = current.isPlaying,
                deviceName = name,
                deviceType = type
            ))
            if (changed) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startUiUpdateDispatcher() {
        serviceScope.launch {
            uiUpdateFlow
                .debounce { event ->
                    when (event) {
                        is UpdateEvent.IdentityChange -> 50L
                        is UpdateEvent.StatusUpdate -> 150L
                    }
                }
                .collect { event ->
                    // REGLA DE ORO (v2.9): Hiato total en reposo para ahorro de batería (Batería Cero)
                    if (!isWidgetPotentiallyVisible()) {
                        Log.d(TAG, "[DIAGNOSTIC] UI_DISPATCHER: Widget no visible. Postponing update.")
                        hasPendingUpdates = true
                        // Hallazgo v3.5: Cancelación física del Ticker en reposo
                        lyricsUpdateJob?.cancel()
                        return@collect
                    }

                    // Hallazgo v3.5: Recuperación proactiva del Ticker al despertar (ACTION_USER_PRESENT indirecto)
                    if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
                        relaunchLyricsTicker("screen_wake")
                    }

                    Log.d(TAG, "[DIAGNOSTIC] UI_DISPATCHER: Ejecutando actualización atómica de Glance (Event=$event)")
                    runCatching {
                        MusicWidget.updateAll(applicationContext)
                    }.onFailure { e ->
                        Log.w(TAG, "Fallo al actualizar Glance (Posible desincronización de widget info)", e)
                    }
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSeekEventProcessor() {
        serviceScope.launch {
            seekEventFlow
                .debounce(400L)
                .collect { (snapshot, position, detectedAt) ->
                    val now = SystemClock.elapsedRealtime()
                    val processingLag = now - detectedAt
                    Log.d(TAG, "[LYRICS_TRACE] Aplicando Seek (Lag compensado: ${processingLag}ms): ${position + processingLag}ms")
                    
                    val updatedSnapshot = snapshot.copy(
                        positionMs = position + processingLag,
                        observedAtRealtime = now
                    )
                    lastLogicalSnapshot = updatedSnapshot
                    relaunchLyricsTicker("seek_event")
                }
        }
    }

    private fun restoreIdempotencyShield() {
        serviceScope.launch {
            val history = musicDataStore.musicInfoFlow.first().history
            if (history.isNotEmpty()) {
                val last = history.first()
                lastProcessedTrack = last.trackKey
                lastProcessedOutcome = if (last.isSkipped) "SKIPPED" else "COMPLETED"
                Log.d(TAG, "[SHADOW_OBSERVER] Escudo de idempotencia restaurado: ${last.title}")
            }
        }
    }

    private fun startBlacklistObserver() {
        serviceScope.launch {
            musicDataStore.musicInfoFlow.collect { info ->
                val currentPkg = lastObservedSnapshot?.packageName
                if (currentPkg != null && info.blacklist.contains(currentPkg)) {
                    Log.d(TAG, "[BLACKLIST_PURGE] App actual $currentPkg ha sido añadida a la lista negra. Purgando.")
                    
                    // 1. Limpieza en Disco
                    musicDataStore.clearActiveSession()
                    
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
                    lastObservedSnapshot = null
                    inFlightSnapshot = null
                    savedArtworkKey = null
                    savedAppIconKey = null
                    
                    // 3. Sincronía Atómica: El observador startRamMirror actualizará la memoria (Fast-Track)
                    
                    // 4. Forzar actualización de Glance
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    
                    // 5. Intento de relevo
                    refreshBestSession(reason = "blacklist_handover")
                }
            }
        }
    }

    private fun startHistoryWorker() {
        serviceScope.launch(Dispatchers.IO) {
            var currentTrackingIdentity: String? = null
            var currentTrackingStartedAt = 0L
            var currentTrackingSnapshot: MediaSnapshot? = null

            for (event in historyChannel) {
                when (event) {
                    is HistoryEvent.TrackStarted -> {
                        // REGLA 1: TrackingIdentity (Título + Artista)
                        val identity = "${event.snapshot.title}|${event.snapshot.artist}"

                        if (identity == currentTrackingIdentity && currentTrackingSnapshot != null) {
                            // REGLA 2: Inmutabilidad del Tiempo (Refinamientos)
                            // Actualizamos el snapshot para capturar metadatos refinados (duración, etc)
                            // sin reiniciar el cronómetro de 5 segundos.
                            currentTrackingSnapshot = event.snapshot
                            Log.d(TAG, "[SHADOW_OBSERVER] Refinamiento detectado para: $identity. Manteniendo reloj.")
                        } else {
                            // Nueva pista o Bucle (si el anterior se cerró)
                            currentTrackingIdentity = identity
                            currentTrackingStartedAt = System.currentTimeMillis()
                            currentTrackingSnapshot = event.snapshot
                            Log.d(TAG, "[SHADOW_OBSERVER] Iniciando rastreo para: $identity")
                        }
                    }
                    is HistoryEvent.TrackEnded -> {
                        val identity = "${event.finalSnapshot.title}|${event.finalSnapshot.artist}"
                        
                        if (identity == currentTrackingIdentity && currentTrackingSnapshot != null) {
                            val durationObserved = System.currentTimeMillis() - currentTrackingStartedAt
                            val finalPos = calculateEffectiveProgress(event.finalSnapshot)
                            
                            if (durationObserved >= 5000L || finalPos >= 5000L) {
                                // Consolidación usando Snapshot refinado
                                commitToHistory(currentTrackingSnapshot, event.finalSnapshot)
                            } else {
                                Log.d(TAG, "[SHADOW_OBSERVER] Pista descartada (Umbral no met: ${durationObserved}ms)")
                                cleanupBuffer(event.trackKey)
                            }
                            
                            // REGLA 3: Cierre Explícito (Permite detectar bucles/loops)
                            currentTrackingIdentity = null
                            currentTrackingSnapshot = null
                        }
                    }
                }
            }
        }
    }

    private fun saveBitmapToBuffer(bitmap: Bitmap, trackKey: String): String? {
        val bufferDir = File(filesDir, "history/buffer")
        if (!bufferDir.exists()) bufferDir.mkdirs()
        
        val fileName = "buf_${trackKey.hashCode()}.webp"
        val file = File(bufferDir, fileName)
        
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(format, 80, out)
                out.flush()
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history buffer", e)
            return null
        }
    }

    private fun cleanupBuffer(trackKey: String) {
        val bufferDir = File(filesDir, "history/buffer")
        val fileName = "buf_${trackKey.hashCode()}.webp"
        val file = File(bufferDir, fileName)
        if (file.exists()) file.delete()
    }

    private fun cleanupHistoryBuffer() {
        val bufferDir = File(filesDir, "history/buffer")
        if (bufferDir.exists()) {
            bufferDir.listFiles()?.forEach { it.delete() }
        }
    }

    private suspend fun commitToHistory(startSnapshot: MediaSnapshot, endSnapshot: MediaSnapshot) {
        try {
            val historyDir = File(filesDir, "history")
            if (!historyDir.exists()) historyDir.mkdirs()

            // REGLA 1: VaultKey (T+A+D Final) para nombrar el archivo
            val trackKey = "${endSnapshot.title}|${endSnapshot.artist}|${endSnapshot.durationMs}"
            
            val bufferDir = File(filesDir, "history/buffer")
            val bufferFile = File(bufferDir, "buf_${startSnapshot.trackKey.hashCode()}.webp")
            
            val artworkFile = File(historyDir, "art_${trackKey.hashCode()}.webp")
            
            if (bufferFile.exists()) {
                // PHASE C: Consolidación (Shadow Observer)
                withContext(Dispatchers.IO) {
                    Files.move(bufferFile.toPath(), artworkFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                Log.d(TAG, "[SHADOW_OBSERVER] Portada consolidada para: $trackKey")
            } else {
                // Fallback: Si no hay buffer, intentamos persistir desde memoria (Fase A)
                val memoryBitmap = memoryArtworkCache[trackKey]
                if (memoryBitmap != null && !artworkFile.exists()) {
                    val density = applicationContext.resources.displayMetrics.density
                    val w = (80 * density).toInt()
                    val h = (40 * density).toInt()
                    val historyPill = ImageUtils.createHorizontalPill(memoryBitmap, w, h)
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, historyPill, trackKey)
                    historyPill.recycle()
                }
            }

            val hasArtwork = artworkFile.exists()
            val finalPos = calculateEffectiveProgress(endSnapshot)
            val progressFactor = if (startSnapshot.durationMs > 0) {
                finalPos.toFloat() / startSnapshot.durationMs.toFloat()
            } else 1.0f

            var isSkipped = progressFactor < 0.4f
            val currentRAM = MusicStateProvider.current()
            val isBlessed = currentRAM.history.any { it.trackKey == trackKey && !it.isSkipped }
            if (isBlessed && isSkipped) isSkipped = false

            val isPartial = !isSkipped && progressFactor < 0.85f

            val outcome = when {
                isSkipped -> "SKIPPED"
                isPartial -> "PARTIAL"
                else -> "COMPLETED"
            }

            if (trackKey == lastProcessedTrack && outcome == lastProcessedOutcome) return
            lastProcessedTrack = trackKey
            lastProcessedOutcome = outcome

            val newStreak = musicDataStore.updateSkipStreak(startSnapshot.title, startSnapshot.artist, isSkipped, isPartial)
            val repeatAnalytics = musicDataStore.updateRepeatStats(startSnapshot.title, startSnapshot.artist, isSkipped)
            if (!isSkipped && !isPartial) musicDataStore.updateArtistStats(startSnapshot.artist)

            val historyItem = HistoryItem(
                title = endSnapshot.title,
                artist = endSnapshot.artist,
                packageName = endSnapshot.packageName,
                artworkPath = artworkFile.absolutePath,
                artworkKey = endSnapshot.artworkKey,
                trackKey = trackKey,
                timestamp = System.currentTimeMillis(),
                isSkipped = isSkipped,
                skipStreak = newStreak,
                playsToday = repeatAnalytics.first,
                streakDays = repeatAnalytics.second,
                artworkUri = if (hasArtwork) Uri.fromFile(artworkFile).toString() else (endSnapshot.artworkUri ?: ""),
                hasPendingArtwork = !hasArtwork
            )
            
            musicDataStore.addToHistory(historyItem)
            cleanupHistoryFiles()

        } catch (e: Exception) {
            Log.e(TAG, "Error en commitToHistory para ${startSnapshot.title}", e)
        }
    }


    private suspend fun cleanupHistoryFiles() {
        try {
            val historyDir = File(filesDir, "history")
            if (!historyDir.exists()) return

            val currentHistory = musicDataStore.musicInfoFlow.first().history
            val validFiles = currentHistory.map { File(it.artworkPath).name }.toSet()

            historyDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("art_") && !validFiles.contains(file.name)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando archivos de historial", e)
        }
    }

    /**
     * Reconciliador de Catch-Up Histórico (PASO 3).
     * Sanea portadas pendientes cuando el widget vuelve a ser visible.
     */
    /**
     * EAGER CACHING (v4.4): Persistencia proactiva de la portada.
     * FASE B: Evaluación de Permanencia (Segundo 5).
     */
    private suspend fun persistHistoryArtworkEagerly(snapshot: MediaSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                val trackKey = snapshot.trackKey
                val historyDir = File(filesDir, "history")
                val artworkFile = File(historyDir, "art_${trackKey.hashCode()}.webp")
                
                // Si ya existe en disco, registramos en caché de rutas y salimos
                if (artworkFile.exists()) {
                    eagerArtworkPaths[trackKey] = artworkFile.absolutePath
                    return@withContext
                }

                // Intentamos obtener el Bitmap de la caché de memoria (Fase A)
                val bitmap = memoryArtworkCache[trackKey] ?: when (val source = snapshot.artworkSource) {
                    is ArtworkSource.Bitmap -> source.bitmap
                    is ArtworkSource.Uri -> if (isWidgetPotentiallyVisible()) decodeAlbumArtUri(source.uri) else null
                    else -> null
                }

                if (bitmap != null) {
                    val density = applicationContext.resources.displayMetrics.density
                    val w = (80 * density).toInt()
                    val h = (40 * density).toInt()
                    val historyPill = ImageUtils.createHorizontalPill(bitmap, w, h)

                    try {
                        val finalPath = ArtworkStorageManager.saveHistoryArtwork(
                            applicationContext,
                            historyPill,
                            trackKey
                        )
                        eagerArtworkPaths[trackKey] = finalPath

                        // REACTIVE UPDATE: Manejo de condición de carrera (Skip tardío)
                        val currentHistory = MusicStateProvider.current().history
                        val pendingItem = currentHistory.find { 
                            it.trackKey == trackKey && it.hasPendingArtwork 
                        }

                        if (pendingItem != null) {
                            musicDataStore.updateHistoryItemArtworkStatus(
                                trackKey, 
                                pendingItem.timestamp, 
                                false
                            )
                            
                            // Fase D: Screen-Gated Rendering
                            if (isWidgetPotentiallyVisible()) {
                                Log.d("GLANCE_REFRESH", "Archivo guardado para $trackKey. Solicitando updateAll a Glance.")
                                MusicWidget.updateAll(applicationContext)
                            } else {
                                Log.d(TAG, "[GATING] Portada resuelta con pantalla apagada. Postergando refresco.")
                                hasPendingUpdates = true
                            }
                            Log.d(TAG, "[REACTIVE_FIX] Sincronización tardía completada para: ${snapshot.title}")
                        }
                    } finally {
                        historyPill.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en persistencia proactiva", e)
            }
        }
    }

    private suspend fun reconcilePendingHistoryArtworks() {
        // Obtenemos el historial actual de la RAM
        val history = MusicStateProvider.current().history
        val pending = history.filter { it.hasPendingArtwork }
        if (pending.isEmpty()) return

        Log.d(TAG, "[CATCH-UP] Iniciando reconciliación de ${pending.size} portadas.")

        pending.forEach { item ->
            val historyDir = File(filesDir, "history")
            val artworkFile = File(historyDir, "art_${item.trackKey.hashCode()}.webp")
            val tempFile = File(historyDir, "art_${item.trackKey.hashCode()}.tmp")
            
            // Intentar resolución usando URI almacenada
            val bitmap = if (item.artworkUri.isNotBlank()) {
                decodeAlbumArtUri(item.artworkUri)
            } else {
                // Fallback: Si no hay URI, intentamos buscar por metadatos (limitado)
                null
            }
            
            if (bitmap != null) {
                val density = applicationContext.resources.displayMetrics.density
                val w = (80 * density).toInt()
                val h = (40 * density).toInt()
                val historyPill = ImageUtils.createHorizontalPill(bitmap, w, h)

                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }

                try {
                    FileOutputStream(tempFile).use { out ->
                        if (historyPill.compress(format, 80, out)) {
                            out.flush()
                            if (tempFile.renameTo(artworkFile)) {
                                // Actualizar estatus en DataStore (Atomic Commit)
                                musicDataStore.updateHistoryItemArtworkStatus(item.trackKey, item.timestamp, false)
                                Log.d("GLANCE_REFRESH", "Portada reconciliada para ${item.trackKey}. Solicitando updateAll a Glance. Estado -> FILE_READY")
                                MusicWidget.updateAll(applicationContext)
                                Log.d(TAG, "[CATCH-UP] Portada resuelta para: ${item.title}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error guardando artwork reconciliado de forma atómica", e)
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                    historyPill.recycle()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "[DIAGNOSTIC] PERMISSION_SYNC: Listener connected. Refreshing widget.")
        
        uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)

        // Registro Dinámico del Receiver de Pantalla (API 33+ Compatible)
        val screenFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, dynamicScreenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        /*
         * Al conectar, sincronizamos el estado del artwork guardado y reconstruimos punteros.
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

            // REHIDRATACIÓN: Cargar el estado lógico desde el DataStore (El "Diario de la Verdad")
            val currentInfo = musicDataStore.musicInfoFlow.first()
            if (currentInfo.trackKey.isNotEmpty()) {
                val recoveredSnapshot = MediaSnapshot(
                    packageName = currentInfo.packageName,
                    title = currentInfo.title,
                    artist = currentInfo.artist,
                    album = "",
                    mediaId = "",
                    artworkUri = currentInfo.artworkUri,
                    playbackState = if (currentInfo.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    isSessionActive = currentInfo.isSessionActive,
                    playbackDeviceName = currentInfo.playbackDeviceName,
                    durationMs = currentInfo.durationMs,
                    observedAtRealtime = SystemClock.elapsedRealtime()
                )
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                lastCommittedInfo = currentInfo
                
                // INIT RAM (v2.8): Sincronizamos la memoria con el disco al arrancar
                serviceScope.launch {
                    MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(currentInfo))
                }
                
                Log.d(TAG, "[DIAGNOSTIC] Punteros de estado y RAM rehidratados desde DataStore.")
            }

            // VERIFICACIÓN DE ESTADO INICIAL: Refrescar si hay discrepancia inmediata
            refreshBestSession(reason = "listener_reconnected")
            
            // Sincronizamos la marca de tiempo para evitar rate-limit inmediato
            lastPreviewUpdate = System.currentTimeMillis()
            
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val manager = GlanceAppWidgetManager(applicationContext)
                    manager.setWidgetPreviews(MusicWidgetFullReceiver::class)
                    manager.setWidgetPreviews(MusicWidgetPillReceiver::class)
                    manager.setWidgetPreviews(MusicWidgetControlReceiver::class)
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

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        val notification =
            sbn.notification

        // Lógica de Ascenso de Icono dirigida por eventos
        serviceScope.launch {
            val lastSnapshot = lastAppliedSnapshot
            if (lastSnapshot != null && sbn.packageName == lastSnapshot.packageName && 
                currentIconTier < TIER_NOTIFICATION && 
                notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                
                val density = applicationContext.resources.displayMetrics.density
                val targetSizePx = (14 * density).toInt()
                
                val iconFromNotif = notification.smallIcon?.loadDrawable(this@MusicNotificationListener)?.toBitmap()
                if (iconFromNotif != null) {
                    val normalized = Bitmap.createScaledBitmap(iconFromNotif, targetSizePx, targetSizePx, true)
                    
                    commitMutex.withLock {
                        // Re-verificar tier dentro del lock para evitar carreras
                        if (currentIconTier < TIER_NOTIFICATION) {
                            saveBitmapToFile(normalized, APP_ICON_FILE)
                            val iconKey = "${sbn.packageName}_stable"
                            saveTextToFile(iconKey, APP_ICON_KEY_FILE)
                            savedAppIconKey = iconKey
                            currentIconTier = TIER_NOTIFICATION
                            
                            // Actualización atómica del DataStore para reflejar el cambio en la UI
                            val currentInfo = musicDataStore.musicInfoFlow.first()
                            if (currentInfo.packageName == sbn.packageName) {
                                val updated = currentInfo.copy(appIconKey = iconKey)
                                musicDataStore.saveMusicInfo(updated)
                                
                                // SYNC RAM (v4.0): Relevo Atómico de Icono (Identity Guard)
                                val changed = MusicStateProvider.applyEvent(MusicUpdateEvent.ArtworkResolved(
                                    trackKey = currentInfo.trackKey,
                                    artworkKey = currentInfo.artworkKey,
                                    iconKey = iconKey
                                ))
                                if (changed) {
                                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                                }
                            }
                            Log.d(TAG, "[DIAGNOSTIC] ICON_ASCENT: Icono ascendido a TIER_NOTIFICATION para ${sbn.packageName}")
                        }
                    }
                }
            }
        }

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
                        // REGLA 3: Intercepción del Seek (Optimizado con Debounce y Reloj Monotónico)
                        if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                            val lastSnapshot = lastAppliedSnapshot
                            if (lastSnapshot != null && lastSnapshot.packageName == controller.packageName) {
                                val elapsed = SystemClock.elapsedRealtime() - lastSnapshot.observedAtRealtime
                                val expectedPos = lastSnapshot.positionMs + (if (lastSnapshot.playbackState == PlaybackState.STATE_PLAYING) elapsed else 0L)
                                val actualPos = state.position
                                
                                // Detectar cualquier salto significativo (> 800ms) solo en reproducción
                                if (Math.abs(expectedPos - actualPos) > 800) {
                                    val now = SystemClock.elapsedRealtime()
                                    Log.d(TAG, "[LYRICS_TRACE] Seek detectado (${actualPos}ms). Agrupando ráfaga...")
                                    seekEventFlow.tryEmit(Triple(lastSnapshot, actualPos, now))
                                }
                            }
                        }

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
                        
                        // TRIGGER DE CIERRE (v4.3)
                        lastLogicalSnapshot?.let { last ->
                            if (last.packageName == controller.packageName) {
                                historyChannel.trySend(HistoryEvent.TrackEnded(last.trackKey, last))
                            }
                        }
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
            lastLogicalSnapshot?.let { last ->
                serviceScope.launch {
                    // TRIGGER DE CIERRE (v4.3)
                    historyChannel.trySend(HistoryEvent.TrackEnded(last.trackKey, last))
                    
                    val finalPos = calculateEffectiveProgress(last)
                    musicDataStore.clearActiveSession()
                    
                    // Notificamos a la RAM el fin de sesión atómico
                    if (MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))) {
                        uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    }
                }
            }
            selectedController = null
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

        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            lastObservedSnapshot
                ?.contentKey
        ) {

            return
        }

        if (
            reason != "catch_up_render" &&
            snapshot.contentKey ==
            inFlightSnapshot
                ?.contentKey
        ) {

            return
        }

        if (
            reason != "catch_up_render" &&
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
            rawSnapshot =
                snapshot,
            reason =
                reason
        )
    }

    private fun selectBestController(
        activeSessions:
        List<MediaController>
    ): MediaController? {

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
        
        // REGLA: Usamos el caché para evitar sondeos en cada track change
        val deviceName = cachedAudioDeviceName
        val deviceType = cachedAudioDeviceType

        val trackKeyStr = "$title|$artist|$duration"

        // FASE A: Captura Inmediata (Segundo 0)
        // Intentamos extraer y clonar el bitmap del sistema mientras está fresco
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { original ->
            if (!memoryArtworkCache.containsKey(trackKeyStr)) {
                runCatching {
                    val clone = original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
                    memoryArtworkCache[trackKeyStr] = clone
                    Log.d(TAG, "[ART_LIFECYCLE] Fase A: Bitmap clonado en RAM para $title")
                }
            }
        }

        return MediaSnapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            mediaId = mediaId,
            artworkUri = artworkUri,
            playbackState = playbackState,
            isSessionActive = true,
            playbackDeviceName = deviceName,
            playbackDeviceType = deviceType,
            durationMs = duration,
            positionMs = position,
            recordedAt = System.currentTimeMillis(),
            artworkSource = ArtworkSource.Placeholder,
            maxPositionMs = position,
            observedAtRealtime = SystemClock.elapsedRealtime()
        )
    }

    private fun calculateEffectiveProgress(snapshot: MediaSnapshot): Long {
        if (snapshot.playbackState != PlaybackState.STATE_PLAYING) {
            return snapshot.maxPositionMs
        }
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceObservation = now - snapshot.observedAtRealtime
        val estimatedPos = snapshot.positionMs + elapsedSinceObservation
        
        // Hallazgo v3.8: Watermark (maxPositionMs) para evitar retrocesos accidentales
        val progress = Math.max(snapshot.maxPositionMs, estimatedPos)

        return if (snapshot.durationMs > 0) {
            Math.min(progress, snapshot.durationMs)
        } else {
            progress
        }
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        
        if (pkg == lastObservedSnapshot?.packageName) {
            Log.d(TAG, "[REACTIVE] Notificación removida para $pkg. Sincronizando sesión.")
            serviceScope.launch {
                refreshBestSession(reason = "notification_removed")
            }
        }
    }

    private fun getPlaybackDeviceInfo(context: Context): Pair<String, Int> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // 1. Prioridad: Bluetooth (A2DP, LE, SCO)
        val bluetooth = devices.find { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (bluetooth != null) {
            val name = bluetooth.productName?.toString()
            return (name?.takeIf { it.isNotBlank() } ?: "Bluetooth") to bluetooth.type
        }

        // 2. Prioridad: Auriculares con cable o USB
        val wired = devices.find { 
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || 
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (wired != null) return "Auriculares" to wired.type

        // 3. Prioridad: Salidas externas (HDMI, TV, Dock)
        val external = devices.find {
            it.type == AudioDeviceInfo.TYPE_HDMI ||
            it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            it.type == AudioDeviceInfo.TYPE_DOCK
        }
        if (external != null) return "Salida externa" to external.type

        // 4. Fallback: Altavoz del teléfono
        return "Altavoz del teléfono" to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun isAppAllowed(packageName: String): Boolean {
        // 0. Apps prohibidas explícitamente (Blacklist interna)
        val restrictedPackages = setOf(
            "org.kde.kdeconnect", "com.google.android.projection.gearhead", 
            "com.android.systemui", "com.google.android.apps.maps"
        )
        if (restrictedPackages.contains(packageName)) return false

        // 1. Apps conocidas que siempre permitimos (Fallback robusto)
        val commonMusicPackages = setOf(
            "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android",
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.deezer.android",
            "com.tidal.android", "com.pandora.android", "com.musicolet", "com.hiby.music"
        )
        if (commonMusicPackages.contains(packageName)) return true

        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            
            // 2. Por categoría de sistema (Android 8.0+)
            val isMediaCategory = appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
                    appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
            if (isMediaCategory) return true

            // 3. Por servicios multimedia declarados
            val mediaIntent = android.content.Intent("android.media.browse.MediaBrowserService")
            val mediaApps = pm.queryIntentServices(mediaIntent, 0).map { it.serviceInfo.packageName }
            if (mediaApps.contains(packageName)) return true

            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processSnapshot(
        controller: MediaController?,
        metadata: MediaMetadata?,
        rawSnapshot: MediaSnapshot,
        reason: String
    ) {
        // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---

        /*
         * Creamos una nueva generación de forma atómica.
         */
        val myGeneration =
            generation.incrementAndGet()

        // REGLA: Usamos lastLogicalSnapshot para la deduplicación de negocio
        // Esto permite que el historial detecte cambios aunque la pantalla esté apagada.
        val previousLogical =
            lastLogicalSnapshot

        val sessionChanged = previousLogical?.sessionIdentity != rawSnapshot.sessionIdentity
        val trackContentChanged = previousLogical?.trackKey != rawSnapshot.trackKey

        // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
        // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
        val isCatchUp = reason == "catch_up_render"
        
        // Detección de Incoherencia de Imagen: Si la portada en disco no coincide con la del snapshot, forzamos bypass
        val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()

        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        val currentMem = MusicStateProvider.current()
        if (!isCatchUp && !trackContentChanged && !artIncoherent && 
            currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
            currentMem.isSessionActive == rawSnapshot.isSessionActive) {
            
            // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
            lastObservedSnapshot = rawSnapshot
            return
        }

        if (isCatchUp || artIncoherent) {
            InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
        }

        val appChanged = previousLogical?.packageName != rawSnapshot.packageName
        
        if (appChanged) {
            currentIconTier = TIER_NONE
            // REGLA: Limpieza de Iconos (Icon Fix) ante cambios de app
            saveTextToFile("", APP_ICON_KEY_FILE)
            savedAppIconKey = null
            
            // Hallazgo v3.4: Limpieza preventiva de RAM en transición
            serviceScope.launch {
                MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(0L)) // Simulamos fin de sesión
            }
        }

        // REGLA DE PROMOCIÓN DE SESIÓN (Persistent Snapshot)
        if (sessionChanged && previousLogical != null && rawSnapshot.packageName != previousLogical.packageName) {
            val isNewSessionWeak = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING
            if (isNewSessionWeak) {
                Log.d(TAG, "[DIAGNOSTIC] IGNORED: Ignorando sesión débil de ${rawSnapshot.packageName}")
                return
            }
        }

        // FILTRO DE IDENTIDAD (Allow-list)
        if (!isAppAllowed(rawSnapshot.packageName)) return

        val currentBlacklist = musicDataStore.musicInfoFlow.first().blacklist
        if (currentBlacklist.contains(rawSnapshot.packageName)) return

        val isMetadataRefinement = previousLogical != null &&
                previousLogical.title == rawSnapshot.title &&
                previousLogical.artist == rawSnapshot.artist &&
                previousLogical.packageName == rawSnapshot.packageName

        val isSameSession = previousLogical?.sessionIdentity == rawSnapshot.sessionIdentity
        
        val firstObservedAt = if (isSameSession) {
            previousLogical?.firstObservedAt ?: rawSnapshot.recordedAt
        } else {
            rawSnapshot.recordedAt
        }

        val snapshot = rawSnapshot.copy(
            firstObservedAt = firstObservedAt,
            // Hallazgo v4.2: Propagación de Watermark y Assets en el Relay
            maxPositionMs = Math.max(previousLogical?.maxPositionMs ?: 0L, rawSnapshot.positionMs),
            artworkSource = rawSnapshot.artworkSource
        )

        // --- STAGE 0: GESTIÓN DE HISTORIAL (AISLAMIENTO v4.3) ---
        if (sessionChanged && !isMetadataRefinement) {
            // Notificar fin de la pista anterior
            previousLogical?.let { prev ->
                historyChannel.trySend(HistoryEvent.TrackEnded(prev.trackKey, prev))
            }
            
            // Captura inmediata y notificación de inicio de nueva pista (Phase A)
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bitmap != null) {
                serviceScope.launch(Dispatchers.IO) {
                    val bufferPath = saveBitmapToBuffer(bitmap, rawSnapshot.trackKey)
                    historyChannel.trySend(HistoryEvent.TrackStarted(rawSnapshot, bufferPath))
                }
            } else {
                historyChannel.trySend(HistoryEvent.TrackStarted(rawSnapshot, null))
            }
        }

        // ACTUALIZACIÓN DEL DIARIO LÓGICO
        lastLogicalSnapshot = snapshot

        // ACTIVE WATCHER (v4.3.1): Cronómetro proactivo de 5s con LATE-READ
        if (snapshot.playbackState == PlaybackState.STATE_PLAYING && (trackContentChanged || eagerCacheJob == null)) {
            eagerCacheJob?.cancel()
            eagerCacheJob = serviceScope.launch {
                delay(5000L)
                // Obtenemos el estado refinado (con portada cargada) tras la espera
                val refinedSnapshot = lastLogicalSnapshot ?: return@launch
                persistHistoryArtworkEagerly(refinedSnapshot)
            }
        } else if (snapshot.playbackState != PlaybackState.STATE_PLAYING) {
            // Cancelación inmediata en pausa/stop para ahorro de recursos
            eagerCacheJob?.cancel()
            eagerCacheJob = null
        }

        // FAST-TRACK SSOT (v4.0 - Relevo Atómico de RAM)
        serviceScope.launch {
            val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
            val currentInfo = musicDataStore.musicInfoFlow.first()
            
            // Hallazgo v3.7: Inmunidad de Salida.
            val (plays, skip, freq) = when {
                sessionChanged -> Triple(0, 0, false)
                !isPlaying -> Triple(currentMem.playsToday, currentMem.skipStreak, currentMem.isFrequentArtist)
                else -> musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
            }
            
            val memInfo = MusicInfo(
                title = snapshot.title,
                artist = snapshot.artist,
                packageName = snapshot.packageName,
                trackKey = snapshot.trackKey,
                artworkKey = currentInfo.artworkKey,
                artworkUri = snapshot.artworkUri ?: currentInfo.artworkUri,
                appIconKey = currentInfo.appIconKey,
                isPlaying = isPlaying,
                isSessionActive = snapshot.isSessionActive,
                currentLyric = if (!sessionChanged) currentMem.currentLyric else "",
                lyricsTrackKey = if (!sessionChanged) currentMem.lyricsTrackKey else "",
                playbackDeviceName = snapshot.playbackDeviceName,
                playbackDeviceType = snapshot.playbackDeviceType,
                durationMs = snapshot.durationMs,
                history = currentInfo.history,
                playsToday = plays,
                skipStreak = skip,
                isFrequentArtist = freq,
                lastUpdate = currentMem.lastUpdate // Se mantiene por el Reconciliador v4.0
            )
            
            val event = if (sessionChanged) {
                MusicUpdateEvent.NewSession(memInfo)
            } else if (trackContentChanged) {
                MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs)
            } else {
                MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType)
            }

            if (MusicStateProvider.applyEvent(event)) {
                val glanceEvent = if (sessionChanged) UpdateEvent.IdentityChange(snapshot.trackKey) else UpdateEvent.StatusUpdate
                uiUpdateFlow.tryEmit(glanceEvent)
            }
            
            if (sessionChanged) {
                relaunchLyricsTicker("identity_change")
            } else {
                val stateChangedUI = currentMem.isPlaying != isPlaying
                if (stateChangedUI) relaunchLyricsTicker("state_sync")
            }
        }
        // Solo guardamos de forma anticipada si el widget NO es visible (gating activo).
        // Si es visible, dejamos que el Stage 2 maneje la persistencia final para evitar race conditions.
        if (!isWidgetPotentiallyVisible()) {
            serviceScope.launch {
                val currentInfo = musicDataStore.musicInfoFlow.first()
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                
                val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                val logicalMusicInfo = MusicInfo(
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
                    lyricsTrackKey = finalLyricKey,
                    playbackDeviceName = snapshot.playbackDeviceName,
                    playbackDeviceType = snapshot.playbackDeviceType,
                    durationMs = snapshot.durationMs
                )
                val changed = musicDataStore.saveMusicInfo(logicalMusicInfo, forceUpdate = false)
                if (changed) {
                    lastCommittedInfo = logicalMusicInfo
                }
            }
        }

        // --- STAGE 2: PRESENTACIÓN (BLOQUEO POR COMPUERTA) ---

        if (!isWidgetPotentiallyVisible()) {
            Log.d(TAG, "[GATING] Presentación suprimida (Pantalla apagada/bloqueada).")
            InternalLogger.log(applicationContext, "STAGE 2: Suprimido (Pantalla bloqueada). Track=${snapshot.title}")
            isPresentationDirty = true
            pendingSnapshot = snapshot
            
            // Destrucción de Ticker de Letras para ahorro de batería
            lyricsUpdateJob?.cancel()
            
            // Abortamos Stage 2 para evitar I/O y CPU innecesarios
            lastObservedSnapshot = snapshot
            return
        }

        // Si el widget es visible, reseteamos flags de gating
        isPresentationDirty = false
        pendingSnapshot = null

        val previousApplied = 
            lastAppliedSnapshot

        val trackChangedUI = 
            previousApplied?.trackKey != snapshot.trackKey

        val appChangedUI = 
            previousApplied?.packageName != snapshot.packageName

        val stateChangedUI = 
            previousApplied?.playbackState != snapshot.playbackState

        val artworkChangedUI =
            previousApplied?.artworkKey != snapshot.artworkKey

        Log.d(TAG, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")

        try {

            // 1. Resolución de recursos visuales (Fase Cancelable).
            var resolvedArtwork: Bitmap? = null
            var resolvedAppIconFinal: Bitmap? = null
            var resolvedIconKey: String? = null
            var resolvedTierFinal: Int = TIER_NONE

            // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
            // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
            var artworkTimedOut = false

            if (controller != null && metadata != null && 
                (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
                
                // A. Portada
                resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(3500L) {
                    resolveArtworkDeduplicated(
                        controller = controller,
                        metadata = metadata,
                        artworkKey = snapshot.artworkKey,
                        generation = myGeneration
                    )
                } ?: run {
                    artworkTimedOut = true
                    null
                }

                // B. Icono de app (Optimizado)
                if (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION) {
                    val (icon, tier) = resolveAppIcon(snapshot.packageName)
                    
                    // Solo actualizamos si el nuevo tier es mejor o igual al actual (o es un cambio de app)
                    if (icon != null && (appChangedUI || tier > currentIconTier)) {
                        resolvedAppIconFinal = icon
                        resolvedIconKey = "${snapshot.packageName}_stable"
                        resolvedTierFinal = tier
                    }
                }
            }

            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (trackChangedUI) {
                Log.d(TAG, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                
                lyricsFetchJob = serviceScope.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)
                    
                    val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        Log.d(TAG, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(snapshot.trackKey, "")
                    }
                }
            } else {
                // Sincronización pasiva: Si no hay cambio de track, relanzamos solo si hay desvío o cambio de estado
                if (currentLyrics == null) {
                    currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                }

                val effectivePos = previousApplied?.let { calculateEffectiveProgress(it) } ?: 0L
                val drift = Math.abs(effectivePos - snapshot.positionMs)
                
                // Hard-Sync: Solo si el desvío es mayor a 1s o cambió el estado
                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true

                if (shouldResync && currentLyrics != null) {
                    relaunchLyricsTicker("state_sync")
                }
            }

            val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
            if (myGeneration != generation.get() && !isStillRelevant) {
                Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
                return
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                commitMutex.withLock {

                    val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey
                    if (myGeneration != generation.get() && !isStillRelevantInLock) {
                        Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
                        return@withLock
                    }

                    if (controller != null && metadata != null && 
                        (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
                        
                        if (resolvedArtwork != null) {
                            // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
                            // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
                            val cacheKey = "${rawSnapshot.artworkKey}_raw"
                            MusicWidget.bitmapCache.put(cacheKey, resolvedArtwork)

                            // Paso 3.2: CACHING DE TRANSFORMACIÓN
                            if (savedArtworkKey != snapshot.artworkKey) {
                                // 1. Guardar versión RAW
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                                
                                // 2. Guardar versión WIDGET (Píldora)
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
                                
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                                savedArtworkKey = snapshot.artworkKey

                                // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
                                // Inyectamos el bitmap en el snapshot lógico para que la próxima 
                                // transición de historial lo lleve ya resuelto.
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(
                                    artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
                                )
                            }
                        } else if (trackChangedUI || artworkChangedUI) {
                            // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
                            val placeholder = getPlaceholderBitmap()
                            saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                            saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
                            saveTextToFile("", ALBUM_ART_KEY_FILE)
                            savedArtworkKey = null
                        }

                        if (resolvedAppIconFinal != null && resolvedIconKey != null) {
                            saveBitmapToFile(resolvedAppIconFinal, APP_ICON_FILE)
                            saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
                            savedAppIconKey = resolvedIconKey
                            currentIconTier = resolvedTierFinal
                        } else if (appChangedUI) {
                            // FIX: Solo borramos la llave si la APP cambió y no tenemos nuevo icono.
                            // Esto evita la alternancia visual (flicker) al cambiar de track en la misma app.
                            saveTextToFile("", APP_ICON_KEY_FILE)
                            savedAppIconKey = null
                            currentIconTier = TIER_NONE
                        }
                    }

                    val currentInfo = musicDataStore.musicInfoFlow.first()
                    val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                    val canKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                    
                    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                    val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                    val (playsToday, skipStreak, isFrequent) = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)

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
                        lyricsTrackKey = finalLyricKey,
                        playbackDeviceName = snapshot.playbackDeviceName,
                        playbackDeviceType = snapshot.playbackDeviceType,
                        durationMs = snapshot.durationMs,
                        history = currentInfo.history,
                        playsToday = playsToday,
                        skipStreak = skipStreak,
                        isFrequentArtist = isFrequent
                    )

                    // 1. Sincronía Atómica: Disco -> RAM -> UI
                    val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
                    
                    if (artworkTimedOut) {
                        Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
                    }
                    
                    // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
                    // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
                    val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo))

                    if (Build.VERSION.SDK_INT >= 35 && snapshot.playbackState == PlaybackState.STATE_PLAYING) {
                        val now = System.currentTimeMillis()
                        val titleChanged = snapshot.title != lastPreviewTitle
                        if (titleChanged && (now - lastPreviewUpdate > 20 * 60 * 1000L)) {
                            lastPreviewUpdate = now
                            lastPreviewTitle = snapshot.title
                            serviceScope.launch {
                                try {
                                    val manager = GlanceAppWidgetManager(applicationContext)
                                    manager.setWidgetPreviews(MusicWidgetFullReceiver::class)
                                    manager.setWidgetPreviews(MusicWidgetPillReceiver::class)
                                    manager.setWidgetPreviews(MusicWidgetControlReceiver::class)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Fallo al publicar preview", e)
                                }
                            }
                        }
                    }

                    // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
                    // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
                    
                    if (changedDisco || sessionChanged || changedRAM) {
                        if (sessionChanged) {
                            uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
                        } else {
                            uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                        }
                    }
                    
                    lastAppliedSnapshot = snapshot
                    lastObservedSnapshot = snapshot
                    lastCommittedInfo = MusicStateProvider.current()
                }
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "[DIAGNOSTIC] CANCELLED: #$myGeneration aborted during resolution")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error en pipeline atómico #$myGeneration", e)
        } finally {
            if (inFlightSnapshot?.contentKey == snapshot.contentKey) {
                inFlightSnapshot = null
            }
        }
    }

    private fun relaunchLyricsTicker(reason: String) {
        if (!isWidgetPotentiallyVisible()) {
            lyricsUpdateJob?.cancel()
            return
        }

        val currentInfo = MusicStateProvider.current()
        if (currentInfo.isEmpty || !currentInfo.isSessionActive) {
            lyricsUpdateJob?.cancel()
            return
        }

        Log.d(TAG, "[LYRICS_TRACE] relaunchLyricsTicker: Reason=$reason | Track=${currentInfo.title}")
        lyricsUpdateJob?.cancel()
        
        // Hallazgo v3.8: Ticker Stateless (Claude). Lee identidad y estado directo de la RAM.
        lyricsUpdateJob = serviceScope.launch(Dispatchers.IO) {
            val lyricsRes = lyricsRepository.getLyrics(
                currentInfo.trackKey, 
                currentInfo.artist, 
                currentInfo.title, 
                currentInfo.durationMs
            ) ?: return@launch

            if (currentInfo.isPlaying) {
                runLyricsShowcase(currentInfo.trackKey, lyricsRes)
            } else {
                runPausedLyricsCycle(currentInfo.trackKey, lyricsRes)
            }
        }
    }

    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
        val snappinessOffset = 500L

        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            // REGLA DE IDENTIDAD: Si la canción cambió o ya no suena, el motor se apaga.
            if (currentRAM.trackKey != myTrackKey || !currentRAM.isPlaying) break
            
            // Usamos el Snapshot Lógico para el cálculo de posición real
            val snapshot = lastLogicalSnapshot ?: break
            val currentPos = calculateEffectiveProgress(snapshot)
            
            val entry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= (currentPos + snappinessOffset) }
            
            if (entry != null) {
                updateLyricInWidget(myTrackKey, entry.text)
            }

            val next = lyricsRes.allEntries.firstOrNull { it.timestampMs > (currentPos + snappinessOffset) }
            if (next != null) {
                val waitTime = (next.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                
                // Hallazgo v3.3: Silencios Inteligentes
                if (waitTime > 15000L) {
                    delay(8000L)
                    if (currentCoroutineContext().isActive && MusicStateProvider.current().trackKey == myTrackKey) {
                        updateLyricInWidget(myTrackKey, "")
                    }
                    delay((waitTime - 8000L).coerceAtLeast(100L))
                } else {
                    delay(waitTime)
                }
            } else {
                break
            }
        }
    }

    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
        var showLyric = true
        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            if (currentRAM.trackKey != myTrackKey || currentRAM.isPlaying) break
            
            val pausedPos = lastLogicalSnapshot?.positionMs ?: 0L
            
            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
            if (lastEntry == null && pausedPos < 5000L) {
                lastEntry = lyricsRes.allEntries.firstOrNull()
            }

            val text = if (showLyric && lastEntry != null) lastEntry.text else ""
            updateLyricInWidget(myTrackKey, text)
            
            showLyric = !showLyric
            delay(60000L)
        }
    }

    private fun updateLyricInWidget(trackKey: String, lyric: String) {
        // Relevo Atómico (v4.0)
        serviceScope.launch {
            if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
        }
    }

    private fun resolveAppIcon(packageName: String): Pair<Bitmap?, Int> {
        val density = applicationContext.resources.displayMetrics.density
        val targetSizePx = (14 * density).toInt()

        return try {
            val existingInVault = iconVault[packageName]
            
            val notifications = getActiveNotifications()
            val targetToken = selectedController?.sessionToken
            
            // PRIORIDAD 1: Icono de la Notificación (Referencia Maestra)
            // 1.1 Match por Token
            var mediaNotif = if (targetToken != null) {
                notifications.firstOrNull { sbn ->
                    val token = sbn.notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                    token == targetToken
                }
            } else null

            // 1.2 Fallback: Match por PackageName + MediaSession Extra
            if (mediaNotif == null) {
                mediaNotif = notifications.firstOrNull { 
                    it.packageName == packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) 
                }
            }

            // 1.3 Fallback final: Match por PackageName
            if (mediaNotif == null) {
                mediaNotif = notifications.firstOrNull { it.packageName == packageName }
            }

            val iconFromNotif = mediaNotif?.notification?.smallIcon?.loadDrawable(this)?.toBitmap()
            if (iconFromNotif != null) {
                val normalized = Bitmap.createScaledBitmap(iconFromNotif, targetSizePx, targetSizePx, true)
                iconVault[packageName] = normalized to TIER_NOTIFICATION
                return normalized to TIER_NOTIFICATION
            }

            // PRIORIDAD 2: Rescate Monocromático
            // Si ya tenemos un icono de Tier 2 en la bóveda, lo devolvemos para evitar re-procesar
            if (existingInVault != null && existingInVault.second == TIER_MONOCHROME) {
                return existingInVault
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val appIcon = packageManager.getApplicationIcon(packageName)
                if (appIcon is android.graphics.drawable.AdaptiveIconDrawable) {
                    val monochrome = appIcon.monochrome
                    if (monochrome != null) {
                        val rawMonochrome = getNativeAwareMonochromeBitmap(monochrome)
                        val normalized = ImageUtils.normalizeIcon(rawMonochrome, isColorFallback = false, targetSizePx = targetSizePx)
                        iconVault[packageName] = normalized to TIER_MONOCHROME
                        return normalized to TIER_MONOCHROME
                    }
                }
            }

            // PRIORIDAD 3: Color Fallback (Normalizado y con Sharpening)
            if (existingInVault != null && existingInVault.second == TIER_COLOR) {
                return existingInVault
            }

            val colorIcon = packageManager.getApplicationIcon(packageName).toBitmap()
            val normalized = ImageUtils.normalizeIcon(colorIcon, isColorFallback = true, targetSizePx = targetSizePx)
            iconVault[packageName] = normalized to TIER_COLOR
            return normalized to TIER_COLOR

        } catch (e: Exception) {
            Log.e(TAG, "Error en jerarquía de resolución de icono para $packageName", e)
            iconVault[packageName] ?: (null to TIER_NONE)
        }
    }

    /**
     * Renderiza la capa monochrome respetando la resolución nativa para evitar pixelado.
     */
    private fun getNativeAwareMonochromeBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val density = applicationContext.resources.displayMetrics.density
        val standardSize = (108 * density).toInt()
        
        val intrinsicW = drawable.intrinsicWidth
        val intrinsicH = drawable.intrinsicHeight
        
        // Si el recurso es ráster y pequeño, no forzamos el lienzo de 108dp para evitar "zoom borroso"
        val renderSize = if (intrinsicW > 0 && intrinsicH > 0 && intrinsicW < standardSize) {
            max(intrinsicW, intrinsicH)
        } else {
            standardSize
        }

        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, renderSize, renderSize)
        drawable.draw(canvas)
        return bitmap
    }

    private suspend fun resolveArtworkDeduplicated(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String,
        generation: Long
    ): Bitmap? {
        artworkCache.get(artworkKey)?.let { bitmap ->
            Log.d(TAG, "Artwork cache hit: $artworkKey")
            return bitmap
        }
        val deferred = getOrCreateArtworkDeferred(controller, metadata, artworkKey, generation)
        return deferred.await()
    }

    private suspend fun getOrCreateArtworkDeferred(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String,
        generation: Long
    ): Deferred<Bitmap?> {
        artworkInFlightMutex.withLock {
            artworkCache.get(artworkKey)?.let { bitmap ->
                return CompletableDeferred(bitmap)
            }
            artworkInFlight[artworkKey]?.let { existing ->
                if (existing.isActive) {
                    Log.d(TAG, "Artwork ya está en vuelo; reutilizando Deferred: $artworkKey")
                    return existing
                }
                artworkInFlight.remove(artworkKey)
            }
            val deferred = serviceScope.async {
                try {
                    val bitmap = findRealAlbumArt(controller, metadata, artworkKey)
                    val isStillRelevant = artworkKey == lastObservedSnapshot?.artworkKey
                    if (isActive && (generation == this@MusicNotificationListener.generation.get() || isStillRelevant) && bitmap != null) {
                        artworkCache.put(artworkKey, bitmap)
                    }
                    bitmap
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolviendo artwork", e)
                    null
                } finally {
                    artworkInFlightMutex.withLock {
                        val current = artworkInFlight[artworkKey]
                        if (current === coroutineContext[Job]) {
                            artworkInFlight.remove(artworkKey)
                        }
                    }
                }
            }
            artworkInFlight[artworkKey] = deferred
            return deferred
        }
    }

    private suspend fun findRealAlbumArt(
        controller: MediaController,
        metadata: MediaMetadata,
        artworkKey: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        val minArtDimension = MIN_ART_DIMENSION
        val targetTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        Log.d(TAG, "---- Resolviendo portada para: $targetTitle ----")
        
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { bitmap ->
            if (isValidArtwork(bitmap, minArtDimension)) return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
        }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { bitmap ->
            if (isValidArtwork(bitmap, minArtDimension)) return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
        }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { bitmap ->
            if (isValidArtwork(bitmap, minArtDimension)) return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
        }
        try {
            val notifications = getActiveNotifications()
            val mediaNotification = notifications.firstOrNull { sbn ->
                sbn.packageName == controller.packageName && sbn.notification.category == Notification.CATEGORY_TRANSPORT
            }
            if (mediaNotification != null) {
                val notifTitle = mediaNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                if (notifTitle != null && notifTitle.equals(targetTitle, ignoreCase = true)) {
                    mediaNotification.notification.getLargeIcon()?.loadDrawable(this@MusicNotificationListener)?.toBitmap()?.let {
                        if (isValidArtwork(it, minArtDimension)) return@withContext ensureMaxDimension(it, MAX_ART_DIMENSION)
                    }
                    @Suppress("DEPRECATION")
                    mediaNotification.notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)?.let {
                        if (isValidArtwork(it, minArtDimension)) return@withContext ensureMaxDimension(it, MAX_ART_DIMENSION)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo consultando notificación activa", e)
        }
        metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.takeIf { it.isNotBlank() }?.let { uri ->
            decodeAlbumArtUri(uri)?.let { bitmap ->
                if (isValidArtwork(bitmap, minArtDimension)) return@withContext bitmap
            }
        }
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.takeIf { it.isNotBlank() }?.let { uri ->
            decodeAlbumArtUri(uri)?.let { bitmap ->
                if (isValidArtwork(bitmap, minArtDimension)) return@withContext bitmap
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

    private fun isValidArtwork(bitmap: Bitmap, minDimension: Int): Boolean {
        return !bitmap.isRecycled && bitmap.width >= minDimension && bitmap.height >= minDimension
    }

    private suspend fun decodeAlbumArtUri(uriString: String): Bitmap? {
        if (uriString.startsWith(SPOTIFY_MEDIA_API_PREFIX)) {
            val hash = Uri.decode(uriString).substringAfterLast(":").substringBefore("?")
            if (hash.isNotBlank()) return downloadBitmapFromUrl("$SPOTIFY_CDN_PREFIX$hash")
        }
        if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
            return downloadBitmapFromUrl(uriString)
        }
        return try {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Fallo al decodificar URI: $uriString", e)
            null
        }
    }

    private suspend fun downloadBitmapFromUrl(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            connection.readTimeout = NETWORK_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.useCaches = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} descargando artwork")
                return@withContext null
            }
            connection.inputStream.use { input ->
                if (!isActive) return@withContext null
                decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is SocketException && !isActive) {
                // Silently ignore
            } else {
                Log.e(TAG, "Fallo descargando artwork: $urlString", e)
            }
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeSampledBitmapFromStream(inputStream: java.io.InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        val start = System.currentTimeMillis()
        val buffer = inputStream.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
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

    private fun getPlaceholderBitmap(): Bitmap {
        return try {
            val drawable = ContextCompat.getDrawable(applicationContext, R.drawable.ic_music_note)
            drawable?.toBitmap() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear placeholder", e)
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private suspend fun saveTextToFile(text: String, fileName: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val finalFile = File(filesDir, fileName)
                val tempFile = File(filesDir, "$fileName.tmp")
                tempFile.writeText(text)
                try {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo archivo de texto $fileName", e)
            }
        }
    }

    private suspend fun saveBitmapToFile(bitmap: Bitmap, fileName: String, applyPillTransform: Boolean = false) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                // Paso 3.1: Procesamiento visual en Dispatchers.Default (Matemática pura fuera de IO/Main)
                val processedBitmap = if (applyPillTransform) {
                    withContext(Dispatchers.Default) {
                        val maxDimension = MAX_ART_DIMENSION
                        val outputBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val (newWidth, newHeight) = if (ratio > 1f) {
                                maxDimension to (maxDimension / ratio).toInt().coerceAtLeast(1)
                            } else {
                                (maxDimension * ratio).toInt().coerceAtLeast(1) to maxDimension
                            }
                            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                        } else {
                            bitmap
                        }
                        
                        val pillWidthPx = applicationContext.resources.getDimensionPixelSize(R.dimen.album_art_size_classic)
                        val result = ImageUtils.createRotatedPillBitmap(outputBitmap, -28f, pillWidthPx, 0.9f)
                        
                        if (outputBitmap !== bitmap) outputBitmap.recycle()
                        result
                    }
                } else {
                    null
                }

                val bitmapToSave = processedBitmap ?: bitmap
                val finalFile = File(filesDir, fileName)
                val tempFile = File(filesDir, "$fileName.tmp")
                
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                
                FileOutputStream(tempFile).use { output ->
                    bitmapToSave.compress(format, 85, output)
                    output.fd.sync()
                }
                
                if (processedBitmap != null && processedBitmap !== bitmap) processedBitmap.recycle()
                
                try {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo archivo $fileName", e)
            }
        }
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "[DIAGNOSTIC] PERMISSION_SYNC: Listener disconnected. Resetting widget state.")
        unregisterDynamicScreenReceiver()
        uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
        super.onListenerDisconnected()
    }

    private fun unregisterDynamicScreenReceiver() {
        runCatching {
            unregisterReceiver(dynamicScreenReceiver)
        }.onFailure {
            Log.w(TAG, "Fallo al desregistrar receiver dinámico", it)
        }
    }

    override fun onDestroy() {
        unregisterDynamicScreenReceiver()
        pendingRefreshJob?.cancel()
        artworkInFlightMutex.tryLock().let { locked ->
            if (locked) {
                try {
                    artworkInFlight.values.forEach { deferred -> deferred.cancel() }
                    artworkInFlight.clear()
                } finally {
                    artworkInFlightMutex.unlock()
                }
            }
        }
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        
        controllerCallbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        controllerCallbacks.clear()
        selectedController = null
        lastObservedSnapshot = null
        lastAppliedSnapshot = null
        inFlightSnapshot = null
        savedArtworkKey = null
        artworkCache.evictAll()
        iconVault.clear()
        lyricsUpdateJob?.cancel()
        lyricsFetchJob?.cancel()
        Log.d(TAG, "[DIAGNOSTIC] SERVICE_LIFECYCLE: onDestroy - Process ending")
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MusicListener"
        private var lastCommittedInfo: MusicInfo? = null
        fun getLatestMusicInfo(): MusicInfo? = lastCommittedInfo

        private const val TIER_NONE = 0
        private const val TIER_COLOR = 1
        private const val TIER_MONOCHROME = 2
        private const val TIER_NOTIFICATION = 3

        private const val ALBUM_ART_FILE = "album_art.webp"
        private const val ALBUM_ART_RAW_FILE = "album_art_raw.webp"
        private const val ALBUM_ART_KEY_FILE = "album_art.key"
        private const val APP_ICON_FILE = "app_icon.webp"
        private const val APP_ICON_KEY_FILE = "app_icon.key"
        private const val MIN_ART_DIMENSION = 100
        private const val MAX_ART_DIMENSION = 800
        private const val NORMAL_DEBOUNCE_MS = 150L
        private const val FAST_DEBOUNCE_MS = 100L
        private const val METADATA_STABILIZATION_MS = 400L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 3000
        private const val NETWORK_READ_TIMEOUT_MS = 3000
        private const val ARTWORK_CACHE_SIZE_KB = 8 * 1024
        private const val ARTWORK_TIMEOUT_MS = 7000L
        private const val SPOTIFY_MEDIA_API_PREFIX = "content://com.spotify.mobile.android.mediaapi"
        private const val SPOTIFY_CDN_PREFIX = "https://i.scdn.co/image/"
    }
}
