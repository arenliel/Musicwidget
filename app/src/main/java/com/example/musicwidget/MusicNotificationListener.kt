package arenliel.musicwidget

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Bundle
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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * MOTOR DE INTEGRIDAD DE SESIÓN (v9.5 - "The Reaper")
 * 
 * Orquestador central de la identidad musical. Implementa un modelo de estados
 * estricto (FSM) para garantizar la atomicidad del historial y la coherencia visual.
 */
class MusicNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var musicDataStore: MusicDataStore
    private lateinit var lyricsRepository: LyricsRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val historyHandler = CoroutineExceptionHandler { _, exception ->
        InternalLogger.e(applicationContext, "HistoryWorker Fatal: ${exception.message}")
    }

    private val fileMutex = Mutex()
    private val artworkInFlightMutex = Mutex()
    private val commitMutex = Mutex()
    private val mutationMutex = Mutex()

    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    
    private var pendingRefreshJob: Job? = null
    private var eagerCacheJob: Job? = null
    
    private var hasPendingUpdates = false

    private val eagerArtworkPaths = ConcurrentHashMap<String, String>()
    private val memoryArtworkCache = ConcurrentHashMap<String, Bitmap>()
    private val iconVault = mutableMapOf<String, Pair<Bitmap, Int>>()

    private data class TrackIdentity(
        val title: String,
        val artist: String
    ) {
        val coreKey: String get() = "$title|$artist"
        
        companion object {
            fun from(snapshot: MediaSnapshot) = TrackIdentity(
                title = snapshot.title.trim().lowercase(),
                artist = snapshot.artist.trim().lowercase()
            )
        }
    }

    private data class PlaybackContext(
        val durationMs: Long,
        val album: String?,
        val artworkKey: String
    )

    private data class LogicalSession(
        val sessionUUID: String = java.util.UUID.randomUUID().toString(),
        val identity: TrackIdentity,
        val birthSnapshot: MediaSnapshot,
        var liveSnapshot: MediaSnapshot,
        val frozenTrackKey: String,
        var maxPositionMs: Long,
        var isProvisional: Boolean = false,
        val startedAtRealtime: Long = android.os.SystemClock.elapsedRealtime(),
        var playbackContext: PlaybackContext,
        val context: Context
    ) {
        val sessionIdentity: String get() = "${birthSnapshot.packageName}|${identity.title}|${identity.artist}"
    }

    private var currentLogicalSession: LogicalSession? = null
    private var selectedController: MediaController? = null

    private var cachedAudioDeviceName: String = "Altavoz del teléfono"
    private var cachedAudioDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { syncPlaybackDevice() }
        override fun onAudioDevicesRemoved(addedDevices: Array<out AudioDeviceInfo>?) { syncPlaybackDevice() }
    }

    private var currentLyrics: LyricsResult? = null
    private var lyricsUpdateJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private var unlockPollingJob: Job? = null

    private val seekEventFlow = MutableSharedFlow<Triple<MediaSnapshot, Long, Long>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private sealed class UpdateEvent {
        data class IdentityChange(val trackKey: String) : UpdateEvent()
        object StatusUpdate : UpdateEvent()
    }

    private val uiUpdateFlow = MutableSharedFlow<UpdateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var lastObservedSnapshot: MediaSnapshot? = null
    private var lastAppliedSnapshot: MediaSnapshot? = null
    private var lastLogicalSnapshot: MediaSnapshot? = null
    private var inFlightSnapshot: MediaSnapshot? = null

    @Volatile
    private var isPresentationDirty: Boolean = false
    private var pendingSnapshot: MediaSnapshot? = null

    private var lastProcessedTrack: String? = null
    private var lastProcessedOutcome: String? = null
    private var lastProcessedSessionUUID: String? = null
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

    private val historyChannel = Channel<HistoryEvent>(capacity = Channel.UNLIMITED)
    private val bootGate = CompletableDeferred<Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val successCounter = AtomicInteger(0)
    private val failureCounter = AtomicInteger(0)
    private val totalEventsCounter = AtomicInteger(0)
    private val pendingEventsCount = AtomicInteger(0)
    private val resurrectionsCount = AtomicInteger(0)

    private val generation = AtomicLong(0)
    private val artworkInFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    private var savedArtworkKey: String? = null
    private var savedAppIconKey: String? = null
    private var currentIconTier: Int = TIER_NONE

    private val dynamicScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> InternalLogger.d(applicationContext, "[GATING] Screen ON")
                Intent.ACTION_SCREEN_OFF -> {
                    InternalLogger.d(applicationContext, "[GATING] Screen OFF. Limpiando presentación.")
                    onDisplayBecameUnavailable()
                }
                Intent.ACTION_USER_PRESENT -> {
                    InternalLogger.d(applicationContext, "[GATING] User Present. Triggering refresh.")
                    onDisplayFullyVisible()
                }
            }
        }
    }

    private fun isWidgetPotentiallyVisible(): Boolean {
        val am = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return am.isInteractive
    }

    private fun onDisplayBecameUnavailable() {
        lyricsUpdateJob?.cancel()
    }

    private fun onDisplayFullyVisible() {
        InternalLogger.d(applicationContext, "[GATING] Display fully visible. Triggering Wake-up Sync.")
        if (hasPendingUpdates) {
            hasPendingUpdates = false
            serviceScope.launch { MusicWidget.updateAll(applicationContext) }
        }
        serviceScope.launch {
            refreshBestSession(reason = "catch_up_render")
            reconcilePendingHistoryArtworks()
        }
    }

    private var lastPreviewUpdate: Long = 0L

    private val artworkCache = object : LruCache<String, Bitmap>(ARTWORK_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
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
        val playbackDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    ) {
        val sessionIdentity: String get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)
        val trackKey: String get() = "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
        val artworkKey: String get() = artworkUri?.takeIf { it.isNotBlank() } ?: trackKey
        val coreKey: String get() = "${MusicDataStore.normalize(title)}|${MusicDataStore.normalize(artist)}"
        val contentKey: String get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"

        fun projectedPositionMs(nowRealtime: Long = SystemClock.elapsedRealtime()): Long {
            if (playbackState != PlaybackState.STATE_PLAYING) return positionMs
            val delta = nowRealtime - observedAtRealtime
            val projected = positionMs + delta
            return if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected.coerceAtLeast(0L)
        }

        companion object {
            fun fromPersisted(info: MusicInfo) = MediaSnapshot(
                packageName = info.packageName,
                title = info.title,
                artist = info.artist,
                album = info.album,
                mediaId = null,
                artworkUri = info.artworkUri,
                playbackState = if (info.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                isSessionActive = info.isSessionActive,
                playbackDeviceName = info.playbackDeviceName,
                playbackDeviceType = info.playbackDeviceType,
                durationMs = info.durationMs,
                positionMs = info.lastMaxPositionMs,
                observedAtRealtime = info.observedAtRealtime
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        InternalLogger.init(this)
        InternalLogger.d(this, "SERVICE_LIFECYCLE: onCreate - Process started")
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        musicDataStore = MusicDataStore(applicationContext)
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
            serviceScope.launch {
                musicDataStore.updatePlaybackDevice(name, type)
                val current = MusicStateProvider.current()
                val changed = MusicStateProvider.applyEvent(MusicUpdateEvent.StatusUpdate(current.isPlaying, name, type))
                if (changed) uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startUiUpdateDispatcher() {
        serviceScope.launch {
            uiUpdateFlow.debounce(NORMAL_DEBOUNCE_MS).collect { event ->
                if (!isWidgetPotentiallyVisible()) {
                    hasPendingUpdates = true
                    lyricsUpdateJob?.cancel()
                    return@collect
                }
                if (lyricsUpdateJob?.isActive != true && currentLyrics != null) relaunchLyricsTicker("screen_wake")
                InternalLogger.d(applicationContext, "[DIAGNOSTIC] UI_DISPATCHER: Ejecutando actualización atómica de Glance (Event=$event)")
                runCatching { MusicWidget.updateAll(applicationContext) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSeekEventProcessor() {
        serviceScope.launch {
            seekEventFlow.debounce(METADATA_STABILIZATION_MS).collect { (snapshot, position, detectedAt) ->
                val now = SystemClock.elapsedRealtime()
                val lag = now - detectedAt
                val updatedSnapshot = snapshot.copy(positionMs = position + lag, observedAtRealtime = now)
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
            musicDataStore.musicInfoFlow.map { it.blacklist }.distinctUntilChanged().collect {
                refreshBestSession("blacklist_updated")
            }
        }
    }

    private fun startHistoryWorker() {
        serviceScope.launch(Dispatchers.IO + historyHandler) {
            for (event in historyChannel) {
                pendingEventsCount.decrementAndGet()
                if (event is HistoryEvent.CommitSession) {
                    val obs = android.os.SystemClock.elapsedRealtime() - event.startedAtRealtime
                    if (obs >= 5000L || event.maxPositionMs >= 5000L) {
                        commitToHistory(event.sessionUUID, event.birthSnapshot, event.finalSnapshot, event.maxPositionMs)
                    }
                }
            }
        }
    }

    private suspend fun commitToHistory(sessionUUID: String, startSnapshot: MediaSnapshot, endSnapshot: MediaSnapshot, maxPositionMs: Long) {
        try {
            val historyDir = File(filesDir, "history")
            if (!historyDir.exists()) historyDir.mkdirs()
            val trackKey = endSnapshot.trackKey
            val artworkFile = File(historyDir, "art_${sessionUUID}.webp")
            
            if (!artworkFile.exists()) {
                val res = resolveArtworkDeduplicated(startSnapshot, gen = -1L)
                if (res != null) {
                    val density = applicationContext.resources.displayMetrics.density
                    val pill = ImageUtils.createHorizontalPill(res, (80 * density).toInt(), (40 * density).toInt())
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, pill, sessionUUID)
                    pill.recycle()
                }
            }

            val hasArtwork = artworkFile.exists()
            val dur = when {
                endSnapshot.durationMs > 0 -> endSnapshot.durationMs
                startSnapshot.durationMs > 0 -> startSnapshot.durationMs
                else -> musicDataStore.musicInfoFlow.first().durationMs
            }
            val progress = if (dur > 0) maxPositionMs.toFloat() / dur.toFloat() else -1f
            var isSkipped = progress in 0.0f..0.4f
            val isBlessed = MusicStateProvider.current().history.any { it.trackKey == trackKey && !it.isSkipped }
            if (isBlessed && isSkipped) isSkipped = false

            val outcome = when { isSkipped -> "SKIPPED"; progress < 0.85f -> "PARTIAL"; else -> "COMPLETED" }
            if (trackKey == lastProcessedTrack && outcome == lastProcessedOutcome && sessionUUID == lastProcessedSessionUUID) return
            lastProcessedTrack = trackKey; lastProcessedOutcome = outcome; lastProcessedSessionUUID = sessionUUID

            val newStreak = musicDataStore.updateSkipStreak(startSnapshot.title, startSnapshot.artist, isSkipped)
            val repeat = musicDataStore.updateRepeatStats(startSnapshot.title, startSnapshot.artist, isSkipped)
            if (!isSkipped && progress >= 0.85f) musicDataStore.updateArtistStats(startSnapshot.artist)

            val item = HistoryItem(
                title = endSnapshot.title, artist = endSnapshot.artist, album = endSnapshot.album ?: "",
                durationMs = dur, packageName = endSnapshot.packageName, artworkPath = artworkFile.absolutePath,
                artworkKey = endSnapshot.artworkKey, trackKey = trackKey, timestamp = System.currentTimeMillis(),
                isSkipped = isSkipped, skipStreak = newStreak, playsToday = repeat.first, streakDays = repeat.second,
                artworkUri = if (hasArtwork) Uri.fromFile(artworkFile).toString() else (endSnapshot.artworkUri ?: ""),
                hasPendingArtwork = !hasArtwork, identitySchemaVersion = MusicDataStore.CURRENT_IDENTITY_VERSION
            )
            musicDataStore.addToHistory(item)
            if (isWidgetPotentiallyVisible()) serviceScope.launch { MusicWidget.updateAll(applicationContext) }
            else hasPendingUpdates = true
            cleanupOrphanedArtworks()
        } catch (e: Exception) { Log.e(TAG, "Error en commitToHistory", e) }
    }

    private fun cleanupOrphanedArtworks() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val history = musicDataStore.musicInfoFlow.first().history
                val protected = history.map { File(it.artworkPath).name }.toSet() + "current_artwork_raw.webp"
                val dir = File(filesDir, "history")
                if (dir.exists()) {
                    dir.listFiles()?.forEach { if (it.name !in protected && it.name.endsWith(".webp")) it.delete() }
                }
            } catch (e: Exception) { Log.e(TAG, "Error en HIST_REAPER", e) }
        }
    }

    private suspend fun persistHistoryArtworkEagerly(snapshot: MediaSnapshot, sessionUUID: String) {
        withContext(Dispatchers.IO) {
            try {
                if (sessionUUID.isBlank()) return@withContext
                val file = File(File(filesDir, "history"), "art_${sessionUUID}.webp")
                if (file.exists()) return@withContext
                val bitmap = memoryArtworkCache[snapshot.coreKey] ?: when (val s = snapshot.artworkSource) {
                    is ArtworkSource.Bitmap -> s.bitmap
                    is ArtworkSource.Uri -> if (isWidgetPotentiallyVisible()) decodeAlbumArtUri(s.uri) else null
                    else -> null
                }
                if (bitmap != null) {
                    val density = applicationContext.resources.displayMetrics.density
                    val pill = ImageUtils.createHorizontalPill(bitmap, (80 * density).toInt(), (40 * density).toInt())
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, pill, sessionUUID)
                    pill.recycle()
                }
            } catch (e: Exception) { Log.e(TAG, "Error eager cache", e) }
        }
    }

    private suspend fun reconcilePendingHistoryArtworks() {
        val pending = MusicStateProvider.current().history.filter { it.hasPendingArtwork }
        pending.forEach { item ->
            val bitmap = if (item.artworkUri.isNotBlank()) decodeAlbumArtUri(item.artworkUri) else null
            if (bitmap != null) {
                val density = applicationContext.resources.displayMetrics.density
                val pill = ImageUtils.createHorizontalPill(bitmap, (80 * density).toInt(), (40 * density).toInt())
                val uuid = File(item.artworkPath).name.substringAfter("art_").substringBefore(".webp")
                ArtworkStorageManager.saveHistoryArtwork(applicationContext, pill, uuid)
                musicDataStore.updateHistoryItemArtworkStatus(item.trackKey, item.timestamp, false)
                pill.recycle()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_CONNECTED")
        ContextCompat.registerReceiver(this, dynamicScreenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        serviceScope.launch {
            val keyFile = File(filesDir, ALBUM_ART_KEY_FILE)
            if (keyFile.exists()) savedArtworkKey = keyFile.readText().trim().takeIf { it.isNotEmpty() }
            val info = musicDataStore.musicInfoFlow.first()
            if (info.trackKey.isNotEmpty()) {
                val snap = MediaSnapshot.fromPersisted(info)
                lastLogicalSnapshot = snap; lastAppliedSnapshot = snap
                currentLogicalSession = LogicalSession(
                    sessionUUID = info.sessionUUID, identity = TrackIdentity(sanitize(info.title), sanitize(info.artist)),
                    birthSnapshot = snap, liveSnapshot = snap, frozenTrackKey = info.trackKey,
                    maxPositionMs = info.lastMaxPositionMs, isProvisional = true, context = this@MusicNotificationListener,
                    playbackContext = PlaybackContext(info.durationMs, info.album, info.artworkKey)
                )
                serviceScope.launch { MusicStateProvider.applyEvent(MusicUpdateEvent.NewSession(info)) }
            }
            bootGate.complete(Unit)
            val name = ComponentName(this@MusicNotificationListener, MusicNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, name, mainHandler)
            updateActiveSessions(mediaSessionManager.getActiveSessions(name))
            refreshBestSession("listener_reconnected")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        serviceScope.launch {
            val last = lastAppliedSnapshot
            if (last != null && sbn.packageName == last.packageName && currentIconTier < TIER_NOTIFICATION && notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                notification.smallIcon?.loadDrawable(this@MusicNotificationListener)?.toBitmap()?.let { icon ->
                    val density = applicationContext.resources.displayMetrics.density
                    val norm = Bitmap.createScaledBitmap(icon, (14 * density).toInt(), (14 * density).toInt(), true)
                    commitMutex.withLock {
                        if (currentIconTier < TIER_NOTIFICATION) {
                            saveBitmapToFile(norm, APP_ICON_FILE)
                            savedAppIconKey = "${sbn.packageName}_stable"
                            saveTextToFile(savedAppIconKey!!, APP_ICON_KEY_FILE)
                            currentIconTier = TIER_NOTIFICATION
                            val info = musicDataStore.musicInfoFlow.first()
                            if (info.packageName == sbn.packageName) {
                                musicDataStore.saveMusicInfo(info.copy(appIconKey = savedAppIconKey!!))
                                if (MusicStateProvider.applyEvent(MusicUpdateEvent.ArtworkResolved(info.trackKey, info.artworkKey, savedAppIconKey))) {
                                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun purgeZombieControllers() {
        InternalLogger.d(applicationContext, "[ZOMBIE_PURGE] Ejecutando purga punitiva de callbacks.")
        controllerCallbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        controllerCallbacks.clear()
    }

    private fun updateActiveSessions(newControllers: List<MediaController>?) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentActive = audioManager.isMusicActive
        if (newControllers.isNullOrEmpty()) {
            if (!currentActive) selectedController = null
            return
        }
        val best = selectBestController(newControllers)
        if (best != selectedController) {
            selectedController = best
            best?.let { c ->
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(m: MediaMetadata?) { serviceScope.launch { refreshBestSession("metadata_changed") } }
                    override fun onPlaybackStateChanged(p: PlaybackState?) { serviceScope.launch { refreshBestSession("playback_state") } }
                    override fun onSessionDestroyed() {
                        if (selectedController?.sessionToken == c.sessionToken) selectedController = null
                        currentLogicalSession?.let { sess ->
                            if (sess.liveSnapshot.packageName == c.packageName) {
                                serviceScope.launch {
                                    val info = musicDataStore.musicInfoFlow.first()
                                    val pos = sess.liveSnapshot.projectedPositionMs()
                                    musicDataStore.saveMusicInfo(info.copy(isPendingCommit = true, lastMaxPositionMs = max(info.lastMaxPositionMs, pos), isSessionActive = false, isPlaying = false))
                                    MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(pos))
                                    uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                                }
                            }
                        }
                        serviceScope.launch { refreshBestSession("session_destroyed") }
                    }
                }
                c.registerCallback(cb)
                controllerCallbacks[c] = cb
                serviceScope.launch { refreshBestSession("new_controller_selected") }
            }
        }
    }

    private fun requestRefresh(force: Boolean, reason: String) {
        pendingRefreshJob?.cancel()
        pendingRefreshJob = serviceScope.launch {
            delay(if (force) 50L else 300L)
            refreshBestSession(reason)
        }
    }

    private suspend fun refreshBestSession(reason: String) {
        val name = ComponentName(this, MusicNotificationListener::class.java)
        val active = mediaSessionManager.getActiveSessions(name)
        if (active.isEmpty()) {
            lastLogicalSnapshot?.let { last ->
                val pos = last.projectedPositionMs()
                if (MusicStateProvider.applyEvent(MusicUpdateEvent.SessionEnded(pos))) uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
            }
            selectedController = null
            return
        }
        val best = selectBestController(active) ?: return
        val meta = best.metadata ?: return
        val snap = createSnapshot(best, meta) ?: return
        if (reason != "catch_up_render" && (snap.contentKey == lastObservedSnapshot?.contentKey || snap.contentKey == inFlightSnapshot?.contentKey || snap.contentKey == lastAppliedSnapshot?.contentKey)) return
        processSnapshot(best, meta, snap, reason)
    }

    private fun selectBestController(controllers: List<MediaController>): MediaController? {
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.firstOrNull()
    }

    private fun createSnapshot(controller: MediaController, metadata: MediaMetadata): MediaSnapshot? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = controller.playbackState?.position ?: 0L
        val core = "$title|$artist".trim().lowercase()

        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { bmp ->
            if (!memoryArtworkCache.containsKey(core)) {
                runCatching {
                    val clone = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                    memoryArtworkCache[core] = clone
                    serviceScope.launch(Dispatchers.IO) { saveBitmapToDiskShield(clone) }
                    currentLogicalSession?.let { sess ->
                        if (sess.identity.title == sanitize(title) && sess.identity.artist == sanitize(artist)) {
                            serviceScope.launch(Dispatchers.IO) {
                                val dir = File(filesDir, "history")
                                if (!dir.exists()) dir.mkdirs()
                                val file = File(dir, "art_${sess.sessionUUID}.webp")
                                val tmp = File(dir, "art_${sess.sessionUUID}.tmp")
                                try {
                                    val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                                    FileOutputStream(tmp).use { out ->
                                        if (clone.compress(fmt, 80, out)) {
                                            out.flush()
                                            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                                        }
                                    }
                                } catch (e: Exception) { Log.e(TAG, "Error atomic direct", e) }
                                finally { if (tmp.exists()) tmp.delete() }
                            }
                        }
                    }
                    InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Fase A: Bitmap clonado para $title")
                }
            }
        }

        return MediaSnapshot(
            packageName = controller.packageName, title = title, artist = artist, album = album,
            mediaId = mediaId, artworkUri = uri, playbackState = state, isSessionActive = true,
            playbackDeviceName = cachedAudioDeviceName, playbackDeviceType = cachedAudioDeviceType,
            durationMs = duration, positionMs = pos, observedAtRealtime = SystemClock.elapsedRealtime()
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        if (pkg == lastObservedSnapshot?.packageName) {
            InternalLogger.d(applicationContext, "[REACTIVE] Notificación removida para $pkg")
            serviceScope.launch { refreshBestSession("notification_removed") }
        }
    }

    private fun getPlaybackDeviceInfo(context: Context): Pair<String, Int> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = devices.firstOrNull { it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return (device?.productName?.toString() ?: "Altavoz") to (device?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    private fun isAppAllowed(packageName: String): Boolean {
        val allowed = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.youtube", "arenliel.metrolist")
        return packageName in allowed
    }

    private fun sanitize(text: String): String = text.trim().lowercase()

    private suspend fun processSnapshot(controller: MediaController?, metadata: MediaMetadata?, rawSnapshot: MediaSnapshot, reason: String) {
        kotlinx.coroutines.withTimeoutOrNull(BOOT_GATE_TIMEOUT_MS) { bootGate.await() }
        val session = currentLogicalSession
        val currentMem = MusicStateProvider.current()
        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
        val isMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
        if (isLatent && isMatch && rawSnapshot.durationMs <= 0L && !currentMem.isEmpty) return

        val myGen = generation.incrementAndGet()
        val prevLog = lastLogicalSnapshot
        val sessChanged = session?.identity != TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        if (sessChanged) memoryArtworkCache.keys.retainAll(setOf("${sanitize(rawSnapshot.title)}|${sanitize(rawSnapshot.artist)}"))

        val trackContChanged = prevLog?.trackKey != rawSnapshot.trackKey
        val isCatchUp = reason == "catch_up_render"
        val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()
        val isPlayingMatches = currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING)
        val isSessionActiveMatches = currentMem.isSessionActive == rawSnapshot.isSessionActive

        // REGLA B.1: Verdad interna siempre se actualiza
        lastObservedPositionMs = rawSnapshot.projectedPositionMs()
        lastLogicalSnapshot = rawSnapshot
        session?.let { s -> if (s.identity == TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))) s.maxPositionMs = max(s.maxPositionMs, lastObservedPositionMs) }

        // REGLA B.3 (v9.5): Cambio de isPlaying siempre dispara Stage 2 para reflejar pausa
        if (!isCatchUp && !trackContChanged && !artIncoherent && isPlayingMatches && isSessionActiveMatches) {
            lastObservedSnapshot = rawSnapshot
            return
        }

        if (!isAppAllowed(rawSnapshot.packageName)) return
        val sameSess = session?.sessionIdentity == rawSnapshot.sessionIdentity
        val firstObs = if (sameSess && session != null) session.startedAtRealtime else rawSnapshot.recordedAt
        val inherit = sameSess && rawSnapshot.title == session?.identity?.title && rawSnapshot.title.isNotBlank()
        val snapshot = rawSnapshot.copy(firstObservedAt = firstObs, artworkSource = if (inherit) (session?.birthSnapshot?.artworkSource ?: rawSnapshot.artworkSource) else rawSnapshot.artworkSource)

        val newIdent = TrackIdentity(sanitize(rawSnapshot.title), sanitize(rawSnapshot.artist))
        val curProj = rawSnapshot.projectedPositionMs()
        val lastProj = prevLog?.projectedPositionMs() ?: 0L
        val prog = if (rawSnapshot.durationMs > 0) curProj.toFloat() / rawSnapshot.durationMs.toFloat() else 0f
        val identChanged = session?.identity != newIdent
        val manualRewind = curProj < (lastProj - 2000L) && !identChanged
        val catchUpRend = if (identChanged) false else Math.abs(curProj - lastProj) < 1500L
        val realLoop = rawSnapshot.playbackState == PlaybackState.STATE_PLAYING && curProj < 2000L && prog > 0.95f && !identChanged
        val sessEnded = identChanged || realLoop || (manualRewind && session?.isProvisional == false)

        if (session != null && session.isProvisional) {
            if (!identChanged) session.isProvisional = false
            else {
                historyChannel.trySend(HistoryEvent.CommitSession(session.sessionUUID, session.birthSnapshot, session.liveSnapshot, session.maxPositionMs, session.startedAtRealtime))
                currentLogicalSession = null
            }
        }

        if (sessEnded && !catchUpRend) {
            currentLogicalSession?.let { s ->
                if (!s.isProvisional) {
                    historyChannel.trySend(HistoryEvent.CommitSession(s.sessionUUID, s.birthSnapshot, s.liveSnapshot, s.maxPositionMs, s.startedAtRealtime))
                }
            }
            purgeZombieControllers()
            val newCtx = PlaybackContext(rawSnapshot.durationMs, rawSnapshot.album, rawSnapshot.artworkKey)
            val newSess = LogicalSession(identity = newIdent, birthSnapshot = rawSnapshot, liveSnapshot = rawSnapshot, frozenTrackKey = rawSnapshot.trackKey, maxPositionMs = rawSnapshot.positionMs, playbackContext = newCtx, context = this)
            currentLogicalSession = newSess
            val core = snapshot.coreKey
            memoryArtworkCache[core]?.let { bmp ->
                serviceScope.launch(Dispatchers.IO) {
                    val dir = File(filesDir, "history")
                    if (!dir.exists()) dir.mkdirs()
                    val pill = ImageUtils.createHorizontalPill(bmp, (80 * applicationContext.resources.displayMetrics.density).toInt(), (40 * applicationContext.resources.displayMetrics.density).toInt())
                    ArtworkStorageManager.saveHistoryArtwork(applicationContext, pill, newSess.sessionUUID)
                    pill.recycle()
                }
            }
        } else {
            session?.let { s -> s.liveSnapshot = rawSnapshot; s.playbackContext = PlaybackContext(rawSnapshot.durationMs, rawSnapshot.album, rawSnapshot.artworkKey) }
        }

        if (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING && (trackContChanged || eagerCacheJob == null)) {
            val uuid = session?.sessionUUID ?: ""
            eagerCacheJob?.cancel()
            eagerCacheJob = serviceScope.launch { delay(5000L); lastLogicalSnapshot?.let { persistHistoryArtworkEagerly(it, uuid) } }
        } else if (rawSnapshot.playbackState != PlaybackState.STATE_PLAYING) eagerCacheJob?.cancel()

        serviceScope.launch {
            mutationMutex.withLock {
                val isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING
                val info = musicDataStore.musicInfoFlow.first()
                val (plays, skip, freq) = if (sessEnded) Triple(0, 0, false) else if (!isPlaying) Triple(currentMem.playsToday, currentMem.skipStreak, currentMem.isFrequentArtist) else musicDataStore.getStatsFor(snapshot.title, snapshot.artist)
                val mem = MusicInfo(snapshot.title, snapshot.artist, snapshot.packageName, snapshot.album ?: "", session?.frozenTrackKey ?: snapshot.trackKey, session?.sessionUUID ?: "", snapshot.artworkKey, snapshot.artworkUri ?: "", currentMem.lastUpdateEpoch, currentMem.observedAtRealtime, savedAppIconKey ?: "", if (!sessEnded) currentMem.currentLyric else "", if (!sessEnded) currentMem.lyricsTrackKey else "", true, true, true, emptySet(), isPlaying, snapshot.isSessionActive, snapshot.playbackDeviceName, snapshot.playbackDeviceType, snapshot.durationMs, info.history, playsToday = plays, streakDays = currentMem.streakDays, skipStreak = skip, isFrequentArtist = freq, isPendingCommit = false, lastMaxPositionMs = session?.maxPositionMs ?: snapshot.positionMs)
                val ev = if (sessEnded) MusicUpdateEvent.NewSession(mem) else if (trackContChanged) MusicUpdateEvent.MetadataRefinement(snapshot.trackKey, snapshot.artworkKey, snapshot.durationMs, isPlaying) else MusicUpdateEvent.StatusUpdate(isPlaying, snapshot.playbackDeviceName, snapshot.playbackDeviceType)
                if (MusicStateProvider.applyEvent(ev)) uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
                if (sessEnded) relaunchLyricsTicker("identity_change") else if (currentMem.isPlaying != isPlaying) relaunchLyricsTicker("state_sync")
            }
        }

        val isVisible = isWidgetPotentiallyVisible()
        if (!isVisible) {
            isPresentationDirty = true; pendingSnapshot = snapshot; lyricsUpdateJob?.cancel(); lastObservedSnapshot = snapshot
        }
        
        val prevApp = lastAppliedSnapshot
        val trackUI = prevApp?.trackKey != snapshot.trackKey
        val appUI = prevApp?.packageName != snapshot.packageName
        val artUI = prevApp?.artworkKey != snapshot.artworkKey

        try {
            var resArt: Bitmap? = null; var resAppIcon: Bitmap? = null; var resIconKey: String? = null; var resTier: Int = TIER_NONE
            if (controller != null && metadata != null && (trackUI || artUI || savedArtworkKey == null)) {
                resArt = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) { resolveArtworkDeduplicated(snapshot, controller, metadata, myGen) }
                
                // REGLA B.2: Refinamiento de Bitmap siempre ocurre
                if (resArt != null) {
                    currentLogicalSession?.let { s -> if (s.identity.title == sanitize(snapshot.title) && s.identity.artist == sanitize(snapshot.artist)) s.liveSnapshot = s.liveSnapshot.copy(artworkSource = ArtworkSource.Bitmap(resArt)) }
                }

                if (!isVisible) return

                if (appUI || savedAppIconKey == null || currentIconTier < TIER_NOTIFICATION) {
                    val (icon, tier) = resolveAppIcon(snapshot.packageName)
                    if (icon != null && (appUI || tier > currentIconTier)) { resAppIcon = icon; resIconKey = "${snapshot.packageName}_stable"; resTier = tier }
                }
            } else if (!isVisible) return

            if (trackUI) {
                lyricsUpdateJob?.cancel(); lyricsFetchJob?.cancel(); currentLyrics = null
                lyricsFetchJob = serviceScope.launch { delay(500L); val r = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs); if (r != null && isActive) { currentLyrics = r; relaunchLyricsTicker("identity_change") } }
            } else {
                if (currentLyrics == null) currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                if (currentLyrics != null) relaunchLyricsTicker("state_sync")
            }

            if (myGen == generation.get() || snapshot.artworkKey == lastObservedSnapshot?.artworkKey) {
                commitMutex.withLock {
                    if (controller != null && metadata != null && (trackUI || artUI || savedArtworkKey == null)) {
                        if (resArt != null) {
                            MusicWidget.bitmapCache.put("${rawSnapshot.artworkKey}_raw", scaleForTransport(resArt))
                            if (savedArtworkKey != snapshot.artworkKey) {
                                saveBitmapToFile(resArt, ALBUM_ART_RAW_FILE, false); saveBitmapToFile(resArt, ALBUM_ART_FILE, true)
                                saveTextToFile(snapshot.artworkKey, ALBUM_ART_KEY_FILE); savedArtworkKey = snapshot.artworkKey
                                lastLogicalSnapshot = lastLogicalSnapshot?.copy(artworkSource = ArtworkSource.Bitmap(resArt))
                            }
                        } else if (trackUI || artUI) {
                            val p = getPlaceholderBitmap(); saveBitmapToFile(p, ALBUM_ART_RAW_FILE, false); saveBitmapToFile(p, ALBUM_ART_FILE, true)
                            saveTextToFile("", ALBUM_ART_KEY_FILE); savedArtworkKey = null
                        }
                        if (resAppIcon != null && resIconKey != null) {
                            saveBitmapToFile(resAppIcon, APP_ICON_FILE); saveTextToFile(resIconKey, APP_ICON_KEY_FILE)
                            savedAppIconKey = resIconKey; currentIconTier = resTier
                        }
                    }
                    lastAppliedSnapshot = snapshot; lastObservedSnapshot = snapshot
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Error pipeline", e) }
    }

    private fun relaunchLyricsTicker(reason: String) {
        if (!isWidgetPotentiallyVisible()) { lyricsUpdateJob?.cancel(); return }
        val info = MusicStateProvider.current()
        if (info.isEmpty || !info.isSessionActive) { lyricsUpdateJob?.cancel(); return }
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = serviceScope.launch(Dispatchers.IO) {
            val res = lyricsRepository.getLyrics(info.trackKey, info.artist, info.title, info.durationMs) ?: return@launch
            if (info.isPlaying) runLyricsShowcase(info.trackKey, res) else runPausedLyricsCycle(info.trackKey, res)
        }
    }

    private suspend fun runLyricsShowcase(tk: String, res: LyricsResult) {
        while (currentCoroutineContext().isActive) {
            val ram = MusicStateProvider.current()
            if (ram.trackKey != tk || !ram.isPlaying) break
            val pos = lastLogicalSnapshot?.projectedPositionMs() ?: break
            val entry = res.allEntries.lastOrNull { it.timestampMs <= (pos + 500L) }
            if (entry != null) updateLyricInWidget(tk, entry.text)
            val idx = res.allEntries.indexOf(entry)
            val next = if (idx != -1 && idx < res.allEntries.size - 1) res.allEntries[idx + 1] else null
            if (next != null) {
                val wait = (next.timestampMs - (pos + 500L)).coerceAtLeast(100L)
                if (wait > 15000L) {
                    delay(8000L); if (MusicStateProvider.current().trackKey == tk) updateLyricInWidget(tk, "")
                    delay((wait - 8000L).coerceAtLeast(100L))
                } else delay(wait)
            } else break
        }
    }

    private suspend fun runPausedLyricsCycle(tk: String, res: LyricsResult) {
        var s = true
        while (currentCoroutineContext().isActive) {
            val ram = MusicStateProvider.current()
            if (ram.trackKey != tk || ram.isPlaying) break
            val pos = lastLogicalSnapshot?.projectedPositionMs() ?: 0L
            val entry = res.allEntries.lastOrNull { it.timestampMs <= pos } ?: res.allEntries.firstOrNull()
            updateLyricInWidget(tk, if (s && entry != null) entry.text else "")
            s = !s; delay(60000L)
        }
    }

    private fun updateLyricInWidget(tk: String, l: String) {
        serviceScope.launch { if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(l, tk))) uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate) }
    }

    private fun resolveAppIcon(pkg: String): Pair<Bitmap?, Int> {
        val density = applicationContext.resources.displayMetrics.density
        val size = (14 * density).toInt()
        return try {
            val notifs = getActiveNotifications()
            val token = selectedController?.sessionToken
            var m = if (token != null) notifs.firstOrNull { it.notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) == token } else null
            if (m == null) m = notifs.firstOrNull { it.packageName == pkg && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            if (m == null) m = notifs.firstOrNull { it.packageName == pkg }
            m?.notification?.smallIcon?.loadDrawable(this)?.toBitmap()?.let {
                val norm = Bitmap.createScaledBitmap(it, size, size, true)
                iconVault[pkg] = norm to TIER_NOTIFICATION
                return norm to TIER_NOTIFICATION
            }
            if (iconVault[pkg]?.second == TIER_MONOCHROME) return iconVault[pkg]!!
            if (Build.VERSION.SDK_INT >= 33) {
                val i = packageManager.getApplicationIcon(pkg)
                if (i is android.graphics.drawable.AdaptiveIconDrawable && i.monochrome != null) {
                    val norm = ImageUtils.normalizeIcon(getNativeAwareMonochromeBitmap(i.monochrome!!), false, size)
                    iconVault[pkg] = norm to TIER_MONOCHROME; return norm to TIER_MONOCHROME
                }
            }
            if (iconVault[pkg]?.second == TIER_COLOR) return iconVault[pkg]!!
            val norm = ImageUtils.normalizeIcon(packageManager.getApplicationIcon(pkg).toBitmap(), true, size)
            iconVault[pkg] = norm to TIER_COLOR; return norm to TIER_COLOR
        } catch (e: Exception) { iconVault[pkg] ?: (null to TIER_NONE) }
    }

    private fun getNativeAwareMonochromeBitmap(d: android.graphics.drawable.Drawable): Bitmap {
        val den = applicationContext.resources.displayMetrics.density
        val std = (108 * den).toInt()
        val w = d.intrinsicWidth; val h = d.intrinsicHeight
        val s = if (w > 0 && h > 0 && w < std) max(w, h) else std
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(android.graphics.Canvas(b)); return b
    }

    private fun scaleForTransport(b: Bitmap): Bitmap {
        val s = 384
        if (b.width <= s && b.height <= s) return b
        val r = b.width.toFloat() / b.height.toFloat()
        val (nw, nh) = if (r > 1f) s to (s / r).toInt().coerceAtLeast(1) else (s * r).toInt().coerceAtLeast(1) to s
        return try { Bitmap.createScaledBitmap(b, nw, nh, true) } catch (e: Exception) { b }
    }

    private suspend fun resolveArtworkDeduplicated(snap: MediaSnapshot, controller: MediaController? = null, metadata: MediaMetadata? = null, gen: Long = -1L): Bitmap? {
        artworkCache.get(snap.artworkKey)?.let { return it }
        return getOrCreateArtworkDeferred(snap, controller, metadata, gen).await()
    }

    private suspend fun getOrCreateArtworkDeferred(snap: MediaSnapshot, controller: MediaController?, metadata: MediaMetadata?, gen: Long): Deferred<Bitmap?> {
        val key = snap.artworkKey
        artworkInFlightMutex.withLock {
            artworkCache.get(key)?.let { return CompletableDeferred(it) }
            artworkInFlight[key]?.let { if (it.isActive) return it; artworkInFlight.remove(key) }
            val d = serviceScope.async {
                try {
                    val b = findRealAlbumArt(snap, controller, metadata)
                    val rel = key == lastObservedSnapshot?.artworkKey
                    if (isActive && (gen == generation.get() || rel || gen == -1L) && b != null) artworkCache.put(key, b)
                    b
                } catch (e: Exception) { if (e is CancellationException) throw e; null }
                finally { artworkInFlightMutex.withLock { if (artworkInFlight[key] === coroutineContext[Job]) artworkInFlight.remove(key) } }
            }
            artworkInFlight[key] = d; return d
        }
    }

    private suspend fun findRealAlbumArt(snap: MediaSnapshot, controller: MediaController?, metadata: MediaMetadata?): Bitmap? = withContext(Dispatchers.IO) {
        metadata?.let { m ->
            if (m.getString(MediaMetadata.METADATA_KEY_TITLE) == snap.title) {
                m.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { if (isValidArtwork(it, 100)) return@withContext ensureMaxDimension(it, 800) }
                m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { if (isValidArtwork(it, 100)) return@withContext ensureMaxDimension(it, 800) }
            }
        }
        try {
            val n = getActiveNotifications().firstOrNull { it.packageName == snap.packageName && it.notification.category == Notification.CATEGORY_TRANSPORT && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == snap.title }
            n?.notification?.getLargeIcon()?.loadDrawable(this@MusicNotificationListener)?.toBitmap()?.let { if (isValidArtwork(it, 100)) return@withContext ensureMaxDimension(it, 800) }
        } catch (e: Exception) { }
        snap.artworkUri?.takeIf { it.isNotBlank() }?.let { u -> decodeAlbumArtUri(u)?.let { if (isValidArtwork(it, 100)) return@withContext it } }
        memoryArtworkCache[snap.coreKey]?.let { if (isValidArtwork(it, 100)) return@withContext ensureMaxDimension(it, 800) }
        null
    }

    private fun ensureMaxDimension(b: Bitmap, max: Int): Bitmap {
        if (b.width <= max && b.height <= max) return b
        val r = b.width.toFloat() / b.height.toFloat()
        val (nw, nh) = if (r > 1f) max to (max / r).toInt().coerceAtLeast(1) else (max * r).toInt().coerceAtLeast(1) to max
        return try { Bitmap.createScaledBitmap(b, nw, nh, true) } catch (e: Exception) { b }
    }

    private fun isValidArtwork(b: Bitmap, m: Int): Boolean = !b.isRecycled && b.width >= m && b.height >= m

    private suspend fun decodeAlbumArtUri(u: String): Bitmap? {
        if (u.startsWith("content://com.spotify.mobile.android.mediaapi")) {
            val h = Uri.decode(u).substringAfterLast(":").substringBefore("?")
            if (h.isNotBlank()) return downloadBitmapFromUrl("https://i.scdn.co/image/$h")
        }
        if (u.startsWith("http://") || u.startsWith("https://")) return downloadBitmapFromUrl(u)
        return try { contentResolver.openInputStream(Uri.parse(u))?.use { decodeSampledBitmapFromStream(it, 800, 800) } } catch (e: Exception) { null }
    }

    private suspend fun downloadBitmapFromUrl(u: String): Bitmap? = withContext(Dispatchers.IO) {
        var c: java.net.HttpURLConnection? = null
        try {
            c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000; c.connect()
            if (c.responseCode !in 200..299) return@withContext null
            c.inputStream.use { if (!isActive) return@withContext null; decodeSampledBitmapFromStream(it, 800, 800) }
        } catch (e: Exception) { null } finally { c?.disconnect() }
    }

    private fun decodeSampledBitmapFromStream(i: java.io.InputStream, rw: Int, rh: Int): Bitmap? {
        val b = i.readBytes(); val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        o.inSampleSize = calculateInSampleSize(o, rw, rh); o.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)
    }

    private fun calculateInSampleSize(o: BitmapFactory.Options, rw: Int, rh: Int): Int {
        var s = 1; if (o.outHeight > rh || o.outWidth > rw) {
            val h2 = o.outHeight / 2; val w2 = o.outWidth / 2
            while (h2 / s >= rh && w2 / s >= rw) s *= 2
        }
        return s
    }

    private fun getPlaceholderBitmap(): Bitmap {
        return try { ContextCompat.getDrawable(applicationContext, R.drawable.ic_music_note)?.toBitmap() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        catch (e: Exception) { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }

    private suspend fun saveTextToFile(t: String, f: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock { try {
            val file = File(filesDir, f); val tmp = File(filesDir, "$f.tmp"); tmp.writeText(t)
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: Exception) { } }
    }

    private suspend fun saveBitmapToFile(b: Bitmap, f: String, p: Boolean = false) = withContext(Dispatchers.IO) {
        fileMutex.withLock { try {
            val pb = if (p) withContext(Dispatchers.Default) {
                val m = 800; val (nw, nh) = if (b.width > m || b.height > m) { val r = b.width.toFloat() / b.height.toFloat(); if (r > 1f) m to (m / r).toInt().coerceAtLeast(1) else (m * r).toInt().coerceAtLeast(1) to m } else b.width to b.height
                val ob = Bitmap.createScaledBitmap(b, nw, nh, true)
                val res = ImageUtils.createRotatedPillBitmap(ob, -28f, applicationContext.resources.getDimensionPixelSize(R.dimen.album_art_size_classic), 0.9f)
                if (ob !== b) ob.recycle(); res
            } else null
            val b2s = pb ?: b; val file = File(filesDir, f); val tmp = File(filesDir, "$f.tmp")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            FileOutputStream(tmp).use { it -> b2s.compress(fmt, 85, it); it.fd.sync() }
            if (pb != null && pb !== b) pb.recycle()
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: Exception) { } }
    }

    override fun onListenerDisconnected() { unregisterDynamicScreenReceiver(); uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate); super.onListenerDisconnected() }

    private fun unregisterDynamicScreenReceiver() { runCatching { unregisterReceiver(dynamicScreenReceiver) } }

    override fun onDestroy() {
        unregisterDynamicScreenReceiver(); pendingRefreshJob?.cancel()
        artworkInFlightMutex.tryLock().let { if (it) { try { artworkInFlight.values.forEach { it.cancel() }; artworkInFlight.clear() } finally { artworkInFlightMutex.unlock() } } }
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(audioDeviceCallback)
        controllerCallbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        controllerCallbacks.clear(); selectedController = null; lastObservedSnapshot = null; lastAppliedSnapshot = null
        inFlightSnapshot = null; savedArtworkKey = null; artworkCache.evictAll(); iconVault.clear(); lyricsUpdateJob?.cancel(); lyricsFetchJob?.cancel(); serviceJob.cancel(); super.onDestroy()
    }

    companion object {
        private const val TAG = "MusicListener"
        private var lastCommittedInfo: MusicInfo? = null
        fun getLatestMusicInfo(): MusicInfo? = lastCommittedInfo
        private const val TIER_NONE = 0; private const val TIER_COLOR = 1; private const val TIER_MONOCHROME = 2; private const val TIER_NOTIFICATION = 3
        private const val ALBUM_ART_FILE = "album_art.webp"; private const val ALBUM_ART_RAW_FILE = "album_art_raw.webp"
        private const val ALBUM_ART_KEY_FILE = "album_art.key"; private const val APP_ICON_FILE = "app_icon.webp"; private const val APP_ICON_KEY_FILE = "app_icon.key"
        private const val NORMAL_DEBOUNCE_MS = 150L; private const val METADATA_STABILIZATION_MS = 400L; private const val NETWORK_CONNECT_TIMEOUT_MS = 3000
        private const val NETWORK_READ_TIMEOUT_MS = 3000; private const val ARTWORK_CACHE_SIZE_KB = 8 * 1024; private const val ARTWORK_PROMOTION_TIMEOUT_MS = 3500L
        private const val DISK_SHIELD_FILE = "current_artwork_raw.webp"; private const val BOOT_GATE_TIMEOUT_MS = 5000L
    }

    private suspend fun saveBitmapToDiskShield(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val file = File(cacheDir, DISK_SHIELD_FILE)
            try {
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                FileOutputStream(file).use { out -> bitmap.compress(format, 90, out); out.flush() }
            } catch (e: Exception) { Log.e(TAG, "Fallo Disk Shield", e) }
        }
    }
}
