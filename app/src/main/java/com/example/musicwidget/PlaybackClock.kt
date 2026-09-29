package arenliel.musicwidget

/**
 * Conjunto Controles-Posicion-1: ancla de posición de reproducción para la barra de progreso.
 *
 * El servicio publica aquí, cada vez que procesa un snapshot o un seek, los mismos datos con los que
 * MediaSnapshot.projectedPositionMs() proyecta la posición (posición + tiempo transcurrido × velocidad,
 * con el reloj monotónico del sistema). El widget solo LEE: calcula la fracción al momento de dibujar.
 * Así la barra no depende de que ninguna app mande actualizaciones periódicas, y MusicInfo (comparado
 * por igualdad completa en MusicStateProvider) no cambia por el simple paso del tiempo.
 *
 * La identidad de sesión viaja con el ancla: si la canción que el widget está mostrando (identidad
 * retenida, ver Identidad-Atómica-Presentación-1) no coincide con la del ancla, la fracción es 0 —
 * nunca se pinta el progreso de una canción bajo el título de otra.
 */
object PlaybackClock {

    private data class Anchor(
        val sessionIdentity: String,
        val positionMs: Long,
        val positionUpdatedAtRealtime: Long,
        val playbackSpeed: Float,
        val durationMs: Long
    )

    @Volatile
    private var anchor: Anchor? = null

    fun publish(
        sessionIdentity: String,
        positionMs: Long,
        positionUpdatedAtRealtime: Long,
        playbackSpeed: Float,
        durationMs: Long
    ) {
        anchor = Anchor(sessionIdentity, positionMs, positionUpdatedAtRealtime, playbackSpeed, durationMs)
    }

    fun clear() {
        anchor = null
    }

    /**
     * Fracción 0f..1f de la canción [sessionIdentity]. Si [isPlaying] es false la posición queda
     * congelada en la del ancla (pausa); si es true se proyecta con el tiempo transcurrido.
     */
    fun fraction(sessionIdentity: String, isPlaying: Boolean, nowRealtime: Long): Float {
        val a = anchor ?: return 0f
        if (a.sessionIdentity != sessionIdentity) return 0f
        if (a.durationMs <= 0L) return 0f
        val position = if (isPlaying) {
            a.positionMs + ((nowRealtime - a.positionUpdatedAtRealtime) * a.playbackSpeed).toLong()
        } else {
            a.positionMs
        }
        return (position.toFloat() / a.durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
