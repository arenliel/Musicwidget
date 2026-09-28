package arenliel.musicwidget

import android.app.Notification
import android.appwidget.AppWidgetManager
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
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

    private val historyHandler = CoroutineExceptionHandler { _, e ->
        InternalLogger.e(applicationContext, "[HIST_CONSUMER] SCOPE_LEVEL_CRASH: ${e.message}")
    }

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
     * Protege las mutaciones del estado en RAM (MusicStateProvider).
     */
    private val mutationMutex = Mutex()

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
    // Conjunto Icono-Refactor-1: `iconVault` fue eliminada. Su función (evitar recalcular el
    // ícono de una app ya vista) ahora la cumple IconRegistry de forma permanente y
    // persistida en disco, en vez de una caché de RAM que se perdía en cada reinicio de
    // proceso.

    /*
     * IDENTIDAD DE PISTA (v5.2): Clave inmutable basada puramente en contenido.
     * Sanitiza los strings para evitar desincronías por espacios o mayúsculas.
     */
    private data class TrackIdentity(val title: String, val artist: String) {
        val coreKey: String get() = "$title|$artist"
        
        companion object {
            fun from(snapshot: MediaSnapshot) = TrackIdentity(
                title = snapshot.title.trim().lowercase(),
                artist = snapshot.artist.trim().lowercase()
            )
        }
    }

    /*
     * CONTEXTO DE REPRODUCCIÓN (v5.2.1): Agrupa metadatos volátiles.
     */
    private data class PlaybackContext(
        val durationMs: Long,
        val album: String?,
        val artworkKey: String,
        /**
         * Conjunto Artwork-Stabilization (Fase 4/5): the artwork key MediaSession/Metrolist last
         * suggested is NOT the same as what's actually confirmed and persisted to disk.
         * [artworkKey] can update the instant a new metadata event arrives; this field only
         * updates once [saveTextToFile] for ALBUM_ART_KEY_FILE actually succeeds. All
         * sync-decision code (artIncoherent, the write-gate, the DIAG_V7_KEY dedup guards)
         * must read THIS field, never [artworkKey] — reading the wrong one reintroduces the
         * "confirmed identity treated as stale" bug this whole conjunto exists to fix.
         */
        val confirmedArtworkKey: String? = null
    )

    /*
     * SESIÓN LÓGICA (v5.2.1): Portador de la inmutabilidad de la sesión.
     * El UUID garantiza que los eventos de cierre correspondan a la sesión correcta.
     */
    private data class LogicalSession(
        /**
         * Uniquely identifies one physical listening session instance, even across immediate
         * repeats of the identical track. Used to distinguish a genuine repeat play from a
         * duplicate/burst signal of the same underlying event (see the idempotency shield).
         */
        val sessionUUID: String = java.util.UUID.randomUUID().toString(),
        val identity: TrackIdentity,
        val birthSnapshot: MediaSnapshot, // Capturado al nacer, inmutable (v6.5)
        var liveSnapshot: MediaSnapshot,  // Actualizado en cada tick (v6.5)
        val frozenTrackKey: String,       // Identidad física congelada al nacer (v6.5)
        var maxPositionMs: Long,          // MARCA DE AGUA MONOTÓNICA (Bloque D)
        var isProvisional: Boolean = false, // Defensa Post-Boot (v7.0)
        // Conjunto Letras-Atomicas-8: true en cuanto esta sesión observa un Estado=PLAYING
        // real por primera vez. Antes de eso (OTHER/carga), el fallback "primeros 5
        // segundos" de runPausedLyricsCycle no debe activarse — ver ese sitio para el
        // porqué completo (caso "Paranoia").
        var hasConfirmedPlayback: Boolean = false,
        // Conjunto Instrumentacion-Letras-Display-1: evita loguear "primera letra mostrada" más
        // de una vez por sesión — se pone en true la primera vez que updateLyricInWidget escribe
        // una letra no vacía para esta sesión. Al ser un campo nuevo de esta data class, cada
        // instancia (cada canción) nace con su propio valor en false; no requiere reinicio manual.
        var firstLyricDisplayLogged: Boolean = false,
        val startedAtRealtime: Long = android.os.SystemClock.elapsedRealtime(),
        var playbackContext: PlaybackContext,
        val context: Context
    ) {
        val sessionIdentity: String get() = "${birthSnapshot.packageName}|${identity.title}|${identity.artist}"

        // Conjunto Letras-Atomicas-1: espacio de trabajo propio de esta sesión, para todo lo
        // relacionado con letras (descarga, ticker en vivo, ciclo de pausa, escritura al widget).
        // Nunca se comparte entre sesiones. Al cancelarse (ver los 3 puntos donde
        // currentLogicalSession cambia de valor en processSnapshot/onListenerConnected),
        // TODO trabajo de letras en curso para esta canción muere con ella automáticamente —
        // ya no depende de que alguien recuerde cancelar cada pieza suelta por separado.
        val lyricsScope: kotlinx.coroutines.CoroutineScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }

    private var currentLogicalSession: LogicalSession? = null

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
    // Conjunto Letras-Atomicas-2: identidad de negocio (sessionIdentity, SIN duración) con la
    // que se obtuvo `currentLyrics`. Es la única llave válida para decidir si se puede reutilizar
    // sin volver a golpear red/disco. Nunca comparar por trackKey (incluye duración, cambia con
    // refinamientos tardíos) ni por un simple null-check (eso fue exactamente el bug: decía
    // "tengo letra cargada" sin decir de qué canción era).
    private var currentLyricsIdentity: String? = null
    private var lyricsUpdateJob: Job? = null
    private val lyricsLock = Any()
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
    private var degradedStreakStartRealtime: Long? = null

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
    private var lastProcessedSessionUUID: String? = null

    /*
     * MONOTONIC GUARD (v4.5): Rastrea el progreso para detectar bucles (loops).
     */
    private var lastObservedPositionMs = 0L

    private sealed class HistoryEvent {
        data class CommitSession(
            val sessionUUID: String,
            val birthSnapshot: MediaSnapshot,
            val finalSnapshot: MediaSnapshot,
            val maxPositionMs: Long,
            val startedAtRealtime: Long
        ) : HistoryEvent()
    }

    private val historyChannel = Channel<HistoryEvent>(Channel.UNLIMITED)
    private val bootGate = CompletableDeferred<Unit>()
    private val BOOT_GATE_TIMEOUT_MS = 2000L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val successCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val failureCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalEventsCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingEventsCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val resurrectionsCount = java.util.concurrent.atomic.AtomicInteger(0)

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

    // Conjunto Artwork-Stabilization (Fase 5): `savedArtworkKey` (a loose service-wide variable)
    // was removed entirely. Its job — "what artwork key is confirmed on disk right now" — now
    // belongs to `session.playbackContext.confirmedArtworkKey`, scoped to the song it actually
    // describes instead of a single shared slot that any song could overwrite or misread.


    /*
     * Icono de la app guardado actualmente en disco.
     */
    private var savedAppIconKey: String? = null
    private var currentIconTier: Int = TIER_NONE
    private var identityGenerationCounter: Int = 0

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
        InternalLogger.d(applicationContext, "[GATING] Display unavailable. Closing gate.")
        InternalLogger.log(applicationContext, "GATING: Pantalla apagada. Compuerta CERRADA.")
        lyricsUpdateJob?.cancel()
        unlockPollingJob?.cancel()
    }

    private fun onDisplayFullyVisible() {
        InternalLogger.d(applicationContext, "[GATING] Display fully visible. Triggering Wake-up Sync.")
        InternalLogger.log(applicationContext, "GATING: Desbloqueo detectado. Forzando reprocesamiento de sesión.")
        
        if (hasPendingUpdates) {
            InternalLogger.d(applicationContext, "[GATING] Aplicando actualizaciones postergadas a Glance.")
            hasPendingUpdates = false
            serviceScope.launch {
                MusicWidget.updateAll(applicationContext)
            }
        }

        // Sincronización de recuperación (Conjunto Correccion-Carrera-Icono-1): antes esto
        // lanzaba su propia corrutina, totalmente independiente de pendingRefreshJob, así que
        // podía ejecutar processSnapshot() en paralelo con cualquier otro refresco ya en
        // curso (p.ej. uno disparado por onMetadataChanged milisegundos antes). Dos
        // invocaciones concurrentes de processSnapshot() podían pisarse entre sí — ver
        // auditoria-icono-carrera-resincronizacion-ronda1.md para el caso concreto que esto
        // producía sobre el ícono de la app. Ahora pasa por el mismo mecanismo de
        // cancelación que usa el resto de los disparadores: el refresco más reciente cancela
        // automáticamente al anterior, en vez de que dos corran a la vez.
        requestRefresh(fast = true, reason = "catch_up_render") {
            // PASO 4: Iniciar reconciliación del historial pendiente
            reconcilePendingHistoryArtworks()
        }
    }

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
        val observedAtRealtime: Long = SystemClock.elapsedRealtime(),
        val positionUpdatedAtRealtime: Long = observedAtRealtime,
        val playbackSpeed: Float = 1.0f,
        val playbackDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    ) {
        /**
         * Canonical "is this the same song" identity: package + title + artist, normalized.
         * This is the single source of truth for business-logic identity comparisons.
         * Never include album or duration here.
         */
        val sessionIdentity: String
            get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)

        /**
         * Business identity key used for history matching, streak tracking, and "blessed repeat"
         * detection. Deliberately does NOT include the album — a song's single and album editions
         * are treated as the same track for these purposes (see Conjunto C).
         * Do not add album back into this formula; use [artworkKey] for anything that needs to
         * distinguish album editions.
         */
        val trackKey: String
            get() = "$sessionIdentity|$durationMs"

        /**
         * Visual identity key used for artwork resolution and caching. Falls back to its own
         * album-aware key (not [trackKey]) specifically so that different album editions of the
         * same song can carry different artwork. Do not change this fallback to use [trackKey] —
         * that reintroduces cross-contamination between album editions' cached artwork (see the
         * "Harana" and "Archie, Marry Me" history bugs, Conjunto C).
         */
        val artworkKey: String
            get() =
                artworkUri
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "$sessionIdentity|${MusicDataStore.normalize(album)}"

        /*
         * Identidad base de contenido (v9.0).
         */
        val coreKey: String
            get() = "${MusicDataStore.normalize(title)}|${MusicDataStore.normalize(artist)}"

        /*
         * Identidad completa del snapshot.
         * Incluye la posición redondeada para detectar Seeks significativos.
         */
        val contentKey: String
            get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"

        /** 
         * ORÁCULO DE TIEMPO PURO (v9.0): Proyecta la posición basado en el tiempo transcurrido.
         * No conserva estado de marca de agua (Regla D.4).
         */
        fun projectedPositionMs(
            nowRealtime: Long = SystemClock.elapsedRealtime()
        ): Long {
            if (playbackState != PlaybackState.STATE_PLAYING) return positionMs
            val delta = nowRealtime - positionUpdatedAtRealtime
            val projected = positionMs + (delta * playbackSpeed).toLong()
            return if (durationMs > 0) projected.coerceIn(0L, durationMs)
            else projected.coerceAtLeast(0L)
        }

        companion object {
            /** Única ruta admitida de rehidratación (v7.0). */
            fun fromPersisted(info: MusicInfo): MediaSnapshot {
                return MediaSnapshot(
                    packageName = info.packageName,
                    title = info.title,
                    artist = info.artist,
                    album = info.album,
                    mediaId = "",
                    // Conjunto Identidad-Rehidratacion-1: se usa info.artworkKey (la clave cruda de
                    // deduplicación que el propio código ya preserva correctamente) en vez de
                    // info.artworkUri (que, una vez resuelta la portada, es la ruta LOCAL del
                    // archivo cacheado). MediaSnapshot.artworkKey se calcula a partir de este campo
                    // — si aquí queda una ruta local, nunca podrá coincidir con la URL remota cruda
                    // que trae un snapshot en vivo para la misma canción sin cambiar, y
                    // artworkChangedUI da un falso "cambió" en cada reinicio de servicio.
                    artworkUri = info.artworkKey,
                    playbackState = if (info.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    isSessionActive = info.isSessionActive,
                    playbackDeviceName = info.playbackDeviceName,
                    playbackDeviceType = info.playbackDeviceType,
                    durationMs = info.durationMs,
                    positionMs = info.lastMaxPositionMs, // Usar el último récord como base al boot
                    observedAtRealtime = SystemClock.elapsedRealtime()
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        InternalLogger.init(this)
        
        InternalLogger.log(this, "[BUILD_ID] " +
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "sha=${BuildConfig.GIT_SHA} built=${BuildConfig.BUILD_TIME}")

        InternalLogger.d(this, "SERVICE_LIFECYCLE: onCreate - Process started")

        mediaSessionManager =
            getSystemService(
                Context.MEDIA_SESSION_SERVICE
            ) as MediaSessionManager

        musicDataStore =
            MusicDataStore(
                applicationContext
            )

        lyricsRepository = LyricsRepository(applicationContext)

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
                .debounce(150L) // Consolidación estricta de ráfagas (v5.3)
                .collect { event ->
                    // REGLA DE ORO (v2.9): Hiato total en reposo para ahorro de batería (Batería Cero)
                    if (!isWidgetPotentiallyVisible()) {
                        InternalLogger.d(applicationContext, "[DIAGNOSTIC] UI_DISPATCHER: Widget no visible. Postponing update.")
                        hasPendingUpdates = true
                        // Hallazgo v3.5: Cancelación física del Ticker en reposo
                        lyricsUpdateJob?.cancel()
                        return@collect
                    }

                    // Hallazgo v3.5: Recuperación proactiva del Ticker al despertar (ACTION_USER_PRESENT indirecto)
                    if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
                        relaunchLyricsTicker("screen_wake")
                    }

                    InternalLogger.d(applicationContext, "[DIAGNOSTIC] UI_DISPATCHER: Ejecutando actualización atómica de Glance (Event=$event)")
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
                    InternalLogger.d(applicationContext, "[LYRICS_TRACE] Aplicando Seek (Lag compensado: ${processingLag}ms): ${position + processingLag}ms")
                    
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
                InternalLogger.d(applicationContext, "[SHADOW_OBSERVER] Escudo de idempotencia restaurado: ${last.title}")
            }
        }
    }

    private fun startBlacklistObserver() {
        serviceScope.launch {
            musicDataStore.musicInfoFlow.collect { info ->
                val currentPkg = lastObservedSnapshot?.packageName
                if (currentPkg != null && info.blacklist.contains(currentPkg)) {
                    InternalLogger.d(applicationContext, "[BLACKLIST_PURGE] App actual $currentPkg ha sido añadida a la lista negra. Purgando.")
                    
                    // 1. Limpieza en Disco
                    musicDataStore.clearActiveSession()
                    
                    // 2. Limpieza en Memoria del Listener (v2.2)
                    lastAppliedSnapshot = null
                    lastLogicalSnapshot = null
                    lastObservedSnapshot = null
                    inFlightSnapshot = null
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
        val workerId = System.identityHashCode(this)
        serviceScope.launch(Dispatchers.IO + historyHandler) {
            InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_LOOP_START")
            
            // Heartbeat cada 60s (B1.4)
            launch {
                while (isActive) {
                    delay(60000L)
                    InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_HEARTBEAT: " +
                        "scopeActive=$isActive, channelClosed=${historyChannel.isClosedForSend}, " +
                        "successCount=${successCounter.get()}, failCount=${failureCounter.get()}, " +
                        "pendingCount=${pendingEventsCount.get()}, resurrections=${resurrectionsCount.get()}")
                }
            }

            while (isActive) {
                try {
                    for (event in historyChannel) {
                        pendingEventsCount.decrementAndGet()
                        try {
                            when (event) {
                                is HistoryEvent.CommitSession -> {
                                    val durationObserved = android.os.SystemClock.elapsedRealtime() - event.startedAtRealtime
                                    
                                    InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] EVENT_RECEIVED: ${event.finalSnapshot.title} (UUID=${event.sessionUUID}, Observado=${durationObserved}ms)")

                                    // BLOQUEO DE SESIONES FANTASMA
                                    if (durationObserved <= 1000L) {
                                        InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] Sesión fantasma bloqueada.")
                                    } else if (durationObserved >= 5000L || event.maxPositionMs >= 5000L) {
                                        commitToHistory(event.sessionUUID, event.birthSnapshot, event.finalSnapshot, event.maxPositionMs)
                                        InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] EVENT_PERSISTED: ${event.finalSnapshot.title}")
                                    } else {
                                        InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] Pista descartada por corta.")
                                    }
                                }
                            }
                        } catch (ce: CancellationException) { throw ce }
                        catch (e: Exception) {
                            InternalLogger.e(applicationContext, "[HIST_CONSUMER] [$workerId] EVENT_FAILED: ${e.message}")
                        }
                    }
                    InternalLogger.w(applicationContext, "[HIST_CONSUMER] [$workerId] CHANNEL_CLOSED_EXIT")
                    break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    resurrectionsCount.incrementAndGet()
                    InternalLogger.e(applicationContext, "[HIST_CONSUMER] [$workerId] LOOP_DEATH_RESURRECTING #${resurrectionsCount.get()}: ${t.message}")
                    delay(500L)
                }
            }
            InternalLogger.d(applicationContext, "[HIST_CONSUMER] [$workerId] CONSUMER_LOOP_EXIT")
        }
    }



    private suspend fun commitToHistory(sessionUUID: String, startSnapshot: MediaSnapshot, endSnapshot: MediaSnapshot, maxPositionMs: Long) {
        try {
            val historyDir = File(filesDir, "history")
            if (!historyDir.exists()) historyDir.mkdirs()

            // DETECTOR HIST_POISON (v9.0): Vigila la divergencia de identidad en el commit (Regla B.5)
            if (endSnapshot.title != startSnapshot.title || endSnapshot.artist != startSnapshot.artist) {
                InternalLogger.w(applicationContext, "[HIST_POISON] Identidad divergente en commit. " +
                    "Birth='${startSnapshot.title} / ${startSnapshot.artist}' " +
                    "Capsule='${endSnapshot.title} / ${endSnapshot.artist}' " +
                    "UUID=$sessionUUID")
            }

            // REGLA 10: Toda identidad procede de MediaSnapshot. Prohibida interpolación manual.
            val trackKey = endSnapshot.trackKey
            
            // v9.0: Escritura directa a ruta definitiva (Bloque B.2). 
            // Eliminamos la dependencia de /history/buffer para el commit.
            val artworkFile = File(historyDir, "art_${sessionUUID}.webp")
            
            // Si no existe, intentamos rescate (Última oportunidad)
            if (!artworkFile.exists()) {
                val myCoreKey = startSnapshot.coreKey
                var memoryBitmap = memoryArtworkCache[myCoreKey]
                
                if (memoryBitmap == null) {
                    try {
                        kotlinx.coroutines.withTimeout(ARTWORK_PROMOTION_TIMEOUT_MS) {
                            val resolved = resolveArtworkDeduplicated(
                                snapshot = startSnapshot,
                                generation = -1L // History Rescue mode
                            )
                            if (resolved != null) memoryBitmap = resolved
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[SHADOW_OBSERVER] Fallo rescate de artwork: $trackKey")
                    }
                }

                if (memoryBitmap != null) {
                    val density = applicationContext.resources.displayMetrics.density
                    val w = (80 * density).toInt()
                    val h = (40 * density).toInt()
                    val historyPill = ImageUtils.createHorizontalPill(memoryBitmap!!, w, h)
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, historyPill, sessionUUID)
                    historyPill.recycle()
                }
            }

            val hasArtwork = artworkFile.exists()
            // v9.0: Usar marca de agua rehidratada/persistente para clasificación (Bloque D)
            val finalPos = maxPositionMs
            
            // CASCADA DE DURACIÓN (v9.0: Blindaje contra valores <= 0)
            val effectiveDuration = when {
                endSnapshot.durationMs > 0 -> endSnapshot.durationMs
                startSnapshot.durationMs > 0 -> startSnapshot.durationMs
                else -> {
                    val diskDur = musicDataStore.musicInfoFlow.first().durationMs
                    if (diskDur > 0) diskDur else 0L
                }
            }

            val progressFactor = if (effectiveDuration > 0) {
                finalPos.toFloat() / effectiveDuration.toFloat()
            } else -1f // Indicador de UNKNOWN

            // FÓRMULA DE SKIP PURA (v6.5): Basada exclusivamente en el progreso del Snapshot final
            // v7.0: Blindaje contra división por cero (UNKNOWN)
            var isSkipped = progressFactor in 0.0f..0.4f
            val rawIsSkipped = isSkipped // Veredicto crudo, sin perdón — para la racha de repetición

            InternalLogger.d(applicationContext, "[DIAG_V6] [SKIP_MATH] Track=${endSnapshot.title}, FinalPos=${finalPos}ms, Duration=${effectiveDuration}ms, Factor=$progressFactor, Verdict=$isSkipped")

            // Conjunto Bendecida-1: reemplaza la búsqueda contra el historial en RAM (que
            // comparaba por trackKey, incluyendo duración — inestable entre reproducciones de
            // la misma canción) por la caja permanente y dedicada en MusicDataStore, que
            // identifica solo por título+artista y tiene su propio vencimiento de 14 días.
            // Conjunto Bendecida-2: antes, "Bendecida" se renovaba con cualquier escucha que no
            // fuera un salto — es decir, también con escuchas PARCIALES (40%-85%). La intención
            // original era que solo una escucha COMPLETA (85% o más) blindara la canción. Se usa
            // progressFactor directamente aquí porque `isPartial` todavía no existe en este punto
            // de la función (se calcula más abajo, después de este llamado).
            val isBlessed = musicDataStore.updateBlessedStatus(startSnapshot.title, startSnapshot.artist, isCompleted = progressFactor >= 0.85f)
            if (isBlessed && isSkipped) isSkipped = false
            
            InternalLogger.d(applicationContext, "[STREAK_TRACE] Bendecida: track=$trackKey, isBlessed=$isBlessed, rawIsSkipped=$rawIsSkipped, isSkippedPostPerdon=$isSkipped")

            val isPartial = !isSkipped && progressFactor < 0.85f

            val outcome = when {
                isSkipped -> "SKIPPED"
                isPartial -> "PARTIAL"
                else -> "COMPLETED"
            }

            if (trackKey == lastProcessedTrack && outcome == lastProcessedOutcome && sessionUUID == lastProcessedSessionUUID) return
            lastProcessedTrack = trackKey
            lastProcessedOutcome = outcome
            lastProcessedSessionUUID = sessionUUID

            // Conjunto Rebobinado-1: el contador de saltos SÍ debe usar el veredicto ya
            // perdonado (`isSkipped`) — Bendecida existe para proteger el historial de un
            // accidente. Pero la racha de repetición debe usar el veredicto crudo
            // (`rawIsSkipped`) — perdonar un salto no debería fabricar una escucha que nunca
            // ocurrió. Mezclar estos dos (usar el mismo veredicto para ambos) es exactamente
            // el bug que causaba que la racha se inflara de más sin razón aparente.
            val newStreak = musicDataStore.updateSkipStreak(startSnapshot.title, startSnapshot.artist, isSkipped)
            val repeatAnalytics = musicDataStore.updateRepeatStats(startSnapshot.title, startSnapshot.artist, rawIsSkipped)
            if (!isSkipped && !isPartial) musicDataStore.updateArtistStats(startSnapshot.artist)
            
            InternalLogger.d(applicationContext, "[STREAK_TRACE] Consumidores: identity=${startSnapshot.title}|${startSnapshot.artist}, newSkipStreak=$newStreak, playsToday=${repeatAnalytics.first}, streakDays=${repeatAnalytics.second}, updateArtistStatsLlamado=${!isSkipped && !isPartial}")

            val historyItem = HistoryItem(
                title = endSnapshot.title,
                artist = endSnapshot.artist,
                album = endSnapshot.album ?: "",
                durationMs = effectiveDuration,
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
                hasPendingArtwork = !hasArtwork,
                identitySchemaVersion = MusicDataStore.CURRENT_IDENTITY_VERSION
            )
            
            InternalLogger.d(applicationContext, "[STREAK_TRACE] HistoryItem a guardar: trackKey=${historyItem.trackKey}, isSkipped=${historyItem.isSkipped}, skipStreak=${historyItem.skipStreak}, playsToday=${historyItem.playsToday}, streakDays=${historyItem.streakDays}")
            
            musicDataStore.addToHistory(historyItem)
            
            // v9.0: Refresco visual condicionado al estado de pantalla (Bloque E.2)
            if (isWidgetPotentiallyVisible()) {
                serviceScope.launch {
                    MusicWidget.updateAll(applicationContext)
                }
            } else {
                hasPendingUpdates = true
                InternalLogger.d(applicationContext, "[GATING] Commit de historial con pantalla apagada. Postergando refresco.")
            }
            
            // v9.0: HIST_REAPER (Bloque Q1)
            cleanupOrphanedArtworks()

        } catch (e: Exception) {
            Log.e(TAG, "Error en commitToHistory para ${startSnapshot.title}", e)
        }
    }


    /**
     * HIST_REAPER (v9.0): Recolector de portadas huérfanas por referencia.
     * Protege el historial vigente y la sesión activa (Regla Q1.2).
     */
    private suspend fun cleanupOrphanedArtworks() {
        withContext(Dispatchers.IO) {
            try {
                val historyDir = File(filesDir, "history")
                if (!historyDir.exists()) return@withContext

                val currentInfo = musicDataStore.musicInfoFlow.first()
                
                // 1. Construir conjunto de UUIDs protegidos
                val protectedUUIDs = mutableSetOf<String>()
                
                // - Del historial
                currentInfo.history.forEach { item ->
                    val uuid = item.artworkPath.substringAfter("art_").substringBefore(".webp")
                    if (uuid.length > 5) protectedUUIDs.add(uuid)
                }
                
                // - De la sesión activa (Now Playing)
                currentLogicalSession?.let { protectedUUIDs.add(it.sessionUUID) }
                
                // - Del limbo (Rehidratación pendiente)
                if (currentInfo.sessionUUID.isNotEmpty()) protectedUUIDs.add(currentInfo.sessionUUID)

                var examined = 0
                var deleted = 0
                var freedBytes = 0L

                historyDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("art_") && file.name.endsWith(".webp")) {
                        examined++
                        val fileName = file.name
                        val fileUUID = fileName.substringAfter("art_").substringBefore(".webp")
                        
                        // Si no está protegido (o es un hash antiguo de < v8.0), borrar
                        val isHashOldScheme = fileUUID.all { it.isDigit() || it == '-' } && fileUUID.length < 20
                        
                        if (!protectedUUIDs.contains(fileUUID) || isHashOldScheme) {
                            freedBytes += file.length()
                            if (file.delete()) deleted++
                        }
                    }
                }
                
                InternalLogger.d(applicationContext, "[HIST_REAPER] Escaneo completado: " +
                    "Examinados=$examined, Protegidos=${protectedUUIDs.size}, Borrados=$deleted, Liberado=${freedBytes/1024}KB")

            } catch (e: Exception) {
                Log.e(TAG, "Error en HIST_REAPER", e)
            }
        }
    }

    /**
     * Reconciliador de Catch-Up Histórico (PASO 3).
     * Sanea portadas pendientes cuando el widget vuelve a ser visible.
     */
    /**
     * EAGER CACHING (v9.0): Persistencia proactiva de la portada directamente a ruta final.
     * FASE B: Evaluación de Permanencia (Segundo 5).
     */
    private suspend fun persistHistoryArtworkEagerly(snapshot: MediaSnapshot, sessionUUID: String) {
        withContext(Dispatchers.IO) {
            try {
                val artworkKey = snapshot.artworkKey
                if (sessionUUID.isBlank()) {
                    InternalLogger.w(applicationContext, "[HIST_EAGER] Abortando: sessionUUID vacío para $artworkKey")
                    return@withContext
                }

                val historyDir = File(filesDir, "history")
                val artworkFile = File(historyDir, "art_${sessionUUID}.webp")
                
                // Si ya existe en disco, registramos en caché de rutas y salimos
                if (artworkFile.exists()) {
                    eagerArtworkPaths[artworkKey] = artworkFile.absolutePath
                    return@withContext
                }

                // Intentamos obtener el Bitmap de la caché de memoria (Fase A)
                val bitmap = memoryArtworkCache[snapshot.coreKey] ?: when (val source = snapshot.artworkSource) {
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
                            sessionUUID
                        )
                        eagerArtworkPaths[artworkKey] = finalPath

                        // REACTIVE UPDATE: Manejo de condición de carrera (Skip tardío)
                        // Conjunto RAM-Identica-1: added a trackKey match alongside artworkKey.
                        // Two pending history entries from the same album could share an
                        // artworkKey; without also matching trackKey (which already encodes
                        // title+artist+duration), this could update the wrong entry's artwork
                        // status.
                        val currentHistory = MusicStateProvider.current().history
                        val pendingItem = currentHistory.find { 
                            it.artworkKey == artworkKey && it.trackKey == snapshot.trackKey && it.hasPendingArtwork 
                        }

                        if (pendingItem != null) {
                            musicDataStore.updateHistoryItemArtworkStatus(
                                pendingItem.trackKey, 
                                pendingItem.timestamp, 
                                false
                            )
                            
                            // Fase D: Screen-Gated Rendering
                            if (isWidgetPotentiallyVisible()) {
                                Log.d("GLANCE_REFRESH", "Archivo guardado para $artworkKey. Solicitando updateAll a Glance.")
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
            val artworkFile = File(item.artworkPath)
            val tempFile = File("${item.artworkPath}.tmp")
            
            // v9.0: Extraer sessionUUID desde el path para logging (Regla 11: No copiar UUID)
            val sessionUUID = artworkFile.name.substringAfter("art_").substringBefore(".webp")
            InternalLogger.d(applicationContext, "[CATCH-UP] Reconciliando portada (UUID=$sessionUUID)")

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
        val bootStart = System.currentTimeMillis()
        InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_CONNECTED: Starting rehydration...")
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
            var rehydratedArtworkKey: String? = null
            val keyFile = File(filesDir, ALBUM_ART_KEY_FILE)
            if (keyFile.exists()) {
                runCatching {
                    rehydratedArtworkKey = keyFile.readText().trim().takeIf { it.isNotEmpty() }
                }
            }
            val iconKeyFile = File(filesDir, APP_ICON_KEY_FILE)
            if (iconKeyFile.exists()) {
                runCatching {
                    savedAppIconKey = iconKeyFile.readText().trim().takeIf { it.isNotEmpty() }
                }
            }
            // Conjunto Icono-Refactor-1: el tier viaja siempre junto a la clave, para que la
            // confianza del ícono ya confirmado sobreviva a un reinicio de proceso igual que
            // la propia clave ya lo hacía.
            val iconTierFile = File(filesDir, APP_ICON_TIER_FILE)
            if (iconTierFile.exists()) {
                runCatching {
                    currentIconTier = iconTierFile.readText().trim().toIntOrNull() ?: TIER_NONE
                }
            }

            InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.")
            val currentInfo = musicDataStore.musicInfoFlow.first()
            InternalLogger.d(applicationContext, "[HIST_BOOT] BOOT_DATA_READY: sessionUUID=${currentInfo.sessionUUID}")
            if (currentInfo.trackKey.isNotEmpty()) {
                // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
                val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
                lastLogicalSnapshot = recoveredSnapshot
                lastAppliedSnapshot = recoveredSnapshot
                
                val recoveredIdentity = TrackIdentity(sanitize(currentInfo.title), sanitize(currentInfo.artist))
                
                currentLogicalSession = LogicalSession(
                    sessionUUID = currentInfo.sessionUUID,
                    identity = recoveredIdentity,
                    birthSnapshot = recoveredSnapshot,
                    liveSnapshot = recoveredSnapshot,
                    frozenTrackKey = currentInfo.trackKey,
                    maxPositionMs = currentInfo.lastMaxPositionMs, // v9.0: Recuperar marca de agua
                    isProvisional = true, // Marcada como provisional (BLOQUE 6.1)
                    startedAtRealtime = android.os.SystemClock.elapsedRealtime(),
                    playbackContext = PlaybackContext(
                        durationMs = currentInfo.durationMs,
                        album = currentInfo.album,
                        artworkKey = currentInfo.artworkKey,
                        confirmedArtworkKey = rehydratedArtworkKey
                    ),
                    context = this@MusicNotificationListener
                )
                
                InternalLogger.d(applicationContext, "[HIST_BOOT] REHYDRATED: uuid=${currentInfo.sessionUUID}, identity=$recoveredIdentity")
                
                // Si la sesión estaba en el limbo, notificamos para que el HistoryWorker esté alerta
                if (currentInfo.isPendingCommit) {
                    InternalLogger.d(applicationContext, "[REHYDRATION] Sesión en el limbo recuperada (UUID=${currentInfo.sessionUUID}). Esperando reconciliación.")
                }
                
                // WARM-UP DE RAM (v5.2.5): Rescate preventivo del Disk Shield para evitar Placeholders en Now Playing
                serviceScope.launch(Dispatchers.IO) {
                    val shieldFile = File(cacheDir, DISK_SHIELD_FILE)
                    if (shieldFile.exists()) {
                        runCatching {
                            val bitmap = BitmapFactory.decodeFile(shieldFile.absolutePath)
                            if (bitmap != null) {
                                val coreKey = recoveredSnapshot.coreKey
                                memoryArtworkCache[coreKey] = bitmap
                                InternalLogger.d(applicationContext, "[REHYDRATION] RAM Warmed-up desde Disk Shield para $coreKey.")
                            }
                        }
                    }
                }

                // INIT RAM (v2.8): Sincronizamos la memoria con el disco al arrancar.
                // Conjunto Carrera-Arranque-1: antes esto corría en su propia corrutina hija,
                // sin esperarla, mientras bootGate.complete() se llamaba unas líneas más abajo
                // de todos modos — abriendo una ventana real (aunque angosta) donde
                // processSnapshot() ya podía arrancar (bootGate ya abierta) mientras la RAM
                // todavía no reflejaba la sesión rehidratada. applyEvent() es una función barata
                // (un Mutex sin contención + una comparación pura + una escritura de StateFlow,
                // sin E/S), así que esperarla aquí no añade una demora perceptible al arranque.
                MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(currentInfo, isRehydration = true, statsResolved = true))
                
                InternalLogger.d(applicationContext, "[DIAGNOSTIC] Punteros de estado y RAM rehidratados desde DataStore.")
            }

            val bootDuration = System.currentTimeMillis() - bootStart
            InternalLogger.d(applicationContext, "[HIST_BOOT] Rehidratación completada en ${bootDuration}ms. Abriendo compuerta.")
            bootGate.complete(Unit)

            val componentName = ComponentName(this@MusicNotificationListener, MusicNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName, mainHandler)
            val initialControllers = mediaSessionManager.getActiveSessions(componentName)
            updateActiveSessions(initialControllers)

            // Conjunto Reconexión-1: antes, esta llamada corría en su propia corrutina,
            // en paralelo con la que updateActiveSessions() ya había disparado unas líneas
            // arriba (vía requestRefresh -> pendingRefreshJob) — dos revisiones de la misma
            // canción, sin coordinarse, podían pisarse entre sí y dejar la portada
            // desincronizada por hasta un minuto. Usar requestRefresh aquí también asegura
            // que ambas compitan por el MISMO trabajo pendiente: la más reciente cancela
            // automáticamente a la anterior, en vez de que las dos corran a la vez.
            // VERIFICACIÓN DE ESTADO INICIAL: Refrescar si hay discrepancia inmediata
            requestRefresh(fast = true, reason = "listener_reconnected")
            
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

    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        val notification =
            sbn.notification

        // Lógica de Ascenso de Icono dirigida por eventos (Conjunto Icono-Refactor-1:
        // delegada por completo a tryPromoteAppIcon, que decide y confirma de forma atómica).
        val lastSnapshot = lastAppliedSnapshot
        if (lastSnapshot != null && sbn.packageName == lastSnapshot.packageName &&
            currentIconTier < TIER_NOTIFICATION &&
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            serviceScope.launch {
                tryPromoteAppIcon(sbn.packageName, appChanged = false)
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

    private fun purgeZombieControllers() {
        InternalLogger.d(applicationContext, "[ZOMBIE_PURGE] Ejecutando purga punitiva de callbacks.")
        controllerCallbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        controllerCallbacks.clear()
    }

    private fun updateActiveSessions(
        newControllers: List<MediaController>?
    ) {

        /*
         * Desregistramos callbacks antiguos.
         */
        purgeZombieControllers()

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
                        // REGLA v5.1: Solo el controlador activo tiene permiso de emitir
                        if (selectedController?.sessionToken != controller.sessionToken) return

                        requestRefresh(
                            reason = "metadata"
                        )
                    }

                    override fun onPlaybackStateChanged(
                        state: PlaybackState?
                    ) {
                        InternalLogger.d(applicationContext, "[DIAG_V7_RAW] state=${state?.state}, rawPos=${state?.position}, speed=${state?.playbackSpeed}, lastUpdateTime=${state?.lastPositionUpdateTime}, now=${SystemClock.elapsedRealtime()}")
                        
                        // REGLA v5.1: Solo el controlador activo tiene permiso de emitir
                        if (selectedController?.sessionToken != controller.sessionToken) return

                        // REGLA 3: Intercepción del Seek (Optimizado con Debounce y Reloj Monotónico)
                        if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                            val lastSnapshot = lastAppliedSnapshot
                            if (lastSnapshot != null && lastSnapshot.packageName == controller.packageName) {
                                val elapsed = SystemClock.elapsedRealtime() - lastSnapshot.observedAtRealtime
                                val expectedPos = lastSnapshot.projectedPositionMs()
                                val actualPos = state.position
                                
                                // Detectar cualquier salto significativo (> 800ms) solo en reproducción
                                if (Math.abs(expectedPos - actualPos) > 800) {
                                    val now = SystemClock.elapsedRealtime()
            InternalLogger.d(applicationContext, "[LYRICS_TRACE] Seek detectado (${actualPos}ms). Agrupando ráfaga...")
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

                        // REGLA v6.7: Cierre Diferido.
                        // Cuando la sesión muere, guardamos su estado en el DataStore marcándola como
                        // "pendiente de compromiso". Si la sesión no resucita tras Doze, la archivaremos tarde.
                        currentLogicalSession?.let { session ->
                            if (session.liveSnapshot.packageName == controller.packageName) {
                                serviceScope.launch {
                                    val currentInfo = musicDataStore.musicInfoFlow.first()
                                    val finalPos = session.liveSnapshot.projectedPositionMs()
                                    
                                    val pendingInfo = currentInfo.copy(
                                        isPendingCommit = true,
                                        lastMaxPositionMs = Math.max(currentInfo.lastMaxPositionMs, finalPos),
                                        isSessionActive = false,
                                        isPlaying = false
                                    )
                                    musicDataStore.saveMusicInfo(pendingInfo)
                                    
                                    MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(finalPos))
                                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                                    
                                    InternalLogger.d(applicationContext, "[FSM] Sesión interrumpida (UUID=${session.sessionUUID}). Marcada como pendiente de compromiso.")
                                }
                            }
                        }

                        requestRefresh(
                            reason =
                                "session_destroyed"
                        )
                    }
                }

            runCatching {

                controller.registerCallback(
                    callback,
                    mainHandler
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
        reason: String,
        onAfterRefresh: (suspend () -> Unit)? = null
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

                    // Conjunto Correccion-Carrera-Icono-1: acción opcional a ejecutar DESPUÉS
                    // de refreshBestSession, dentro del mismo job cancelable por
                    // pendingRefreshJob. Antes, onDisplayFullyVisible() lanzaba su propia
                    // corrutina independiente para encadenar reconcilePendingHistoryArtworks()
                    // tras su propio refresco — esa corrutina nunca pasaba por
                    // pendingRefreshJob, así que nada la cancelaba si un refresco más reciente
                    // (de cualquier otro disparador) llegaba mientras tanto, permitiendo que
                    // dos invocaciones de processSnapshot() corrieran a la vez (ver
                    // auditoria-icono-carrera-resincronizacion-ronda1.md). Ahora cualquier
                    // llamador que necesite encadenar trabajo lo pasa aquí, y hereda
                    // automáticamente la misma cancelación que ya protege a
                    // refreshBestSession.
                    onAfterRefresh?.invoke()

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
            lastAppliedSnapshot?.let { last ->
                // WARM-UP DE DESPERTAR (v5.2.5): Bloqueo de Placeholder. Rescatamos del escudo antes de emitir SessionEnded.
                serviceScope.launch(Dispatchers.IO) {
                    val shieldFile = File(cacheDir, DISK_SHIELD_FILE)
                    if (shieldFile.exists() && !memoryArtworkCache.containsKey(last.coreKey)) {
                        runCatching {
                            val bitmap = BitmapFactory.decodeFile(shieldFile.absolutePath)
                            if (bitmap != null) {
                                memoryArtworkCache[last.coreKey] = bitmap
                                InternalLogger.d(applicationContext, "[CATCH-UP] RAM Warmed-up tras desaparición de sesión: ${last.coreKey}")
                            }
                        }
                    }
                    
                    val finalPos = last.projectedPositionMs()
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

        // Conjunto Artwork-Stabilization (Fase 2): each of the next 3 duplicate-content guards
        // now also requires the confirmed artwork key to already match the incoming snapshot's.
        // Without this, a desynced artwork (e.g. after a cancelled resolution) could get stuck
        // for minutes: the content otherwise "looks identical" to what was last observed, so
        // these guards would keep discarding every update before it ever reached processSnapshot
        // — never giving the Fase 1 self-heal (artIncoherent) a chance to run.
        if (
            reason != "catch_up_render" &&
            currentLogicalSession?.playbackContext?.confirmedArtworkKey == snapshot.artworkKey &&
            snapshot.contentKey ==
            lastObservedSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastObservedSnapshot?.contentKey}")
            return
        }

        if (
            reason != "catch_up_render" &&
            currentLogicalSession?.playbackContext?.confirmedArtworkKey == snapshot.artworkKey &&
            snapshot.contentKey ==
            inFlightSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${inFlightSnapshot?.contentKey}")
            return
        }

        if (
            reason != "catch_up_render" &&
            currentLogicalSession?.playbackContext?.confirmedArtworkKey == snapshot.artworkKey &&
            snapshot.contentKey ==
            lastAppliedSnapshot
                ?.contentKey
        ) {
            InternalLogger.d(applicationContext, "[DIAG_V7_KEY] Bloqueado por contentKey duplicado. Nuevo=${snapshot.contentKey}, Anterior=${lastAppliedSnapshot?.contentKey}")
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

        val hasBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
        val rawUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        InternalLogger.d(applicationContext, "[ART_TRACE] createSnapshot: tieneBitmapEmbebido=$hasBitmap, artworkUriCruda=$rawUri")

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
        val myCoreKey = "$title|$artist".trim().lowercase()

        // FASE A: Captura Inmediata (Segundo 0)
        // Intentamos extraer y clonar el bitmap del sistema mientras está fresco.
        // v5.2.3: Se usa CoreKey como índice. UI-Only (Disk Shield). No toca el historial.
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { original ->
            if (!memoryArtworkCache.containsKey(myCoreKey)) {
                runCatching {
                    InternalLogger.d(applicationContext, "[ART_TRACE] Fase A: escribiendo para coreKey=$myCoreKey, gen_actual=$identityGenerationCounter")
                    val clone = original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
                    memoryArtworkCache[myCoreKey] = clone
                    
                    // DISK SHIELD (v5.1): Persistencia inmediata para Glance
                    serviceScope.launch(Dispatchers.IO) {
                        saveBitmapToDiskShield(clone)
                    }

                    // RETOQUE ATÓMICO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
                    currentLogicalSession?.let { session ->
                        if (session.identity.title == sanitize(title) && session.identity.artist == sanitize(artist)) {
                            serviceScope.launch(Dispatchers.IO) {
                                InternalLogger.d(applicationContext, "[ART_TRACE] Retoque Atómico: escribiendo para UUID=${session.sessionUUID}, gen_actual=$identityGenerationCounter")
                                val historyDir = File(filesDir, "history")
                                if (!historyDir.exists()) historyDir.mkdirs()
                                val artworkFile = File(historyDir, "art_${session.sessionUUID}.webp")
                                val tempFile = File(historyDir, "art_${session.sessionUUID}.tmp")
                                
                                try {
                                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        Bitmap.CompressFormat.WEBP_LOSSY
                                    } else {
                                        @Suppress("DEPRECATION")
                                        Bitmap.CompressFormat.WEBP
                                    }
                                    FileOutputStream(tempFile).use { out ->
                                        if (clone.compress(format, 80, out)) {
                                            out.flush()
                                            Files.move(tempFile.toPath(), artworkFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                                            InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Retoque Atómico: Portada persistida directamente (UUID=${session.sessionUUID})")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error en retoque atómico directo", e)
                                } finally {
                                    if (tempFile.exists()) tempFile.delete()
                                }
                            }
                        }
                    }
                    
                    InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para $title")
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
            observedAtRealtime = SystemClock.elapsedRealtime(),
            positionUpdatedAtRealtime = controller.playbackState?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            playbackSpeed = controller.playbackState?.playbackSpeed ?: 1.0f
        )
    }



    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        
        if (pkg == lastObservedSnapshot?.packageName) {
            InternalLogger.d(applicationContext, "[REACTIVE] Notificación removida para $pkg. Sincronizando sesión.")
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

    private fun sanitize(text: String): String = text.trim().lowercase()

    private suspend fun processSnapshot(
        controller: MediaController?,
        metadata: MediaMetadata?,
        rawSnapshot: MediaSnapshot,
        reason: String
    ) {
        // v8.0: Bloqueo proactivo hasta que la rehidratación termine (BLOQUE A)
        kotlinx.coroutines.withTimeoutOrNull(BOOT_GATE_TIMEOUT_MS) { bootGate.await() }
            ?: InternalLogger.w(applicationContext, "[HIST_BOOT] BOOT_GATE_TIMEOUT: procesando paquete vivo ($reason) sin estado rehidratado")

        // FILTRO DE IDENTIDAD (Conjunto Deteccion-Nativa-Apps-1): se evalúa aquí, al principio
        // absoluto de la función, antes de CUALQUIER mutación de estado (RAM, disco, eventos),
        // para que una app no seleccionada por el usuario quede completamente ignorada — ni
        // purga la caché de artwork, ni limpia el ícono en disco, ni dispara SessionEnded.
        if (!PermissionUtils.isNativeMusicApp(applicationContext, rawSnapshot.packageName)) return
        val currentBlacklist = musicDataStore.musicInfoFlow.first().blacklist
        if (currentBlacklist.contains(rawSnapshot.packageName)) return

        // NOTE (Conjunto Artwork-Stabilization Fase 3): must remain `var`, not `val`. This local
        // reference is intentionally reassigned to `newSession` right after a session replacement
        // (see line ~2194) so that downstream MusicInfo construction blocks in this same invocation
        // read the incoming song's identity, not the outgoing one. Reverting to `val` silently
        // reintroduces stale trackKey/sessionUUID on the first update of every new track.
        var session = currentLogicalSession
        
        val stateName = when(rawSnapshot.playbackState) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            else -> "OTHER(${rawSnapshot.playbackState})"
        }
        InternalLogger.d(applicationContext, "[DIAG_V5] [INTAKE] Recibido: Estado=$stateName, Track=${rawSnapshot.title}, Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}ms, Reason=$reason")

        // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---

        val currentMem = MusicStateProvider.current()

        // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
        // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
        // abortamos para proteger el estado coherente en RAM y Disco.
        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        val isDegraded = rawSnapshot.durationMs <= 0L

        if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
            val now = SystemClock.elapsedRealtime()
            val streakStart = degradedStreakStartRealtime ?: now.also { degradedStreakStartRealtime = it }
            val elapsed = now - streakStart

            if (elapsed < BUFFERING_THRESHOLD_MS) {
                InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
                return
            }

            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Umbral de ${BUFFERING_THRESHOLD_MS}ms superado. Activando estado Cargando.")
            serviceScope.launch {
                val bufferingEvent = MusicUpdateEvent.StatusUpdate(
                    isPlaying = false,
                    deviceName = currentMem.playbackDeviceName,
                    deviceType = currentMem.playbackDeviceType,
                    isBuffering = true
                )
                if (MusicStateProvider.applyEvent(bufferingEvent)) {
                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                }
            }
            return
        }
        degradedStreakStartRealtime = null

        /*
         * Creamos una nueva generación de forma atómica.
         */
        val myGeneration =
            generation.incrementAndGet()

        // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
        // Esto permite que el historial use la fuente de verdad del último estado aplicado.
        val previousLogical =
            lastLogicalSnapshot

        val previousReliable =
            lastAppliedSnapshot

        val sessionChanged = currentLogicalSession?.identity != TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        
        if (sessionChanged) {
            // Limpieza de Memoria RAM (v5.2.3): Purga basada en CoreKey
            val myCoreKey = "${sanitize(rawSnapshot.title)}|${sanitize(rawSnapshot.artist)}"
            memoryArtworkCache.keys.retainAll(setOf(myCoreKey))
        }

        val trackContentChanged = previousLogical?.artworkKey != rawSnapshot.artworkKey

        // Conjunto Letras-Duracion-Confirmada-1: una actualización cuyo único cambio real es que
        // la duración pasó de "desconocida" (<=0) a un valor real ya no puede tratarse como un
        // duplicado sin información nueva. Sin esta señal, tanto el guard de deduplicación (ver
        // más abajo) como el enrutamiento de eventos descartaban esa corrección en silencio,
        // dejando a MusicStateProvider con una duración obsoleta indefinidamente — y por lo tanto
        // sin que relaunchLyricsTicker pudiera completar nunca la búsqueda de letras pospuesta por
        // Conjunto Letras-Atomicas-12. Ver auditoria-regresion-latencia-letras-ronda1.md.
        val durationJustConfirmed = currentMem.durationMs <= 0L && rawSnapshot.durationMs > 0L

        // Paso 2.2: GUARD CLAUSE (Evita procesar snapshots redundantes en Disco)
        // REGLA VIP: Si vienes de un Catch-up, ignoramos la deduplicación para forzar el renderizado visual.
        val isCatchUp = reason == "catch_up_render"
        
        // Conjunto RAM-Identica-1: `sessionChanged` added here too. At this exact point in the
        // function, `session` still refers to the OUTGOING song (the replacement to `newSession`
        // hasn't happened yet) — if the outgoing song happens to share its artwork with the
        // incoming one (common within the same album), the raw key comparison alone wrongly says
        // "coherent," even though the incoming song is about to become a brand-new session with
        // no confirmed key of its own. Treating a genuine identity change as always incoherent
        // forces a real, fresh artwork resolution for the NEW session (using its own sessionUUID)
        // instead of silently inheriting the outgoing song's already-published artwork file.
        val artIncoherent = (session?.playbackContext?.confirmedArtworkKey != rawSnapshot.artworkKey || sessionChanged) && isWidgetPotentiallyVisible()

        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Conjunto RAM-Identica-1: added `!sessionChanged`. `trackContentChanged` only compares
        // artworkKey — two different tracks from the same album sharing artwork looked
        // "identical" to it, so a genuine song change could be silently discarded here forever,
        // with no self-heal other than a playback-state flip (pause/resume). `sessionChanged`
        // (already computed above, comparing the ACTIVE session's identity — title+artist — to
        // the incoming snapshot) is the correct signal to prevent that: reused here, not
        // recomputed, and without touching `trackContentChanged`'s own correct meaning for its
        // other uses elsewhere in this function.
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        if (!isCatchUp && !trackContentChanged && !durationJustConfirmed && !sessionChanged && !artIncoherent && 
            currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
            currentMem.isSessionActive == rawSnapshot.isSessionActive) {
            
            // REGLA B.1 (v9.1): Actualización de Verdad (Posición) previa al Redibujado.
            // Aseguramos que la marca de agua progrese aunque el refresco visual sea ignorado.
            session?.let { s ->
                s.maxPositionMs = Math.max(s.maxPositionMs, rawSnapshot.projectedPositionMs())
            }

            // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
            lastObservedSnapshot = rawSnapshot
            InternalLogger.d(applicationContext, "[DIAG_V7_RAM] Bloqueado por RAM idéntica. isPlaying=${currentMem.isPlaying}, isSessionActive=${currentMem.isSessionActive}")
            return
        }

        if (isCatchUp || artIncoherent) {
            InternalLogger.log(applicationContext, "BYPASS: Forzando actualización (Catch-up=$isCatchUp, ArtIncoherent=$artIncoherent)")
        }

        val appChanged = previousLogical?.packageName != rawSnapshot.packageName

        if (appChanged) {
            // Conjunto Icono-Sesion-Reanudada-1: antes, este bloque limpiaba
            // incondicionalmente savedAppIconKey/currentIconTier ante CUALQUIER cambio de
            // paquete respecto a la sesión lógica anterior — incluyendo el caso de una app
            // que YA tenía su ícono confirmado y simplemente reanuda reproducción tras un
            // hueco (p. ej. su notificación/sesión de MediaSession se destruyó por
            // inactividad prolongada y la app vuelve a sonar más tarde). Esa limpieza
            // destruía savedAppIconKey ANTES de que tryPromoteAppIcon pudiera comprobar su
            // propia protección "alreadyIconifiedForThisPackage" (Correccion-Carrera-
            // Icono-1): para cuando esa función se ejecutaba, el valor que necesita ya había
            // sido borrado aquí, así que esa protección nunca llegaba a activarse en este
            // escenario. Ver auditoria-icono-sesion-reanudada-ronda1.md.
            //
            // Ahora la limpieza de estado solo se aplica si el paquete entrante NO es el
            // mismo que ya tiene el ícono confirmado — es decir, solo ante un cambio de app
            // genuino, nunca ante la reanudación de la misma app.
            val alreadyIconifiedForIncomingPackage = savedAppIconKey == "${rawSnapshot.packageName}_stable"
            if (!alreadyIconifiedForIncomingPackage) {
                currentIconTier = TIER_NONE
                // REGLA: Limpieza de Iconos (Icon Fix) ante cambios de app
                saveTextToFile("", APP_ICON_KEY_FILE)
                saveTextToFile("", APP_ICON_TIER_FILE)
                savedAppIconKey = null
            }

            // Hallazgo v3.4: Limpieza preventiva de RAM en transición
            serviceScope.launch {
                MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(0L)) // Simulamos fin de sesión
            }

            // Conjunto Icono-Precalentado-1: precalentamos el registro permanente de iconos
            // para esta app EN SEGUNDO PLANO, apenas se detecta que empezó a sonar (fuera de
            // commitMutex, sin bloquear ninguna decisión de tier). Si esta app nunca se vio
            // antes, la extracción completa (PackageManager + normalización + escritura a
            // disco) ocurre aquí, con tiempo de sobra, en vez de ocurrir más tarde dentro de
            // tryPromoteAppIcon exactamente cuando hace falta un fallback (por ejemplo, si la
            // app crashea antes de que el ícono de notificación se confirme) — ese era el
            // punto donde antes competía por commitMutex con el commit de portada. No cambia
            // ninguna decisión de tier por sí mismo: solo dispara resolveStaticIcon, que ya
            // sirve el resultado desde caché si ya estaba resuelto.
            //
            // Esto sigue siendo incondicional a propósito (Conjunto Icono-Sesion-Reanudada-1
            // no lo toca): precalentar de más nunca es incorrecto (resolveStaticIcon sirve
            // desde caché si ya estaba resuelto), y SessionEnded(0L) simula el fin de la
            // sesión lógica anterior independientemente de si el ícono se reinicia o no.
            val warmupPackageName = rawSnapshot.packageName
            if (warmupPackageName.isNotBlank()) {
                serviceScope.launch {
                    val density = applicationContext.resources.displayMetrics.density
                    val targetSizePx = (14 * density).toInt()
                    IconRegistry.resolveStaticIcon(applicationContext, warmupPackageName, targetSizePx)
                }
            }
        }

        // REGLA DE PROMOCIÓN DE SESIÓN (Persistent Snapshot)
        if (sessionChanged && previousLogical != null && rawSnapshot.packageName != previousLogical.packageName) {
            val isNewSessionWeak = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING
            if (isNewSessionWeak) {
                InternalLogger.d(applicationContext, "[DIAGNOSTIC] IGNORED: Ignorando sesión débil de ${rawSnapshot.packageName}")
                return
            }
        }

        val isSameSession = session?.sessionIdentity == rawSnapshot.sessionIdentity
        
        val firstObservedAt = if (isSameSession && session != null) {
            session.startedAtRealtime // Usar el inicio real de la sesión (v9.0)
        } else {
            rawSnapshot.recordedAt
        }

        // --- PURGA DE TRANSICIÓN Y PROTECCIÓN DE HERENCIA (v9.0: Sede única en session) ---
        // Bloqueamos la herencia de marcas de agua (maxPositionMs) y assets si el título cambia.
        // Si el título entrante es nulo o distinto, el snapshot nace desde cero.
        val canInheritAssets = isSameSession && rawSnapshot.title == session?.identity?.title && rawSnapshot.title.isNotBlank()

        val snapshot = rawSnapshot.copy(
            firstObservedAt = firstObservedAt,
            artworkSource = if (canInheritAssets) (session?.birthSnapshot?.artworkSource ?: rawSnapshot.artworkSource) else rawSnapshot.artworkSource
        )

        // --- MOTOR DE TRANSICIÓN VECTORIAL (v9.0) ---
        val currentIdentity = session?.identity
        val newIdentity = TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        
        val currentProjectedPos = rawSnapshot.projectedPositionMs()
        val lastProjectedPos = previousReliable?.projectedPositionMs() ?: 0L
        
        val isPlaying = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING
        val progressFactor = if (rawSnapshot.durationMs > 0) currentProjectedPos.toFloat() / rawSnapshot.durationMs.toFloat() else 0f

        // BLOQUE 3.2: Guarda de identidad primero, heurística de posición después
        val identityChanged = currentIdentity != newIdentity
        
        // 1. Evaluamos si es un salto manual hacia atrás (Scrubbing/Rewind)
        val isManualRewind = !isCatchUp && currentProjectedPos < (lastProjectedPos - 2000L) && !identityChanged

        // 2. Evaluamos si es un resurgimiento del sistema sin cambio real de tiempo (Catch-up)
        val isCatchUpRender = if (identityChanged) false else Math.abs(currentProjectedPos - lastProjectedPos) < 1500L

        // 3. Detectamos el loop perfecto
        val isRealLoop = isPlaying && 
                         currentProjectedPos < 2000L && 
                         progressFactor > 0.95f && 
                         !identityChanged

        // Conjunto Rebobinado-1: un rebobinado o loop genuino ya no cierra la escucha —
        // solo alimenta la racha de repetición si ya se había cruzado el umbral de escucha
        // válida ANTES del salto (usando maxPositionMs, la marca de agua ya acumulada —
        // nunca la posición recién saltada, que siempre estará cerca de 0).
        // ADVERTENCIA DE DISEÑO: NO reemplaces esto por `progressFactor`. `progressFactor` mide
        // la posición del snapshot que ACABA de llegar — justo tras un rebobinado o loop, esa
        // posición siempre está cerca de 0, así que esta condición nunca se cumpliría. Se
        // necesita `maxPositionMs`: el punto más lejano alcanzado ANTES del salto. Este error
        // ya se cometió una vez en el diseño original de este bloque y fue atrapado por la
        // cláusula de alto del agente antes de implementarse — no lo repitas.
        val progressBeforeJump = if (session != null && session.playbackContext.durationMs > 0) {
            session.maxPositionMs.toFloat() / session.playbackContext.durationMs.toFloat()
        } else 0f
        val isValidRepeatReplay = (isManualRewind || isRealLoop) && progressBeforeJump > 0.4f && session?.isProvisional == false
        if (isValidRepeatReplay) {
            InternalLogger.d(applicationContext, "[STREAK_TRACE] Disparador de rebobinado: track=${rawSnapshot.title}, progressBeforeJump=$progressBeforeJump, esManualRewind=$isManualRewind, esLoop=$isRealLoop")
            serviceScope.launch {
                musicDataStore.updateRepeatStats(rawSnapshot.title, rawSnapshot.artist, isSkip = false)
                InternalLogger.d(applicationContext, "[STREAK_TRACE] Disparador de rebobinado: updateRepeatStats ejecutado para ${rawSnapshot.title}")
            }
        }

        // DECISIÓN ESTRUCTURAL: Solo rompemos la sesión si cambió la canción de verdad.
        // v9.0: Rebobinado y loop ya no cierran la escucha (Conjunto Rebobinado-1)
        // Antes de este conjunto, `isManualRewind`/`isRealLoop` también disparaban esto,
        // fragmentando una sola escucha en varias sesiones/entradas de historial. Ver
        // `isValidRepeatReplay` unas líneas arriba: ese es el reemplazo correcto para
        // "esto merece contar como una repetición" — nunca vuelvas a agregar rewind/loop
        // a esta condición para lograrlo.
        val sessionEnded = identityChanged
        
        // REGLA D.2: Monotonía de la marca de agua. Sede única: LogicalSession.
        session?.let { s ->
            if (!identityChanged) {
                s.maxPositionMs = max(s.maxPositionMs, currentProjectedPos)
            }
        }

        // INSTRUMENTACIÓN BLOQUE 3.3
        InternalLogger.d(applicationContext, "[FSM_GUARD] identityChanged=$identityChanged, " +
            "projectedPos=${currentProjectedPos}ms, rawPos=${rawSnapshot.positionMs}ms, maxPos=${session?.maxPositionMs ?: 0}ms, " +
            "delta=${currentProjectedPos - lastProjectedPos}ms, taken=${if (sessionEnded) "ENDED" else if (isCatchUpRender) "CATCHUP" else "FUSION"}")

        // BLOQUE 6.1: Manejo de Sesión Provisional (Resurrección vs Cierre Retroactivo)
        if (session != null && session.isProvisional) {
            if (!identityChanged) {
                // ESCENARIO A: Resurrección tras Doze confirmada. 
                // Adoptamos la sesión rehidratada y limpiamos el flag de provisional.
                session.isProvisional = false
                InternalLogger.d(applicationContext, "[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=${session.sessionUUID}")
            } else {
                // ESCENARIO B: Cierre Real. La canción cambió mientras el widget dormía.
                // Archivamos la sesión vieja usando el watermark persistido.
                historyChannel.trySend(HistoryEvent.CommitSession(
                    sessionUUID = session.sessionUUID,
                    birthSnapshot = session.birthSnapshot,
                    finalSnapshot = session.liveSnapshot,
                    maxPositionMs = session.maxPositionMs,
                    startedAtRealtime = session.startedAtRealtime
                ))
                InternalLogger.d(applicationContext, "[HIST_BOOT] CIERRE RETROACTIVO: uuid=${session.sessionUUID}")
                // Conjunto Letras-Atomicas-1: sin este cierre explícito, el espacio de trabajo
                // de esta sesión quedaría huérfano para siempre (nadie más guarda una referencia
                // a él una vez que currentLogicalSession pasa a null).
                session.lyricsScope.coroutineContext[Job]?.cancel()
                currentLogicalSession = null
                // Continuamos al flujo normal de creación de sesión nueva
            }
        }

        if (sessionEnded && !isCatchUpRender) {
            // AQUÍ EJECUTAMOS EL COMPROMISO ATÓMICO AL HISTORIAL (v6.5)
            currentLogicalSession?.let { session ->
                // Verificación de seguridad v7.0: Una sesión provisional nunca emite aquí
                if (session.isProvisional) return@let

                val commitEvent = HistoryEvent.CommitSession(
                    sessionUUID = session.sessionUUID,
                    birthSnapshot = session.birthSnapshot,
                    finalSnapshot = session.liveSnapshot,
                    maxPositionMs = max(session.maxPositionMs, session.liveSnapshot.projectedPositionMs()),
                    startedAtRealtime = session.startedAtRealtime
                )
                
                // BLOQUE 7.2: Detector de Atribución (Anti-Envenenamiento)
                if (commitEvent.finalSnapshot.title != session.birthSnapshot.title ||
                    commitEvent.finalSnapshot.artist != session.birthSnapshot.artist) {
                    InternalLogger.e(applicationContext, "[HIST_POISON] Identidad divergente en commit. " +
                        "birth='${session.birthSnapshot.title} / ${session.birthSnapshot.artist}' " +
                        "capsule='${commitEvent.finalSnapshot.title} / ${commitEvent.finalSnapshot.artist}' " +
                        "uuid=${session.sessionUUID}")
                }

                val res = historyChannel.trySend(commitEvent)
                
                totalEventsCounter.incrementAndGet()
                if (res.isSuccess) {
                    successCounter.incrementAndGet()
                    pendingEventsCount.incrementAndGet()
                } else {
                    failureCounter.incrementAndGet()
                }
                
                InternalLogger.d(applicationContext, "[HIST_CHANNEL] EVENT_SENT: Success=${res.isSuccess}, Failure=${res.isFailure}, Closed=${res.isClosed}, Track=${session.liveSnapshot.title}")
                if (res.isFailure) {
                    InternalLogger.e(applicationContext, "[HIST_CHANNEL] FAIL_CAUSE: ${res.exceptionOrNull()?.message}")
                }
            }
            
            purgeZombieControllers()
            
            // GENERAMOS LA NUEVA SESIÓN (Su cronómetro arranca en el constructor)
            val newContext = PlaybackContext(
                durationMs = rawSnapshot.durationMs,
                album = rawSnapshot.album,
                artworkKey = rawSnapshot.artworkKey
            )
            val newSession = LogicalSession(
                identity = newIdentity,
                birthSnapshot = rawSnapshot,
                liveSnapshot = rawSnapshot,
                frozenTrackKey = rawSnapshot.trackKey, // Capturado al nacer (v6.5)
                maxPositionMs = rawSnapshot.positionMs, // v9.0: Reset marca de agua
                playbackContext = newContext,
                context = this@MusicNotificationListener
            )
            InternalLogger.d(applicationContext, "[ART_TRACE] Sesión saliente antes de reemplazo: UUID=${session?.sessionUUID}, artworkKey=${session?.playbackContext?.artworkKey}")
            // Conjunto Letras-Atomicas-1: cerramos el espacio de trabajo de letras de la sesión
            // SALIENTE antes de reemplazarla — cualquier descarga o ciclo de letras suyo que
            // siguiera en curso muere aquí mismo, nunca puede alcanzar a escribir en la sesión
            // nueva que está a punto de nacer.
            session?.lyricsScope?.coroutineContext[Job]?.cancel()
            currentLogicalSession = newSession
            session = newSession
            identityGenerationCounter++
            InternalLogger.d(applicationContext, "[ART_TRACE] Nueva generación de identidad: gen=$identityGenerationCounter, UUID=${newSession.sessionUUID}, Track=${rawSnapshot.title}")
            InternalLogger.d(applicationContext, "[FSM] Nueva Sesión Creada (UUID=${newSession.sessionUUID}): ${rawSnapshot.title}")
            
            // BUFFER DE NACIMIENTO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
            val myCoreKey = snapshot.coreKey
            val bitmap = memoryArtworkCache[myCoreKey]
            val uuid = newSession.sessionUUID
            if (bitmap != null) {
                serviceScope.launch(Dispatchers.IO) {
                    val density = applicationContext.resources.displayMetrics.density
                    val w = (80 * density).toInt()
                    val h = (40 * density).toInt()
                    val historyPill = ImageUtils.createHorizontalPill(bitmap, w, h)
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, historyPill, uuid)
                    historyPill.recycle()
                    InternalLogger.d(applicationContext, "[FSM] Portada de nacimiento persistida (UUID=$uuid)")
                }
            }
        } else {
            // FUSIÓN DE ESTADO (v6.5): Solo actualizamos liveSnapshot y contexto
            val updatedContext = PlaybackContext(
                durationMs = rawSnapshot.durationMs,
                album = rawSnapshot.album,
                artworkKey = rawSnapshot.artworkKey,
                confirmedArtworkKey = session?.playbackContext?.confirmedArtworkKey
            )
            session?.let { s ->
                s.liveSnapshot = rawSnapshot
                s.playbackContext = updatedContext
            }
        }

        // Conjunto Letras-Atomicas-8: se aplica sea cual sea la rama (creación o fusión)
        // recién tomada arriba, porque `session` ya apunta a la sesión vigente aquí.
        if (isPlaying) {
            session?.hasConfirmedPlayback = true
        }

        // ACTUALIZACIÓN DEL DIARIO LÓGICO (Cierres-3)
        lastLogicalSnapshot = rawSnapshot
        lastAppliedSnapshot = snapshot
        lastObservedPositionMs = currentProjectedPos

        // ACTIVE WATCHER (v4.3.1): Cronómetro proactivo de 5s con LATE-READ
        if (snapshot.playbackState == PlaybackState.STATE_PLAYING && (trackContentChanged || eagerCacheJob == null)) {
            val uuid = session?.sessionUUID ?: ""
            eagerCacheJob?.cancel()
            eagerCacheJob = serviceScope.launch {
                delay(5000L)
                // Obtenemos el estado refinado (con portada cargada) tras la espera
                val refinedSnapshot = lastLogicalSnapshot ?: return@launch
                persistHistoryArtworkEagerly(refinedSnapshot, uuid)
            }
        } else if (snapshot.playbackState != PlaybackState.STATE_PLAYING) {
            // Cancelación inmediata en pausa/stop para ahorro de recursos
            eagerCacheJob?.cancel()
            eagerCacheJob = null
        }

        val isSessionEnded = sessionEnded
        // Conjunto Letras-Duracion-Confirmada-1: además del cambio de portada, una duración
        // recién confirmada también debe enrutarse como "contenido afinado" (MetadataRefinement),
        // nunca como un simple StatusUpdate (que no persiste durationMs) — ver Cambio 1.
        val isTrackContentChanged = trackContentChanged || durationJustConfirmed

        // Letras-Atomicas-11: se elimina el serviceScope.launch que diferia esta actualización.
        // processSnapshot ya es suspend; diferir esto a una corrutina aparte abría una ventana
        // entre la escritura síncrona de hasConfirmedPlayback/lastLogicalSnapshot (arriba) y la
        // actualización de la verdad oficial (MusicStateProvider) más la decisión de relanzar el
        // ticker de letras — ventana en la que otro procesamiento concurrente podía leer un
        // MusicStateProvider.current().isPlaying desactualizado. Ejecutarlo en línea garantiza que
        // ambas cosas queden sincronizadas antes de que este procesamiento continúe.
        mutationMutex.withLock {
            val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
            val currentInfo = musicDataStore.musicInfoFlow.first()
            
            // Conjunto Corrección-Carrera-Stats-1 (Ronda 2): esta sección es la vía rápida, sin
            // espera de portada — ya no necesita calcular estos 3 campos con cuidado, porque
            // reconcileNewSession (MusicStateProvider.kt) los ignora salvo que el emisor confirme
            // statsResolved=true (ver commitMutex, más abajo, que es quien sí lo hace). Se
            // preserva el último valor conocido solo para que memInfo quede internamente
            // coherente; el valor real llega y se aplica solo por la vía de commitMutex.
            val (plays, skip, freq) = Triple(currentMem.playsToday, currentMem.skipStreak, currentMem.isFrequentArtist)
            
            val memInfo = MusicInfo(
                title = snapshot.title,
                artist = snapshot.artist,
                packageName = snapshot.packageName,
                album = snapshot.album ?: "",
                trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                artworkKey = currentInfo.artworkKey,
                artworkUri = snapshot.artworkUri ?: currentInfo.artworkUri,
                appIconKey = currentInfo.appIconKey,
                isPlaying = isPlaying,
                isSessionActive = snapshot.isSessionActive,
                currentLyric = if (!isSessionEnded) currentMem.currentLyric else "",
                lyricsTrackKey = if (!isSessionEnded) currentMem.lyricsTrackKey else "",
                playbackDeviceName = snapshot.playbackDeviceName,
                playbackDeviceType = snapshot.playbackDeviceType,
                durationMs = snapshot.durationMs,
                history = currentInfo.history,
                playsToday = plays,
                skipStreak = skip,
                isFrequentArtist = freq,
                lastUpdateEpoch = currentMem.lastUpdateEpoch,
                observedAtRealtime = currentMem.observedAtRealtime,
                sessionUUID = session?.sessionUUID ?: "",
                isPendingCommit = false,
                lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
            )
            
            val event = if (isSessionEnded) {
                MusicUpdateEvent.NewSession(memInfo)
            } else if (isTrackContentChanged) {
                MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying)
            } else {
                MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType, isBuffering = false)
            }

            if (MusicStateProvider.applyEvent(event)) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
            
            if (isSessionEnded) {
                relaunchLyricsTicker("identity_change")
            } else {
                val stateChangedUI = currentMem.isPlaying != isPlaying
                // Conjunto Letras-Duracion-Confirmada-1: la confirmación de duración por sí sola
                // (sin cambio de isPlaying) debe relanzar el motor de letras — es exactamente la
                // señal que Conjunto Letras-Atomicas-12 dejó pendiente de emitir.
                if (stateChangedUI || durationJustConfirmed) relaunchLyricsTicker("state_sync")
            }
        }
        // Solo guardamos de forma anticipada si el widget NO es visible (gating activo).
        // Si es visible, dejamos que el Stage 2 maneje la persistencia final para evitar race conditions.
        if (!isWidgetPotentiallyVisible()) {
            serviceScope.launch {
                val currentInfo = musicDataStore.musicInfoFlow.first()
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val oldCanKeepLyric = snapshot.isSessionActive && snapshot.trackKey == currentInfo.lyricsTrackKey
                val canKeepLyric = snapshot.isSessionActive &&
                    currentInfo.lyricsTrackKey.isNotBlank() &&
                    MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                        MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso6_canKeepLyricStage1: viejo=$oldCanKeepLyric, nuevo=$canKeepLyric, coincide=${oldCanKeepLyric == canKeepLyric}")
                
                val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                val logicalMusicInfo = MusicInfo(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    packageName = snapshot.packageName,
                    trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
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
                    sessionUUID = session?.sessionUUID ?: "",
                    isPendingCommit = false, // Reconciliación exitosa (v6.7)
                    lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                )
                val changed = musicDataStore.saveMusicInfo(logicalMusicInfo, forceUpdate = false)
                if (changed) {
                    lastCommittedInfo = logicalMusicInfo
                }
            }
        }

        // --- STAGE 2: PRESENTACIÓN (BLOQUEO POR COMPUERTA) ---

        if (!isWidgetPotentiallyVisible()) {
            // NOTE (Conjunto B.2, decision recorded — do not "fix" without checking with the
            // project owner first): artwork resolution is intentionally skipped entirely while the
            // screen is off, including cases where a corrected artwork would otherwise reach the
            // history. This is a deliberate battery-saving tradeoff, not an oversight.
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

        val oldTrackChangedUI = previousLogical?.trackKey != snapshot.trackKey
        val trackChangedUI = 
            previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)
        InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=$oldTrackChangedUI, nuevo=$trackChangedUI, coincide=${oldTrackChangedUI == trackChangedUI}, track=${snapshot.title}")

        // Identidad de negocio para decidir si hace falta re-buscar la letra (Conjunto Letras-3).
        // Evita cancelar una búsqueda o descartar una letra ya cargada solo porque trackKey
        // cambió por una corrección tardía de duración.
        val songChangedForLyrics = previousLogical == null ||
            MusicDataStore.computeSessionIdentity(previousLogical.packageName, previousLogical.title, previousLogical.artist) !=
                MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist)

        val appChangedUI = 
            previousLogical?.packageName != snapshot.packageName

        val stateChangedUI = 
            previousLogical?.playbackState != snapshot.playbackState

        val artworkChangedUI =
            previousLogical?.artworkKey != snapshot.artworkKey

                InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")

        try {

            // 1. Resolución de recursos visuales (Fase Cancelable).
            var resolvedArtwork: Bitmap? = null

            // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
            // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
            var artworkTimedOut = false

            // REGLA Artwork-3: Captura de generación para protección de identidad
            val genAlIniciarResolucion = identityGenerationCounter

            // REGLA Artwork-1: Resolución dirigida por incoherencia (evita ráfagas CPU)
            if (controller != null && metadata != null && artIncoherent) {
                
                // A. Portada (v6.3 Pipeline Unificado)
                resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
                    resolveArtworkDeduplicated(
                        snapshot = snapshot,
                        controller = controller,
                        metadata = metadata,
                        generation = myGeneration
                    )
                } ?: run {
                    artworkTimedOut = true
                    null
                }
                InternalLogger.d(applicationContext, "[ART_TRACE] Resolución terminada: Exito=${resolvedArtwork != null}, gen_actual=$identityGenerationCounter, UUID_actual=${currentLogicalSession?.sessionUUID}, Track_actual=${currentLogicalSession?.identity?.title}")
            }

            // B. Icono de app (Conjunto Icono-Refactor-1: delegado a tryPromoteAppIcon, que
            // decide y confirma de forma atómica bajo commitMutex — ya no hay variables locales
            // que transportar hasta el bloque de commit de más abajo).
            if (controller != null && metadata != null && 
                (appChangedUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION)) {
                tryPromoteAppIcon(snapshot.packageName, appChanged = appChangedUI)
            }

            // 1.5 GESTIÓN DE LETRAS (Independiente de la imagen para evitar desfases en pausa)
            if (songChangedForLyrics) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                currentLyricsIdentity = null

                // Conjunto Letras-Atomicas-1: se lanza en el espacio de trabajo de la sesión
                // recién creada (currentLogicalSession ya es la nueva en este punto de la
                // función) — si esta canción termina antes de que la descarga responda, muere
                // con ella, nunca puede escribir su resultado sobre la canción siguiente.
                lyricsFetchJob = currentLogicalSession?.lyricsScope?.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)

                    // Conjunto Letras-Atomicas-5: releemos el estado real justo antes de
                    // consultar, en vez de usar el snapshot capturado antes del debounce —
                    // la duración pudo resolverse recién durante esta espera.
                    val freshInfo = MusicStateProvider.current()
                    val result = lyricsRepository.getLyrics(freshInfo.trackKey, freshInfo.artist, freshInfo.title, freshInfo.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        currentLyricsIdentity = MusicDataStore.computeSessionIdentity(freshInfo.packageName, freshInfo.title, freshInfo.artist)
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive && freshInfo.durationMs > 0L) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        // Conjunto Letras-Duracion-Confirmada-1: este "no encontrado" solo es
                        // definitivo cuando la duración ya estaba confirmada al consultar. Si
                        // durationMs<=0, LyricsRepository pospuso la consulta a propósito
                        // (Atomicas-12/9) y el null NO significa "no existe" — borrar el widget
                        // aquí silenciaría una canción que sí tiene letra, solo que aún no se pudo
                        // confirmar su duración. En ese caso no se toca el widget: los Cambios 1-5
                        // garantizan el reintento en cuanto la duración se confirme.
                        InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(MusicDataStore.computeSessionIdentity(freshInfo.packageName, freshInfo.title, freshInfo.artist), "")
                    }
                }
            } else {
                // Conjunto Letras-Atomicas-2: eliminamos la antigua rama "trackChangedUI" — nunca
                // pudo ejecutarse (trackChangedUI y songChangedForLyrics eran exactamente la misma
                // fórmula, así que si songChangedForLyrics ya dio false, trackChangedUI también).
                // La decisión de reutilizar la letra ya cargada en vez de re-buscarla ahora vive
                // centralizada dentro de relaunchLyricsTicker (ver Cambio 3) — no repartida aquí.
                // Cualquier evento que no sea un cambio real de canción entra a este único camino.
                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=${currentLyrics == null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")

                val effectivePos = previousLogical?.projectedPositionMs() ?: 0L
                val drift = Math.abs(effectivePos - snapshot.projectedPositionMs())

                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${currentLyrics == null}")
                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true

                if (shouldResync) {
                    relaunchLyricsTicker("state_sync")
                }
            }

            // Conjunto RAM-Identica-1: added an identity check alongside the artwork-key match.
            // A result was previously accepted as "still relevant" just because the artwork key
            // matched — but two different tracks from the same album share that key too, so a
            // stale resolution could be misapplied to a brand-new song. Now it also requires the
            // title+artist identity of this invocation's own snapshot to still match what the
            // rest of the system last observed.
            val isStillRelevant = snapshot.artworkKey == lastObservedSnapshot?.artworkKey &&
                lastObservedSnapshot?.let {
                    MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                        MusicDataStore.computeSessionIdentity(it.packageName, it.title, it.artist)
                } == true
            if (myGeneration != generation.get() && !isStillRelevant) {
                Log.d(TAG, "[DIAGNOSTIC] ABORT_EARLY: #$myGeneration is obsolete (current gen: ${generation.get()})")
                return
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                commitMutex.withLock {

                    // Conjunto RAM-Identica-1: same identity check as ABORT_EARLY above, for the
                    // same reason — artwork key alone isn't enough to confirm this is still the
                    // same song when two different tracks can share it.
                    val isStillRelevantInLock = snapshot.artworkKey == lastObservedSnapshot?.artworkKey &&
                        lastObservedSnapshot?.let {
                            MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                                MusicDataStore.computeSessionIdentity(it.packageName, it.title, it.artist)
                        } == true
                    if (myGeneration != generation.get() && !isStillRelevantInLock) {
                        Log.d(TAG, "[DIAGNOSTIC] ABORT_IN_LOCK: #$myGeneration is obsolete (current gen: ${generation.get()})")
                        return@withLock
                    }

                    // Conjunto Artwork-Stabilization (Fase 1): `artIncoherent` was added to this
                    // OR condition. Without it, if the FIRST attempt to write a track's artwork
                    // got cancelled (e.g. a newer MediaSession event arrived mid-resolution),
                    // no LATER invocation of the same track would ever retry — trackChangedUI
                    // and artworkChangedUI only detect a difference from the PREVIOUS in-memory
                    // snapshot, not from what's actually confirmed on disk. `artIncoherent`
                    // checks against disk directly, so it's the only condition here that lets
                    // the system self-heal a desynced artwork without waiting for the next song.
                    if (controller != null && metadata != null && 
                        (trackChangedUI || artworkChangedUI || session?.playbackContext?.confirmedArtworkKey == null || artIncoherent)) {
                        
                        if (resolvedArtwork != null) {
                            // Hallazgo v3.9: Warm-up de RAM (Zero-Lag)
                            // Inyectamos el bitmap en la caché compartida para que Glance lo lea a 0ms.
                            // SEGURIDAD IPC (v4.5): Escalado de cortesía para el bus Binder.
                            val transportBitmap = scaleForTransport(resolvedArtwork)
                            val cacheKey = "${rawSnapshot.artworkKey}_raw"
                            MusicWidget.bitmapCache.put(cacheKey, transportBitmap)

                            // Paso 3.2: CACHING DE TRANSFORMACIÓN
                            if (session?.playbackContext?.confirmedArtworkKey != snapshot.artworkKey) {
                                // 1. Guardar versión RAW
                                InternalLogger.d(applicationContext, "[ART_TRACE] Escribiendo archivo sincronizado: key=${snapshot.artworkKey}, UUID=${session?.sessionUUID}")
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_RAW_FILE, applyPillTransform = false)
                                
                                // 2. Guardar versión WIDGET (Píldora)
                                saveBitmapToFile(resolvedArtwork, ALBUM_ART_FILE, applyPillTransform = true)
                                
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE)
                                session?.let { it.playbackContext = it.playbackContext.copy(confirmedArtworkKey = snapshot.artworkKey) }

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
                            session?.let { it.playbackContext = it.playbackContext.copy(confirmedArtworkKey = null) }
                        }
                    }

                    val currentInfo = musicDataStore.musicInfoFlow.first()
                    val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                    val canKeepLyric = snapshot.isSessionActive &&
                        currentInfo.lyricsTrackKey.isNotBlank() &&
                        MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                            MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                    
                    val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
                    // Preservar currentInfo.lyricsTrackKey (ya en formato sessionIdentity), nunca
                    // snapshot.trackKey (incluye duración) (Conjunto Letras-Atomicas-3).
                    val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""

                    val songStats = musicDataStore.getStatsFor(snapshot.title, snapshot.artist)

                    // REGLA Artwork-3: Guardar imagen resuelta con verificación de identidad síncrona
                    val (finalArtworkKey, finalArtworkUri) = if (artIncoherent) {
                        val currentUUID = currentLogicalSession?.sessionUUID
                        val uri = if (resolvedArtwork != null && identityGenerationCounter == genAlIniciarResolucion && currentUUID != null) {
                            ArtworkStorageManager.saveHistoryArtwork(applicationContext, resolvedArtwork, currentUUID)
                        } else if (run {
                                val oldMatch = snapshot.trackKey == currentInfo.trackKey
                                val newMatch = MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                                    MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
                                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso2_artworkFallback: viejo=$oldMatch, nuevo=$newMatch, coincide=${oldMatch == newMatch}, track=${snapshot.title}")
                                newMatch
                            }) {
                            currentInfo.artworkUri
                        } else {
                            ""
                        }
                        snapshot.artworkKey to uri
                    } else {
                        currentInfo.artworkKey to currentInfo.artworkUri
                    }

                    InternalLogger.d(applicationContext, "[ART_TRACE] Decisión final de portada: artIncoherent=$artIncoherent, gen=$identityGenerationCounter, UUID=${currentLogicalSession?.sessionUUID}, valorElegido=$finalArtworkUri")

                    val finalMusicInfo = MusicInfo(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        packageName = snapshot.packageName,
                        album = snapshot.album ?: "",
                        trackKey = session?.frozenTrackKey ?: snapshot.trackKey, // Usar identidad física congelada (v6.5)
                        artworkKey = finalArtworkKey,
                        artworkUri = finalArtworkUri,
                        appIconKey = savedAppIconKey ?: "",
                        isPlaying = isPlaying,
                        isSessionActive = snapshot.isSessionActive,
                        currentLyric = finalLyric,
                        lyricsTrackKey = finalLyricKey,
                        playbackDeviceName = snapshot.playbackDeviceName,
                        playbackDeviceType = snapshot.playbackDeviceType,
                        durationMs = snapshot.durationMs,
                        history = currentInfo.history,
                        playsToday = songStats.playsToday,
                        skipStreak = songStats.skipStreak,
                        isFrequentArtist = songStats.isFrequentArtist,
                        // Conjunto Identidad-Atómica-Presentación-1: antes este campo nunca se
                        // fijaba aquí y quedaba en su valor por defecto (0) — el sello de racha de
                        // repetición diaria nunca se disparaba en la tarjeta "sonando ahora".
                        streakDays = songStats.streakDays,
                        sessionUUID = session?.sessionUUID ?: "",
                        isPendingCommit = false,
                        lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs
                    )

                    // 1. Sincronía Atómica: Disco -> RAM -> UI
                    val changedDisco = musicDataStore.saveMusicInfo(finalMusicInfo, forceUpdate = isCatchUp || artIncoherent || artworkTimedOut)
                    InternalLogger.d(applicationContext, "[ART_TRACE] Persistencia confirmada: artworkUri_guardado=${musicDataStore.musicInfoFlow.first().artworkUri}")
                    
                    if (artworkTimedOut) {
                        Log.w(TAG, "[ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.")
                    }
                    
                    // Hallazgo v3.9: Warm-up de RAM ya inyectado en bitmapCache
                    // REGLA DE ORO (v4.0): El Árbitro reconcilia el commit de disco
                    // Conjunto Corrección-Carrera-Stats-1 (Ronda 2): esta es la única vía que
                    // calcula playsToday/skipStreak/isFrequentArtist con getStatsFor (línea de
                    // arriba) — es la fuente autorizada, así que confirma statsResolved=true.
                    val changedRAM = MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(finalMusicInfo, statsResolved = true))

                    // Conjunto Letras-Duracion-Confirmada-1: esta es la vía que más confiablemente
                    // termina corrigiendo la duración (finalMusicInfo.durationMs siempre viene de
                    // snapshot.durationMs, sin condiciones), pero hasta ahora nunca avisaba al
                    // motor de letras (ninguna llamada a relaunchLyricsTicker vive dentro de este
                    // bloque). Se relanza aquí también, sin depender de que Stage 1 ya lo haya
                    // detectado — las dos vías quedan simétricas e independientes.
                    if (currentInfo.durationMs <= 0L && finalMusicInfo.durationMs > 0L) {
                        relaunchLyricsTicker("duration_confirmed")
                    }

                    // PROMOCIÓN DE IDENTIDAD (v2.8): Ahora que el disco tiene la imagen y la llave,
                    // sincronizamos la RAM al 100% para mostrar el nuevo artwork.
                    
                    if (changedDisco || isSessionEnded || changedRAM) {
                        if (isSessionEnded) {
                            uiUpdateFlow.tryEmit(UpdateEvent.IdentityChange(snapshot.trackKey))
                        } else {
                            uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                        }
                    }
                    
                    lastObservedSnapshot = snapshot
                    lastCommittedInfo = MusicStateProvider.current()
                }
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "[DIAGNOSTIC] CANCELLED: #$myGeneration aborted during resolution")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error en pipeline atatomic #$myGeneration", e)
        } finally {
            if (inFlightSnapshot?.contentKey == snapshot.contentKey) {
                inFlightSnapshot = null
            }
        }
    }

    private fun relaunchLyricsTicker(reason: String) {
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${currentLyrics == null}")
        if (!isWidgetPotentiallyVisible()) {
            synchronized(lyricsLock) {
                lyricsUpdateJob?.cancel()
            }
            return
        }
        val currentInfo = MusicStateProvider.current()
        if (currentInfo.isEmpty || !currentInfo.isSessionActive) {
            synchronized(lyricsLock) {
                lyricsUpdateJob?.cancel()
            }
            return
        }
        InternalLogger.d(applicationContext, "[LYRICS_TRACE] relaunchLyricsTicker: Reason=$reason | Track=${currentInfo.title}")

        val activeSession = currentLogicalSession ?: return

        // Conjunto Letras-Atomicas-2: identidad de negocio de lo que suena AHORA. Es la única
        // llave que decide si podemos reutilizar `currentLyrics` sin tocar red/disco. Capturamos
        // ambos valores aquí (fuera de la corrutina) para no leer variables de clase que puedan
        // cambiar mientras la corrutina está suspendida.
        val targetIdentity = MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
        val cachedLyrics = currentLyrics
        val canReuse = cachedLyrics != null && currentLyricsIdentity == targetIdentity
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Decisión de datos: reused=$canReuse, targetIdentity=$targetIdentity, cachedIdentity=$currentLyricsIdentity")

        synchronized(lyricsLock) {
            lyricsUpdateJob?.cancel()
            lyricsUpdateJob = activeSession.lyricsScope.launch {
                val lyricsRes = if (canReuse && cachedLyrics != null) {
                    // Ya tenemos la letra correcta para esta identidad exacta: nos ahorramos el
                    // viaje a disco/red. Esto es lo que vuelve inofensivo que varios disparadores
                    // (Stage 1, sincronización pasiva, screen_wake, seek_event) llamen a esta
                    // función casi al mismo tiempo para el mismo evento — todos convergen aquí sin
                    // competir por una descarga lenta que terminan cancelándose entre sí.
                    cachedLyrics
                } else {
                    lyricsRepository.getLyrics(
                        currentInfo.trackKey, currentInfo.artist, currentInfo.title, currentInfo.durationMs
                    )?.also {
                        currentLyrics = it
                        currentLyricsIdentity = targetIdentity
                    } ?: return@launch
                }
                if (currentInfo.isPlaying) {
                    runLyricsShowcase(targetIdentity, lyricsRes)
                } else {
                    runPausedLyricsCycle(targetIdentity, lyricsRes)
                }
            }
        }
    }

    private suspend fun runLyricsShowcase(myTrackKey: String, lyricsRes: LyricsResult) {
        val snappinessOffset = 500L

        while (currentCoroutineContext().isActive) {
            val currentRAM = MusicStateProvider.current()
            // REGLA DE IDENTIDAD DUAL: Si la sesión física (Karaoke) cambió, abortamos
            val oldZombieCheck = currentRAM.trackKey != myTrackKey
            val newZombieCheck = MusicDataStore.computeSessionIdentity(currentRAM.packageName, currentRAM.title, currentRAM.artist) != myTrackKey
            InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_zombieDetector: viejo=$oldZombieCheck, nuevo=$newZombieCheck, coincide=${oldZombieCheck == newZombieCheck}")
            if (newZombieCheck || !currentRAM.isPlaying) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Zombie Detector: Clave discordante. Cancelando Ticker.")
                lyricsUpdateJob?.cancel()
                break
            }
            
            // Usamos el Snapshot Lógico para el cálculo de posición real
            val snapshot = lastLogicalSnapshot ?: break
            val currentPos = snapshot.projectedPositionMs()
            
            val entry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= (currentPos + snappinessOffset) }
            
            if (entry != null) {
                updateLyricInWidget(myTrackKey, entry.text)
            }

            val entryIdx = lyricsRes.allEntries.indexOf(entry)
            val next = if (entryIdx != -1 && entryIdx < lyricsRes.allEntries.size - 1) lyricsRes.allEntries[entryIdx + 1] else null
            
            // Hallazgo v4.1: Regla Unificada de Silencio (Conjunto Letras-2)
            // Un solo umbral decide cuándo alternar a mostrar el artista, tanto si el
            // próximo verso conocido tarda de más como si ya no queda ningún verso más.
            if (next != null) {
                val waitTime = (next.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                
                if (waitTime > LYRICS_SILENCE_THRESHOLD_MS) {
                    delay(LYRICS_SILENCE_THRESHOLD_MS)
                    val oldSilenceCheck = MusicStateProvider.current().trackKey == myTrackKey
                    val newSilenceCheck = MusicDataStore.computeSessionIdentity(MusicStateProvider.current().packageName, MusicStateProvider.current().title, MusicStateProvider.current().artist) == myTrackKey
                    InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_silencio: viejo=$oldSilenceCheck, nuevo=$newSilenceCheck, coincide=${oldSilenceCheck == newSilenceCheck}")
                    if (currentCoroutineContext().isActive && newSilenceCheck) {
                        updateLyricInWidget(myTrackKey, "")
                    }
                    delay((waitTime - LYRICS_SILENCE_THRESHOLD_MS).coerceAtLeast(100L))
                } else {
                    delay(waitTime)
                }
            } else if (entryIdx != -1) {
                // Ya no queda ningún verso más, pero la canción sigue sonando: aplicamos
                // la misma regla de silencio antes de ceder el lugar al nombre del artista.
                delay(LYRICS_SILENCE_THRESHOLD_MS)
                val oldSilenceCheck = MusicStateProvider.current().trackKey == myTrackKey
                val newSilenceCheck = MusicDataStore.computeSessionIdentity(MusicStateProvider.current().packageName, MusicStateProvider.current().title, MusicStateProvider.current().artist) == myTrackKey
                InternalLogger.d(applicationContext, "[IDENTITY_TRACE] Paso3_silencio: viejo=$oldSilenceCheck, nuevo=$newSilenceCheck, coincide=${oldSilenceCheck == newSilenceCheck}")
                if (currentCoroutineContext().isActive && newSilenceCheck) {
                    updateLyricInWidget(myTrackKey, "")
                }
                break
            } else if (lyricsRes.allEntries.isNotEmpty()) {
                // Todavía no llega ninguna línea (intro largo, currentPos está antes de la
                // primera marca de tiempo). En vez de rendirse, programamos la espera exacta
                // hasta que llegue, en trozos del mismo umbral de silencio ya establecido, para
                // seguir revisando la identidad de la sesión mientras tanto (Conjunto
                // Letras-Atomicas-4).
                val firstEntry = lyricsRes.allEntries.first()
                val waitTime = (firstEntry.timestampMs - (currentPos + snappinessOffset)).coerceAtLeast(100L)
                delay(waitTime.coerceAtMost(LYRICS_SILENCE_THRESHOLD_MS))
            } else {
                break
            }
        }
    }

    private suspend fun runPausedLyricsCycle(myTrackKey: String, lyricsRes: LyricsResult) {
        var showLyric = true
        while (currentCoroutineContext().isActive) {
            // Conjunto Letras-Atomicas-10: espera de asentamiento antes de leer el estado.
            // Evidencia (auditoria-log-diagnostico-showcase-ronda1.md + log crudo del
            // 2026-09-25): el teléfono a veces envía un evento "aún no confirmado" y su
            // corrección real con 27-75ms de diferencia. relaunchLyricsTicker puede lanzar
            // este ciclo con una lectura de isPlaying ya obsoleta justo antes de que llegue
            // la corrección. Esta espera le da margen a la cancelación cooperativa de
            // Kotlin para interrumpir aquí mismo, antes de escribir nada en el widget, si
            // la corrección (isPlaying real) llega dentro de este margen.
            delay(300L)
            val currentRAM = MusicStateProvider.current()
            val currentSessionId = MusicDataStore.computeSessionIdentity(currentRAM.packageName, currentRAM.title, currentRAM.artist)
            if (currentSessionId != myTrackKey || currentRAM.isPlaying) break
            
            val pausedPos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
            
            var lastEntry = lyricsRes.allEntries.lastOrNull { it.timestampMs <= pausedPos }
            val fallbackFired = lastEntry == null && pausedPos < 5000L && currentLogicalSession?.hasConfirmedPlayback == true
            // Conjunto Letras-Atomicas-8: antes, esta regla también se disparaba durante
            // Estado=OTHER (carga), mostrando la primera línea antes de que sonara audio.
            if (fallbackFired) {
                lastEntry = lyricsRes.allEntries.firstOrNull()
            }

            val text = if (showLyric && lastEntry != null) lastEntry.text else ""
            updateLyricInWidget(myTrackKey, text)
            
            showLyric = !showLyric
            delay(150000L) // Conjunto Letras-Atomicas-1: restaurado a 2.5 min, valor original de diseño
        }
    }

    private fun updateLyricInWidget(trackKey: String, lyric: String) {
        // Relevo Atómico (v4.0)
        serviceScope.launch {
            val applied = MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))
            InternalLogger.d(applicationContext, "[LYRICS_WIDGET_WRITE] trackKey=$trackKey, lyric=\"$lyric\", applied=$applied")
            if (applied) {
                uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }

            // Conjunto Instrumentacion-Letras-Display-1: mide, para cada canción, cuánto tardó en
            // aparecer su PRIMERA letra real desde que la sesión nació — el dato que hace falta
            // para validar en campo la corrección de auditoria-regresion-latencia-letras-ronda1.md
            // sin tener que reconstruir la cuenta a mano desde varios logs distintos.
            if (applied && lyric.isNotBlank()) {
                val session = currentLogicalSession
                if (session != null && session.sessionIdentity == trackKey && !session.firstLyricDisplayLogged) {
                    session.firstLyricDisplayLogged = true
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - session.startedAtRealtime
                    InternalLogger.d(applicationContext, "[LYRICS_DISPLAY_TRACE] Primera letra mostrada: track=${session.identity.title}, artist=${session.identity.artist}, sessionUUID=${session.sessionUUID}, elapsedDesdeInicioSesionMs=$elapsedMs, lyric=\"$lyric\"")
                }
            }
        }
    }

    /**
     * Conjunto Icono-Refactor-1: única función responsable de decidir y confirmar el ícono
     * de la app activa. Sustituye tanto la lógica de ascenso que antes vivía duplicada en
     * onNotificationPosted como la resolución que antes hacía resolveAppIcon junto con el
     * commit separado en processSnapshot.
     *
     * Todo el ciclo "leer el tier actual -> resolver un candidato -> decidir si es mejor ->
     * escribir" ocurre atómicamente dentro de commitMutex: ninguna otra parte de la clase lee
     * o escribe savedAppIconKey/currentIconTier fuera de esta función, con la única excepción
     * del reset en processSnapshot cuando appChanged es true (Conjunto Icono-Sesion-
     * Reanudada-1: ese reset dejó de ser incondicional — solo se ejecuta si el paquete
     * entrante no coincide con el que ya tiene ícono confirmado, ver
     * alreadyIconifiedForIncomingPackage en processSnapshot). Sigue siendo una limpieza de
     * estado ante un cambio de app genuino, no una decisión de qué ícono usar, y por lo tanto
     * no participa de la misma carrera que esta función resuelve.
     */
    private suspend fun tryPromoteAppIcon(packageName: String, appChanged: Boolean) {
        commitMutex.withLock {
            // Conjunto Correccion-Carrera-Icono-1 (blindaje defensivo): `appChanged` es una
            // bandera capturada por el LLAMADOR, en processSnapshot, ANTES de la espera (hasta
            // 3.5s, ARTWORK_PROMOTION_TIMEOUT_MS) por la resolución de portada — no antes de
            // adquirir este candado. Si mientras tanto otra invocación más rápida ya resolvió
            // y confirmó el ícono correcto para este mismo packageName (guardándolo en
            // savedAppIconKey), la bandera capturada por esta invocación quedó desactualizada:
            // ya no describe la realidad en el instante en que efectivamente tomamos el
            // candado. Reconfirmamos aquí, con el estado ya persistido (fresco), si el reset
            // sigue siendo necesario — ver auditoria-icono-carrera-resincronizacion-ronda1.md.
            val alreadyIconifiedForThisPackage = savedAppIconKey == "${packageName}_stable"
            val shouldReset = appChanged && !alreadyIconifiedForThisPackage

            if (shouldReset) {
                savedAppIconKey = null
                currentIconTier = TIER_NONE
                saveTextToFile("", APP_ICON_KEY_FILE)
                saveTextToFile("", APP_ICON_TIER_FILE)
            }

            val density = applicationContext.resources.displayMetrics.density
            val targetSizePx = (14 * density).toInt()

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
            // Conjunto Instrumentacion-IconTier-2: registra por cuál de las 3 estrategias se
            // encontró (o no) la notificación de origen — necesario para validar técnicamente por
            // qué una sesión termina o no en TIER_NOTIFICATION, sin adivinar a partir del
            // resultado final.
            var matchStrategy = if (mediaNotif != null) "token" else "ninguna_todavia"

            // 1.2 Fallback: Match por PackageName + MediaSession Extra
            if (mediaNotif == null) {
                mediaNotif = notifications.firstOrNull {
                    it.packageName == packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                }
                if (mediaNotif != null) matchStrategy = "package_mas_extra_mediasession"
            }

            // 1.3 Fallback final: Match por PackageName
            if (mediaNotif == null) {
                mediaNotif = notifications.firstOrNull { it.packageName == packageName }
                if (mediaNotif != null) matchStrategy = "solo_package"
            }
            if (mediaNotif == null) matchStrategy = "ninguna"

            val smallIconDisponible = mediaNotif?.notification?.smallIcon != null
            val iconFromNotif = mediaNotif?.notification?.smallIcon?.loadDrawable(this)?.toBitmap()
            // Conjunto Instrumentacion-IconTier-2: evidencia cruda de lo que la notificación
            // activa realmente ofrecía en este intento, independiente de si terminó promoviendo
            // el tier o no (eso ya lo cubre ICON_TIER_TRACE, Cambio B1).
            InternalLogger.d(applicationContext, "[ICON_MEDIASESSION_TRACE] package=$packageName, notificacionesActivas=${notifications.size}, estrategiaMatch=$matchStrategy, notificacionEncontrada=${mediaNotif != null}, smallIconDisponible=$smallIconDisponible, drawableCargado=${iconFromNotif != null}")

            val candidate: Pair<Bitmap, Int>? = if (iconFromNotif != null) {
                Bitmap.createScaledBitmap(iconFromNotif, targetSizePx, targetSizePx, true) to TIER_NOTIFICATION
            } else {
                // PRIORIDAD 2/3: delegadas al registro permanente por paquete.
                IconRegistry.resolveStaticIcon(applicationContext, packageName, targetSizePx)
            }

            // Conjunto Instrumentacion-IconTier-1: nombre legible para no tener que memorizar los
            // enteros 0-3 de TIER_NONE/COLOR/MONOCHROME/NOTIFICATION al leer los logs.
            fun tierName(t: Int) = when (t) {
                TIER_NOTIFICATION -> "NOTIFICATION"
                TIER_MONOCHROME -> "MONOCHROME"
                TIER_COLOR -> "COLOR"
                else -> "NONE"
            }
            val tierBefore = currentIconTier
            val sessionForLog = currentLogicalSession

            if (candidate != null && (shouldReset || candidate.second > currentIconTier)) {
                val (icon, tier) = candidate
                val iconKey = "${packageName}_stable"
                saveBitmapToFile(icon, APP_ICON_FILE)
                saveTextToFile(iconKey, APP_ICON_KEY_FILE)
                saveTextToFile(tier.toString(), APP_ICON_TIER_FILE)
                savedAppIconKey = iconKey
                currentIconTier = tier

                val currentInfo = musicDataStore.musicInfoFlow.first()
                if (currentInfo.packageName == packageName) {
                    val updated = currentInfo.copy(appIconKey = iconKey)
                    musicDataStore.saveMusicInfo(updated)

                    val changed = MusicStateProvider.applyEvent(MusicUpdateEvent.ArtworkResolved(
                        trackKey = currentInfo.trackKey,
                        artworkKey = currentInfo.artworkKey,
                        iconKey = iconKey
                    ))
                    if (changed) {
                        uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                    }
                }
                InternalLogger.d(applicationContext, "[DIAGNOSTIC] ICON_ASCENT: Icono confirmado en tier=$tier para $packageName")
                InternalLogger.d(applicationContext, "[ICON_TIER_TRACE] Promovido: sessionUUID=${sessionForLog?.sessionUUID}, track=${sessionForLog?.identity?.title}, artist=${sessionForLog?.identity?.artist}, package=$packageName, tierAntes=${tierName(tierBefore)}, tierDespues=${tierName(tier)}, appChanged=$appChanged, resetAplicado=$shouldReset")
            } else {
                InternalLogger.d(applicationContext, "[ICON_TIER_TRACE] Sin promoción: sessionUUID=${sessionForLog?.sessionUUID}, track=${sessionForLog?.identity?.title}, artist=${sessionForLog?.identity?.artist}, package=$packageName, tierActual=${tierName(tierBefore)}, tierCandidato=${candidate?.second?.let { tierName(it) } ?: "sin_candidato"}, appChanged=$appChanged, resetAplicado=$shouldReset")
            }
        }
    }

    private fun scaleForTransport(bitmap: Bitmap): Bitmap {
        val maxTransportSize = 384
        if (bitmap.width <= maxTransportSize && bitmap.height <= maxTransportSize) return bitmap
        
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val (newWidth, newHeight) = if (ratio > 1f) {
            maxTransportSize to (maxTransportSize / ratio).toInt().coerceAtLeast(1)
        } else {
            (maxTransportSize * ratio).toInt().coerceAtLeast(1) to maxTransportSize
        }
        
        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private suspend fun resolveArtworkDeduplicated(
        snapshot: MediaSnapshot,
        controller: MediaController? = null,
        metadata: MediaMetadata? = null,
        generation: Long = -1L // -1 indica que se ignora la validación de generación (v6.3)
    ): Bitmap? {
        val artworkKey = snapshot.artworkKey
        val isVisible = isWidgetPotentiallyVisible()
        InternalLogger.d(applicationContext, "[ART_TRACE] Iniciando resolución para: gen=$identityGenerationCounter, UUID=${currentLogicalSession?.sessionUUID}, Track=${snapshot.title}")
        InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Intentando resolución. Track=${snapshot.title}, Visible=$isVisible")
        
        artworkCache.get(artworkKey)?.let { bitmap ->
            InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Cache HIT en RAM. Key=$artworkKey")
            return bitmap
        }
        val deferred = getOrCreateArtworkDeferred(snapshot, controller, metadata, generation)
        return deferred.await()
    }

    private suspend fun getOrCreateArtworkDeferred(
        snapshot: MediaSnapshot,
        controller: MediaController?,
        metadata: MediaMetadata?,
        generation: Long
    ): Deferred<Bitmap?> {
        val artworkKey = snapshot.artworkKey
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
                    val bitmap = findRealAlbumArt(snapshot, controller, metadata)
                    
                    // Solo guardamos en caché si la sesión sigue siendo relevante para esta generación
                    // o si es una petición de historial (generation == -1)
                    // Conjunto RAM-Identica-1: same identity check as the two ABORT guards in
                    // processSnapshot — `artworkKey` alone can't distinguish two different tracks
                    // from the same album that happen to share it.
                    val isStillRelevant = artworkKey == lastObservedSnapshot?.artworkKey &&
                        lastObservedSnapshot?.let {
                            MusicDataStore.computeSessionIdentity(snapshot.packageName, snapshot.title, snapshot.artist) ==
                                MusicDataStore.computeSessionIdentity(it.packageName, it.title, it.artist)
                        } == true
                    val isHistoryRescue = generation == -1L
                    
                    if (isActive && (generation == this@MusicNotificationListener.generation.get() || isStillRelevant || isHistoryRescue) && bitmap != null) {
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
        snapshot: MediaSnapshot,
        controller: MediaController?,
        metadata: MediaMetadata?
    ): Bitmap? = withContext(Dispatchers.IO) {
        val minArtDimension = MIN_ART_DIMENSION
        val targetTitle = snapshot.title
        val artworkKey = snapshot.artworkKey
        
        InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] findRealAlbumArt START: $targetTitle")
        
        // 1. Intentar desde metadatos vivos (solo si el título coincide)
        metadata?.let { meta ->
            val metaTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (metaTitle == targetTitle) {
                meta.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { bitmap ->
                    if (isValidArtwork(bitmap, minArtDimension)) {
                        InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de METADATA_KEY_ART")
                        return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
                    }
                }
                meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { bitmap ->
                    if (isValidArtwork(bitmap, minArtDimension)) {
                        InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de METADATA_KEY_ALBUM_ART")
                        return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
                    }
                }
            }
        }

        // 2. Intentar desde notificaciones activas
        try {
            val notifications = getActiveNotifications()
            val mediaNotification = notifications.firstOrNull { sbn ->
                sbn.packageName == snapshot.packageName && 
                sbn.notification.category == Notification.CATEGORY_TRANSPORT &&
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == targetTitle
            }
            if (mediaNotification != null) {
                mediaNotification.notification.getLargeIcon()?.loadDrawable(this@MusicNotificationListener)?.toBitmap()?.let {
                    if (isValidArtwork(it, minArtDimension)) {
                        InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de NOTIFICACIÓN (LargeIcon)")
                        return@withContext ensureMaxDimension(it, MAX_ART_DIMENSION)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo consultando notificación activa", e)
        }

        // 3. Resolución asíncrona de URI
        snapshot.artworkUri?.takeIf { it.isNotBlank() }?.let { uri ->
            decodeAlbumArtUri(uri)?.let { bitmap ->
                if (isValidArtwork(bitmap, minArtDimension)) {
                    InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de URI: $uri")
                    return@withContext bitmap
                }
            }
        }

        // 4. FALLBACK v6.3: Bóveda de Reserva (Fase A / Rehydration)
        // Usamos CoreKey para recuperar el Bitmap independientemente del refinamiento de duración.
        val myCoreKey = snapshot.coreKey
        val fallbackBitmap = memoryArtworkCache[myCoreKey]
        
        fallbackBitmap?.let { bitmap ->
            if (isValidArtwork(bitmap, minArtDimension)) {
                InternalLogger.d(applicationContext, "[ARTWORK_RESOLVE] Obtenido de BÓVEDA DE RESERVA (CoreKey: $myCoreKey)")
                return@withContext ensureMaxDimension(bitmap, MAX_ART_DIMENSION)
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
        // Conjunto Portada-Fuente-Unica-1: la traducción de URIs conocidas (Spotify, etc.)
        // ahora vive únicamente en ArtworkUriResolver — ver ese archivo para el porqué.
        val resolvedUri = ArtworkUriResolver.resolveKnownUri(uriString)

        // Conjunto Saneamiento-ArtworkUri-1: "artworkUri" no siempre contiene una URI real —
        // ver auditoria-conflacion-artworkuri-identidad-ronda1.md. Cuando una app no entrega
        // ninguna portada por ningún medio (metadata, bitmap embebido, ícono de notificación
        // útil — el caso confirmado de YouTube) y el servicio se reinicia en el momento
        // exacto, este mismo campo puede terminar cargando la clave interna de identidad
        // ("paquete|título|artista|álbum") en vez de una URI. Esta función es la única puerta
        // de entrada real a un intento de lectura/descarga — findRealAlbumArt,
        // reconcilePendingHistoryArtworks, y cualquier llamador futuro pasan siempre por
        // aquí — así que blindarla en este único punto cierra la clase completa de este
        // error para cualquier app, presente o futura, sin que cada llamador tenga que
        // aprender a reconocer el caso especial por su cuenta.
        if (!ArtworkUriResolver.isFetchableUri(resolvedUri)) {
            InternalLogger.d(applicationContext, "[ART_TRACE] decodeAlbumArtUri: valor no es una URI descargable/legible, se omite sin intentarlo. Valor=$resolvedUri")
            return null
        }

        if (resolvedUri.startsWith("http://") || resolvedUri.startsWith("https://")) {
            return downloadBitmapFromUrl(resolvedUri)
        }
        return try {
            contentResolver.openInputStream(Uri.parse(resolvedUri))?.use { input ->
                decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Fallo al decodificar URI: $resolvedUri", e)
            null
        }
    }

    private suspend fun downloadBitmapFromUrl(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        // Conjunto Portada-Reintento-Red-1: esta es la única rama de findRealAlbumArt que hace
        // una descarga de red real (a diferencia de los pasos que leen metadata embebida o la
        // notificación activa, donde la ausencia de bitmap significa que genuinamente no existe,
        // no que algo haya fallado). Un fallo aquí puede ser transitorio (blip de red), así que
        // vale la pena un reintento corto y acotado — ver diseno-reintento-descarga-portada-1.md.
        repeat(ARTWORK_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
                connection.readTimeout = NETWORK_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.doInput = true
                connection.useCaches = true
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "HTTP ${connection.responseCode} descargando artwork (intento ${attempt + 1}/$ARTWORK_DOWNLOAD_MAX_ATTEMPTS)")
                } else {
                    connection.inputStream.use { input ->
                        if (!isActive) return@withContext null
                        val bitmap = decodeSampledBitmapFromStream(input, MAX_ART_DIMENSION, MAX_ART_DIMENSION)
                        if (bitmap != null) return@withContext bitmap
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is java.net.SocketException && !isActive) {
                    // Silently ignore
                } else {
                    Log.e(TAG, "Fallo descargando artwork (intento ${attempt + 1}/$ARTWORK_DOWNLOAD_MAX_ATTEMPTS): $urlString", e)
                }
            } finally {
                connection?.disconnect()
            }
            if (attempt < ARTWORK_DOWNLOAD_MAX_ATTEMPTS - 1) {
                delay(ARTWORK_DOWNLOAD_RETRY_DELAY_MS)
            }
        }
        null
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
                    Log.d(TAG, "[ART_SYNC_TRACE] Archivo escrito y confirmado: fileName=$fileName, texto=$text, timestamp=${System.currentTimeMillis()}")
                } catch (_: Exception) {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Log.d(TAG, "[ART_SYNC_TRACE] Archivo escrito y confirmado: fileName=$fileName, texto=$text, timestamp=${System.currentTimeMillis()}")
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
        artworkCache.evictAll()
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
        private const val APP_ICON_TIER_FILE = "app_icon.tier"
        private const val MIN_ART_DIMENSION = 100
        private const val MAX_ART_DIMENSION = 800
        private const val BUFFERING_THRESHOLD_MS = 8000L
        private const val LYRICS_SILENCE_THRESHOLD_MS = 10000L
    private const val NORMAL_DEBOUNCE_MS = 150L
        private const val FAST_DEBOUNCE_MS = 100L
        private const val METADATA_STABILIZATION_MS = 400L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 2000
        private const val NETWORK_READ_TIMEOUT_MS = 2000
        private const val ARTWORK_CACHE_SIZE_KB = 8 * 1024
        private const val ARTWORK_TIMEOUT_MS = 7000L
        // Conjunto Portada-Reintento-Red-1: antes ARTWORK_PROMOTION_TIMEOUT_MS (3500ms) era
        // MENOR que el peor caso de un solo intento de descarga (hasta 6000ms con los timeouts
        // anteriores de 3000+3000) — el límite externo cortaba la descarga antes de que ella
        // misma pudiera fallar limpiamente por su propio timeout. Ampliado a 6000ms para dar
        // espacio real a los 2 intentos más cortos definidos abajo. Ver diseno-reintento-descarga-
        // portada-1.md para el razonamiento completo de cada cifra.
        private const val ARTWORK_PROMOTION_TIMEOUT_MS = 6000L
        private const val ARTWORK_DOWNLOAD_MAX_ATTEMPTS = 2
        private const val ARTWORK_DOWNLOAD_RETRY_DELAY_MS = 500L
        // Conjunto Portada-Fuente-Unica-1: SPOTIFY_MEDIA_API_PREFIX/SPOTIFY_CDN_PREFIX se
        // movieron a ArtworkUriResolver.kt, única fuente de verdad para esta traducción.
        private const val DISK_SHIELD_FILE = "current_artwork_raw.webp"
    }

    private suspend fun saveBitmapToDiskShield(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val file = File(cacheDir, DISK_SHIELD_FILE)
            try {
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(file).use { out ->
                    bitmap.compress(format, 90, out)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al escribir Disk Shield", e)
            }
        }
    }
}
