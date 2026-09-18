package arenliel.musicwidget

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RELEVO ATÓMICO (Smart Mirror v4.2 - "Gobernanza de Integridad")
 * 
 * Centraliza la verdad del widget mediante un modelo de transacciones atómicas.
 * Prohíbe escrituras directas y fragmentadas para eliminar parpadeos y desincronización.
 */
object MusicStateProvider {

    private val safeInitialState = MusicInfo(
        title = "",
        artist = "",
        packageName = "",
        isPlaying = false,
        isSessionActive = false,
        lastUpdateEpoch = System.currentTimeMillis(),
        observedAtRealtime = android.os.SystemClock.elapsedRealtime()
    )

    private val _musicInfoState = MutableStateFlow<MusicInfo>(safeInitialState)
    val musicInfoState: StateFlow<MusicInfo> = _musicInfoState.asStateFlow()
    
    private val mutationMutex = Mutex()

    suspend fun applyEvent(event: MusicUpdateEvent): Boolean = mutationMutex.withLock {
        val current = _musicInfoState.value
        val next = when (event) {
            is MusicUpdateEvent.NewSession -> reconcileNewSession(current, event)
            is MusicUpdateEvent.MetadataRefinement -> reconcileRefinement(current, event)
            is MusicUpdateEvent.ArtworkResolved -> reconcileArtwork(current, event)
            is MusicUpdateEvent.LyricTick -> reconcileLyric(current, event)
            is MusicUpdateEvent.SessionEnded -> reconcileEnd(current, event)
            is MusicUpdateEvent.StatusUpdate -> reconcileStatus(current, event)
            is MusicUpdateEvent.ClearVisualHistory -> current.copy(history = emptyList())
        }

        // Full-object equality check — this intentionally catches ANY field change, including
        // isPlaying, so a real play/pause transition always triggers a UI notification.
        if (next == current) return@withLock false
        
        _musicInfoState.value = next
        return@withLock true
    }

    private fun reconcileNewSession(current: MusicInfo, e: MusicUpdateEvent.NewSession): MusicInfo {
        // Hallazgo v4.2: Estabilización de Scroll Inteligente.
        // Si la sesión cambió, permitimos que el historial se actualice (para mostrar la canción nueva).
        // Pero si los datos son idénticos, preservamos la referencia física para silenciar el scroll.
        val oldSessionChanged = current.trackKey != e.info.trackKey
        val sessionChanged = MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist) !=
            MusicDataStore.computeSessionIdentity(e.info.packageName, e.info.title, e.info.artist)
        android.util.Log.d("IDENTITY_TRACE", "Paso4_reconcileNewSession: viejo=$oldSessionChanged, nuevo=$sessionChanged, coincide=${oldSessionChanged == sessionChanged}")
        val stableHistory = if (!sessionChanged && e.info.history == current.history) {
            current.history
        } else {
            e.info.history
        }

        val shouldResetClock = sessionChanged || e.info.isPlaying

        // Identidad de negocio (sessionIdentity, no trackKey) para decidir si la letra actual
        // sigue perteneciendo a la misma canción. Evita descartar una letra válida solo porque
        // trackKey cambió por una corrección tardía de duración (Conjunto Letras-2).
        val lyricBelongsToSameSong = MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist) ==
            MusicDataStore.computeSessionIdentity(e.info.packageName, e.info.title, e.info.artist)

        return e.info.copy(
            currentLyric = if (lyricBelongsToSameSong) current.currentLyric else "",
            lyricsTrackKey = if (lyricBelongsToSameSong) e.info.trackKey else "",
            history = stableHistory,
            lastUpdateEpoch = if (shouldResetClock) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (shouldResetClock) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }

    private fun reconcileRefinement(current: MusicInfo, e: MusicUpdateEvent.MetadataRefinement): MusicInfo {
        // Este evento nunca representa un cambio real de canción — es la misma pista con datos
        // afinados (ej. duración exacta llegando tarde). Si ya había una letra resuelta y
        // etiquetada, se re-etiqueta con el trackKey nuevo para que no quede huérfana
        // (Conjunto Letras-2).
        val updatedLyricsTrackKey = if (current.lyricsTrackKey.isNotBlank()) e.newTrackKey else current.lyricsTrackKey

        return current.copy(
            trackKey = e.newTrackKey,
            artworkKey = e.newArtworkKey,
            durationMs = e.newDuration,
            isPlaying = e.isPlaying,
            isBuffering = false,
            lyricsTrackKey = updatedLyricsTrackKey
        )
    }

    private fun reconcileArtwork(current: MusicInfo, e: MusicUpdateEvent.ArtworkResolved): MusicInfo {
        if (e.trackKey != current.trackKey) return current
        
        return current.copy(
            artworkKey = e.artworkKey,
            appIconKey = e.iconKey ?: current.appIconKey
        )
    }

    private fun reconcileLyric(current: MusicInfo, e: MusicUpdateEvent.LyricTick): MusicInfo {
        if (e.trackKey != MusicDataStore.computeSessionIdentity(current.packageName, current.title, current.artist)) return current
        
        return current.copy(
            currentLyric = e.lyric,
            lyricsTrackKey = e.trackKey
        )
    }

    private fun reconcileEnd(current: MusicInfo, e: MusicUpdateEvent.SessionEnded): MusicInfo {
        val shouldResetClock = current.isPlaying
        return current.copy(
            isSessionActive = false,
            isPlaying = false,
            lastUpdateEpoch = if (shouldResetClock) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (shouldResetClock) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }
    
    private fun reconcileStatus(current: MusicInfo, e: MusicUpdateEvent.StatusUpdate): MusicInfo {
        val statusChanged = current.isPlaying != e.isPlaying || 
                           current.playbackDeviceName != e.deviceName
        
        return current.copy(
            isPlaying = e.isPlaying,
            isBuffering = e.isBuffering,
            playbackDeviceName = e.deviceName,
            playbackDeviceType = e.deviceType,
            lastUpdateEpoch = if (statusChanged) System.currentTimeMillis() else current.lastUpdateEpoch,
            observedAtRealtime = if (statusChanged) android.os.SystemClock.elapsedRealtime() else current.observedAtRealtime
        )
    }

    fun current(): MusicInfo = _musicInfoState.value
}

sealed class MusicUpdateEvent {
    data class NewSession(val info: MusicInfo) : MusicUpdateEvent()
    data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: Long, val isPlaying: Boolean) : MusicUpdateEvent()
    data class ArtworkResolved(val trackKey: String, val artworkKey: String, val iconKey: String? = null) : MusicUpdateEvent()
    data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
    data class SessionEnded(val finalPos: Long) : MusicUpdateEvent()
    data class StatusUpdate(val isPlaying: Boolean, val deviceName: String, val deviceType: Int, val isBuffering: Boolean = false) : MusicUpdateEvent()
    object ClearVisualHistory : MusicUpdateEvent()
}
