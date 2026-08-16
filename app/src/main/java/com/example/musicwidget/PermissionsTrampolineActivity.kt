package arenliel.musicwidget

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Actividad invisible que sirve de "vigilante" para lanzar pantallas de ajustes
 * y forzar el refresco del widget al regresar.
 */
class PermissionsTrampolineActivity : ComponentActivity() {
    private var launchedSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val targetAction = intent.action
        
        // --- LÓGICA: BÚSQUEDA MUSICAL ---
        if (targetAction == "arenliel.musicwidget.ACTION_SEARCH_PLAY") {
            val title = intent.getStringExtra("EXTRA_TITLE")
            val artist = intent.getStringExtra("EXTRA_ARTIST")
            val query = "$title $artist"
            
            val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(android.app.SearchManager.QUERY, query)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            runCatching {
                startActivity(searchIntent)
            }
            finish()
            return
        }

        if (!targetAction.isNullOrBlank()) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(150) // Respiro técnico
                
                when (targetAction) {
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS -> {
                        PermissionUtils.openNotificationSettings(this@PermissionsTrampolineActivity)
                        launchedSettings = true
                    }
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> {
                        PermissionUtils.openBatterySettings(this@PermissionsTrampolineActivity)
                        launchedSettings = true
                    }
                    else -> {
                        // Fallback genérico para acciones no mapeadas
                        runCatching {
                            val intent = Intent(targetAction).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            launchedSettings = true
                        }.onFailure { finish() }
                    }
                }
            }
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Si regresamos de los ajustes, forzamos el repintado del widget
        if (launchedSettings) {
            CoroutineScope(Dispatchers.Main).launch {
                MusicWidget.updateAll(applicationContext)
                finish()
            }
        }
    }
}
