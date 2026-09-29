package arenliel.musicwidget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Conjunto Controles-Posicion-1: pruebas de PlaybackClock (lógica pura, sin Android). */
class PlaybackClockTest {

    @Test
    fun reproduciendo_proyectaConElTiempoTranscurrido() {
        PlaybackClock.publish("id", positionMs = 30_000L, positionUpdatedAtRealtime = 1_000L, playbackSpeed = 1f, durationMs = 120_000L)
        // 10s después: 40s de 120s
        assertEquals(40_000f / 120_000f, PlaybackClock.fraction("id", isPlaying = true, nowRealtime = 11_000L), 0.0001f)
    }

    @Test
    fun enPausa_quedaCongeladaEnLaPosicionDelAncla() {
        PlaybackClock.publish("id", positionMs = 30_000L, positionUpdatedAtRealtime = 1_000L, playbackSpeed = 1f, durationMs = 120_000L)
        assertEquals(0.25f, PlaybackClock.fraction("id", isPlaying = false, nowRealtime = 999_999L), 0.0001f)
    }

    @Test
    fun identidadDistinta_devuelveCero() {
        PlaybackClock.publish("A", positionMs = 30_000L, positionUpdatedAtRealtime = 1_000L, playbackSpeed = 1f, durationMs = 120_000L)
        assertEquals(0f, PlaybackClock.fraction("B", isPlaying = true, nowRealtime = 5_000L), 0f)
    }

    @Test
    fun duracionDesconocida_devuelveCero() {
        PlaybackClock.publish("id", positionMs = 30_000L, positionUpdatedAtRealtime = 1_000L, playbackSpeed = 1f, durationMs = 0L)
        assertEquals(0f, PlaybackClock.fraction("id", isPlaying = true, nowRealtime = 5_000L), 0f)
    }

    @Test
    fun nuncaSalePorEncimaDeUno() {
        PlaybackClock.publish("id", positionMs = 119_000L, positionUpdatedAtRealtime = 0L, playbackSpeed = 1f, durationMs = 120_000L)
        assertEquals(1f, PlaybackClock.fraction("id", isPlaying = true, nowRealtime = 60_000L), 0f)
    }

    @Test
    fun sinAncla_devuelveCero() {
        PlaybackClock.clear()
        assertEquals(0f, PlaybackClock.fraction("id", isPlaying = true, nowRealtime = 5_000L), 0f)
    }
}
