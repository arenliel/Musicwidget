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

        if (next == current) return@withLock false
        
        _musicInfoState.value = next
        return@withLock true
    }

    private fun reconcileNewSession(current: MusicInfo, e: MusicUpdateEvent.NewSession): MusicInfo {
        // Hallazgo v4.2: Estabilización de Scroll Inteligente.
        // Si la sesión cambió, permitimos que el historial se actualice (para mostrar la canción nueva).
        // Pero si los datos son idénticos, preservamos la referencia física para silenciar el scroll.
        val sessionChanged = current.trackKey != e.info.trackKey
        val stableHistory = if (!sessionChanged && e.info.history == current.history) {
            current.history
        } else {
            e.info.history
        }

        return e.info.copy(
            currentLyric = if (sessionChanged) "" else current.currentLyric,
            lyricsTrackKey = if (sessionChanged) "" else current.lyricsTrackKey,
            history = stableHistory,
            lastUpdateEpoch = System.currentTimeMillis(),
            observedAtRealtime = android.os.SystemClock.elapsedRealtime()
        )
    }

    private fun reconcileRefinement(current: MusicInfo, e: MusicUpdateEvent.MetadataRefinement): MusicInfo {
        return current.copy(
            trackKey = e.newTrackKey,
            artworkKey = e.newArtworkKey,
            durationMs = e.newDuration
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
        if (e.trackKey != current.trackKey) return current
        
        return current.copy(
            currentLyric = e.lyric,
            lyricsTrackKey = e.trackKey
        )
    }

    private fun reconcileEnd(current: MusicInfo, e: MusicUpdateEvent.SessionEnded): MusicInfo {
        return current.copy(
            isSessionActive = false,
            isPlaying = false,
            lastUpdateEpoch = System.currentTimeMillis(),
            observedAtRealtime = android.os.SystemClock.elapsedRealtime()
        )
    }
    
    private fun reconcileStatus(current: MusicInfo, e: MusicUpdateEvent.StatusUpdate): MusicInfo {
        return current.copy(
            isPlaying = e.isPlaying,
            playbackDeviceName = e.deviceName,
            playbackDeviceType = e.deviceType
        )
    }

    fun current(): MusicInfo = _musicInfoState.value
}

sealed class MusicUpdateEvent {
    data class NewSession(val info: MusicInfo) : MusicUpdateEvent()
    data class MetadataRefinement(val newTrackKey: String, val newArtworkKey: String, val newDuration: Long) : MusicUpdateEvent()
    data class ArtworkResolved(val trackKey: String, val artworkKey: String, val iconKey: String? = null) : MusicUpdateEvent()
    data class LyricTick(val lyric: String, val trackKey: String) : MusicUpdateEvent()
    data class SessionEnded(val finalPos: Long) : MusicUpdateEvent()
    data class StatusUpdate(val isPlaying: Boolean, val deviceName: String, val deviceType: Int) : MusicUpdateEvent()
    object ClearVisualHistory : MusicUpdateEvent()
}
