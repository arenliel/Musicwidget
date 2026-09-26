package arenliel.musicwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Conjunto Widget-Host-Resiliencia-2: reemplaza el mecanismo de reintento de
 * Widget-Host-Resiliencia-1 (ver docs/reportes-ia/auditoria-widget-host-instability-ronda2.md
 * para la investigación completa, incluyendo el código fuente real de Glance que confirma
 * cada punto de abajo).
 *
 * El mecanismo anterior tenía dos defectos confirmados:
 * 1. Medía "recuperación" con la señal equivocada: si `update()` lanzaba una excepción.
 *    Cuando la sesión de Glance sigue viva (que es el caso real observado: Glance no cierra
 *    la sesión al fallar `processEmittableTree`), `update()` solo encola un evento interno
 *    (`sendEvent`) que casi nunca lanza — así que el mecanismo anterior reportaba
 *    "recuperado" de inmediato, en el intento 1, sin que el widget realmente se hubiera
 *    redibujado.
 * 2. No coordinaba invocaciones concurrentes: cada recomposición fallida de la sesión (una
 *    por cada canción nueva mientras el problema seguía activo) disparaba un
 *    `onCompositionError` nuevo e independiente, cada uno con su propia escalera de
 *    reintento sin memoria de las demás — por eso nunca se veía "intento 2" ni "intento 3"
 *    en el log de campo, pese a 636 fallos en 6 minutos.
 *
 * Este objeto corrige ambos puntos:
 * - Señal real: comprueba directamente `AppWidgetManager.getAppWidgetInfo(appWidgetId) != null`
 *   — exactamente la condición cuya ausencia causa el error real
 *   (`AppWidgetSession.kt:180` en el código fuente de Glance), no un proxy indirecto.
 * - Vuelo único (single-flight) por `appWidgetId`: si ya hay una recuperación en curso para
 *   ese `appWidgetId`, una invocación nueva se ignora — la que ya está corriendo seguirá
 *   comprobando el estado real hasta resolverse o agotar el plazo. Se implementa con
 *   `ConcurrentHashMap.computeIfAbsent`, que garantiza (por el propio contrato de la clase)
 *   que nunca se cree más de un `Job` a la vez para el mismo `appWidgetId`, sin ventana de
 *   carrera entre "comprobar si ya hay uno" y "crear uno nuevo".
 */
object WidgetRecoveryGuard {

    // Fase 1: misma cadencia ya validada en campo por Widget-Host-Resiliencia-1 (rápida,
    // cubre el caso común de reconexión transitoria del binder tras Doze).
    private val FAST_BACKOFF_MS = listOf(3_000L, 10_000L, 30_000L)

    // Fase 2: el log real de campo (auditoria-widget-host-instability-ronda2.md) mostró una
    // indisponibilidad sostenida de varios minutos tras un crash real de otra app — la Fase 1
    // por sí sola (43s totales) no la habría cubierto. Fase 2 sondea cada 60s, hasta 6 veces
    // más, antes de rendirse de verdad (6 minutos adicionales; ~6.7 minutos en total, acorde
    // con la duración real observada en el log).
    private const val SLOW_POLL_MS = 60_000L
    private const val SLOW_POLL_MAX_ATTEMPTS = 6

    private val activeRecoveries = ConcurrentHashMap<Int, Job>()

    /**
     * Punto de entrada, llamado desde `onCompositionError`. No es `suspend`: arranca (o
     * ignora, si ya hay una en curso) una recuperación en segundo plano y retorna de
     * inmediato, igual que el mecanismo anterior. [onGiveUp] se invoca solo si ambas fases
     * se agotan sin que el host confirme el widget como válido de nuevo.
     */
    fun onWidgetError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        widget: MusicWidget,
        onGiveUp: () -> Unit,
    ) {
        activeRecoveries.computeIfAbsent(appWidgetId) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context)

                    suspend fun probarYRedibujar(): Boolean {
                        val hostListo = runCatching {
                            appWidgetManager.getAppWidgetInfo(appWidgetId) != null
                        }.getOrDefault(false)
                        if (!hostListo) return false
                        // El host ya reconoce el ID: forzamos un redibujado real con los
                        // datos actuales en vez de esperar a que la próxima recomposición
                        // espontánea (p. ej. la siguiente canción) lo haga por casualidad.
                        runCatching { widget.update(context, glanceId) }
                        return true
                    }

                    var recuperado = false

                    for ((intento, esperaMs) in FAST_BACKOFF_MS.withIndex()) {
                        delay(esperaMs)
                        if (probarYRedibujar()) {
                            InternalLogger.log(context, "[WidgetRecovery] Host confirmado listo en el intento ${intento + 1} (tras ${esperaMs}ms) para appWidgetId=$appWidgetId")
                            recuperado = true
                            break
                        }
                    }

                    if (!recuperado) {
                        InternalLogger.w(context, "[WidgetRecovery] appWidgetId=$appWidgetId sigue sin registro de host tras ${FAST_BACKOFF_MS.sum()}ms. Pasando a sondeo lento (${SLOW_POLL_MAX_ATTEMPTS}x${SLOW_POLL_MS}ms).")
                        for (intentoLento in 1..SLOW_POLL_MAX_ATTEMPTS) {
                            delay(SLOW_POLL_MS)
                            if (probarYRedibujar()) {
                                InternalLogger.log(context, "[WidgetRecovery] Host confirmado listo en el sondeo lento #$intentoLento para appWidgetId=$appWidgetId")
                                recuperado = true
                                break
                            }
                        }
                    }

                    if (!recuperado) {
                        InternalLogger.w(context, "[WidgetRecovery] appWidgetId=$appWidgetId no recuperó tras agotar ambas fases. Delegando en el comportamiento por defecto de Glance.")
                        onGiveUp()
                    }
                } finally {
                    activeRecoveries.remove(appWidgetId)
                }
            }
        }
    }
}
