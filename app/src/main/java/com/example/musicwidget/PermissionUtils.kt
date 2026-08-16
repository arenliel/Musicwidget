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

    /**
     * Obtiene la lista de aplicaciones de música instaladas de forma eficiente.
     */
    fun getInstalledMusicApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val mediaIntent = Intent("android.media.browse.MediaBrowserService")
        val mediaServices = pm.queryIntentServices(mediaIntent, 0)
        
        val commonMusicPackages = setOf(
            "com.spotify.music", 
            "com.google.android.apps.youtube.music", 
            "com.apple.android.music", 
            "com.amazon.mp3", 
            "com.soundcloud.android", 
            "org.videolan.vlc", 
            "com.mxtech.videoplayer.ad", 
            "com.deezer.android", 
            "com.tidal.android", 
            "com.pandora.android"
        )

        val musicApps = mediaServices.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.serviceInfo.packageName
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = pkg,
                    icon = pm.getApplicationIcon(appInfo)
                )
            }.getOrNull()
        }.toMutableList()

        commonMusicPackages.forEach { pkg ->
            if (musicApps.none { it.packageName == pkg }) {
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    musicApps.add(AppItem(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = pkg,
                        icon = pm.getApplicationIcon(appInfo)
                    ))
                }
            }
        }

        return musicApps.distinctBy { it.packageName }.sortedBy { it.name }
    }
}
