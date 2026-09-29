package arenliel.musicwidget

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Conjunto Controles-Puente-1: comandos de transporte que el widget puede enviar al reproductor.
 * Se envían por la API estándar de MediaSession (MediaController.transportControls), igual para
 * cualquier app de música — no hay ningún caso especial por aplicación.
 */
enum class TransportCommand { PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * Puente entre los botones del widget (que corren fuera del servicio) y el MediaController que el
 * servicio de escucha ya eligió (selectBestController). El servicio registra un proveedor al
 * conectarse y lo retira al desconectarse; los botones nunca eligen un controlador por su cuenta,
 * así que siempre actúan sobre la misma sesión que el widget está mostrando.
 *
 * No actualiza la interfaz por sí mismo: el reproductor responde con un cambio de estado de su
 * MediaSession y el servicio ya redibuja el widget por el camino normal.
 */
object TransportBridge {

    @Volatile
    private var controllerProvider: (() -> MediaController?)? = null

    fun attach(provider: () -> MediaController?) {
        controllerProvider = provider
    }

    fun detach() {
        controllerProvider = null
    }

    /** Devuelve true si el comando llegó a enviarse al controlador; false si no había controlador o falló. */
    fun send(context: Context, command: TransportCommand): Boolean {
        val controller = runCatching { controllerProvider?.invoke() }.getOrNull()
        if (controller == null) {
            InternalLogger.d(context, "[TRANSPORT_TRACE] comando=$command sin controlador disponible (servicio desconectado o sin sesión).")
            return false
        }

        val stateBefore = controller.playbackState?.state
        val delivered = runCatching {
            val controls = controller.transportControls
            when (command) {
                TransportCommand.PLAY_PAUSE -> {
                    if (stateBefore == PlaybackState.STATE_PLAYING || stateBefore == PlaybackState.STATE_BUFFERING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                TransportCommand.NEXT -> controls.skipToNext()
                TransportCommand.PREVIOUS -> controls.skipToPrevious()
            }
        }.isSuccess

        InternalLogger.d(
            context,
            "[TRANSPORT_TRACE] comando=$command paquete=${controller.packageName} estadoAntes=$stateBefore enviado=$delivered"
        )
        return delivered
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TransportBridge.send(context, TransportCommand.PLAY_PAUSE)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TransportBridge.send(context, TransportCommand.NEXT)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TransportBridge.send(context, TransportCommand.PREVIOUS)
    }
}
