package arenliel.musicwidget

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PermissionUtils {

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

    fun isNotificationServiceEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabledPackages.contains(context.packageName)) return true

        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val componentName = ComponentName(context, MusicNotificationListener::class.java).flattenToString()
        return listeners?.contains(componentName) == true
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Navegación inteligente a ajustes de notificaciones con resaltado.
     */
    fun openNotificationSettings(context: Context) {
        val componentName = ComponentName(context, MusicNotificationListener::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, componentName)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, componentName)
            })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            // Fallback a la lista estándar
            val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /**
     * Solicitud directa de ignorar optimizaciones de batería.
     */
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) {
        if (isBatteryOptimizationIgnored(context)) return

        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(directIntent)
        }.onFailure {
            // Fallback a la lista general si el sistema bloquea el diálogo directo
            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(listIntent)
        }
    }

    /**
     * Navegación a la información de la app (para Ajustes Restringidos).
     */
    fun openAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Navegación a través del Trampolín para asegurar el refresco y retorno controlado.
     */
    fun openNotificationSettingsViaTrampoline(context: Context) {
        val intent = Intent(context, PermissionsTrampolineActivity::class.java).apply {
            action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openBatterySettingsViaTrampoline(context: Context) {
        val intent = Intent(context, PermissionsTrampolineActivity::class.java).apply {
            action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Determina si es probable que estemos bajo restricciones de "Ajustes Restringidos" (API 33+).
     */
    fun isRestrictedSettingsLikely(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationServiceEnabled(context)
    }

    // Conjunto Deteccion-Nativa-Apps-1: caches a nivel de proceso. No requieren invalidación —
    // una app nueva simplemente se evalúa correctamente la primera vez que se le consulta, y el
    // resultado se memoiza para siempre mientras el proceso siga vivo.
    @Volatile private var browserPackagesCache: Set<String>? = null
    @Volatile private var mediaBrowserServicePackagesCache: Set<String>? = null
    private val musicAppDetectionCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun browserPackages(context: Context): Set<String> {
        browserPackagesCache?.let { return it }
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val result = context.packageManager.queryIntentActivities(browserIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        browserPackagesCache = result
        return result
    }

    private fun mediaBrowserServicePackages(context: Context): Set<String> {
        mediaBrowserServicePackagesCache?.let { return it }
        val mediaIntent = Intent("android.media.browse.MediaBrowserService")
        val result = context.packageManager.queryIntentServices(mediaIntent, 0)
            .map { it.serviceInfo.packageName }
            .toSet()
        mediaBrowserServicePackagesCache = result
        return result
    }

    /**
     * Detección nativa de apps de música/audio (Conjunto Deteccion-Nativa-Apps-1).
     * Sin listas de paquetes hardcodeadas: un paquete se considera app de música si
     * expone un MediaBrowserService o está categorizado por el sistema como
     * CATEGORY_AUDIO/CATEGORY_VIDEO, y NO responde como navegador web (mismo criterio
     * que usa Android internamente para resolver el navegador predeterminado).
     */
    fun isNativeMusicApp(context: Context, packageName: String): Boolean {
        musicAppDetectionCache[packageName]?.let { return it }

        val result = runCatching {
            if (browserPackages(context).contains(packageName)) {
                false
            } else {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                val isMediaCategory = appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
                        appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
                isMediaCategory || mediaBrowserServicePackages(context).contains(packageName)
            }
        }.getOrDefault(false)

        musicAppDetectionCache[packageName] = result
        return result
    }

    /**
     * Obtiene la lista de aplicaciones de música instaladas, mediante detección nativa
     * (sin listas de paquetes hardcodeadas): enumera las apps con actividad de LAUNCHER
     * (ya declarado en <queries>) y las filtra con [isNativeMusicApp].
     */
    // Conjunto Correccion-Carrera-Icono-1 (Pieza 3): scope propio, de vida ligada al
    // proceso, exclusivamente para precalentar en segundo plano el registro de íconos
    // estáticos (IconRegistry) de las apps detectadas en la pantalla de configuración. No se
    // cancela nunca a propósito — es trabajo de "mejor esfuerzo" que puede sobrevivir a la
    // pantalla que lo disparó (si el usuario sale de Ajustes antes de que termine, el
    // precalentado sigue igual, ya que su único efecto es dejar la caché en disco lista).
    private val iconWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getInstalledMusicApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPackages = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        val result = launcherPackages
            .filter { isNativeMusicApp(context, it) }
            .mapNotNull { pkg ->
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    AppItem(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = pkg,
                        icon = pm.getApplicationIcon(appInfo)
                    )
                }.getOrNull()
            }
            .sortedBy { it.name }

        // Conjunto Correccion-Carrera-Icono-1 (Pieza 3): apenas se detecta una app de música,
        // se dispara en segundo plano la resolución de su ícono estático (monochrome si
        // existe, si no color), reutilizando IconRegistry.resolveStaticIcon tal cual existe
        // hoy — sin esperar el resultado ni bloquear esta lista. Así, para cuando esa app
        // suene de verdad por primera vez, su ícono estático ya está resuelto y en caché, en
        // vez de tener que resolverse en caliente durante una reproducción real.
        //
        // IMPORTANTE: el tamaño (targetSizePx) usado aquí debe coincidir exactamente con el
        // que usa MusicNotificationListener.tryPromoteAppIcon para pedir el mismo ícono en
        // tiempo real — IconRegistry no vuelve a escalar un bitmap servido desde caché, así
        // que un tamaño distinto en cualquiera de los dos puntos haría que se sirviera un
        // ícono del tamaño equivocado sin ningún error visible. Si alguna vez cambia el
        // tamaño en un lado, debe cambiarse igual en el otro.
        val appContext = context.applicationContext
        val density = appContext.resources.displayMetrics.density
        val targetSizePx = (14 * density).toInt()
        result.forEach { app ->
            iconWarmupScope.launch {
                IconRegistry.resolveStaticIcon(appContext, app.packageName, targetSizePx)
            }
        }

        return result
    }
}
