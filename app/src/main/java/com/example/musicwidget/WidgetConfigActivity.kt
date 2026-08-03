package arenliel.musicwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import arenliel.musicwidget.ui.theme.MusicWidgetTheme
import kotlinx.coroutines.launch

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        render()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render() {
        setContent {
            MusicWidgetTheme {
                val scope = rememberCoroutineScope()
                val context = this@WidgetConfigActivity
                val dataStore = remember { MusicDataStore(context) }
                val musicInfo by dataStore.musicInfoFlow.collectAsState(initial = MusicInfo("", "", ""))
                
                val isPermissionGrantedFlow by remember { derivedStateOf { isNotificationServiceEnabled(context) } }
                val isBatteryIgnoringOptimizationsFlow by remember { derivedStateOf { isIgnoringBatteryOptimizations(context) } }
                
                val installedApps = remember { getInstalledMusicApps(context) }

                var showAppSheet by remember { mutableStateOf(false) }
                val sheetState = rememberModalBottomSheetState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(64.dp))
                        
                        Text(
                            text = "Ajustes de música",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.weight(0.5f))

                        // Sección Seguridad
                        Text(
                            text = "Seguridad",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                        )
                        
                        PermissionCard(
                            isGranted = isPermissionGrantedFlow,
                            onGrantClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                startActivity(intent)
                            },
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(2.dp))

                        BatteryOptimizationCard(
                            isIgnoring = isBatteryIgnoringOptimizationsFlow,
                            onGrantClick = {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(intent)
                            },
                            shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Sección Comportamiento
                        Text(
                            text = "Comportamiento",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                        )
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            SettingsItem(
                                icon = Icons.AutoMirrored.Filled.List,
                                title = "Lista blanca de música",
                                subtitle = if (musicInfo.blacklist.isEmpty()) "Todas las apps" else "${installedApps.size - musicInfo.blacklist.size} apps seleccionadas",
                                onClick = { showAppSheet = true }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))
                    }

                    if (showAppSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showAppSheet = false },
                            sheetState = sheetState,
                            dragHandle = { BottomSheetDefaults.DragHandle() },
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            AppListContent(
                                apps = installedApps,
                                blacklist = musicInfo.blacklist,
                                onToggle = { pkg, checked ->
                                    scope.launch {
                                        dataStore.updateBlacklist(pkg, checked)
                                        // Actualización instantánea del widget al cambiar la lista
                                        MusicWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render() // Refrescar para chequear el permiso
    }

    private fun isNotificationServiceEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun getInstalledMusicApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        
        // 1. Buscar apps que implementen MediaBrowserService (estándar de música en Android)
        val mediaIntent = Intent("android.media.browse.MediaBrowserService")
        val mediaApps = pm.queryIntentServices(mediaIntent, 0).map { it.serviceInfo.packageName }

        // 2. Buscar todas las apps instaladas para filtrar por categoría
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        // 3. Apps conocidas que a veces no se categorizan bien (fallback)
        val commonMusicPackages = setOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", 
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android",
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.deezer.android",
            "com.tidal.android", "com.pandora.android"
        )

        return allApps.filter { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pkg = app.packageName.lowercase()
            val isBluetooth = label.contains("bluetooth") || pkg.contains("bluetooth")

            val isMediaCategory = app.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
                app.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO

            // Incluir si: NO es bluetooth Y (es categoría media O tiene MediaBrowserService O es una app común)
            !isBluetooth && (isMediaCategory || mediaApps.contains(app.packageName) || commonMusicPackages.contains(app.packageName))
        }.map { app ->
            AppItem(
                name = pm.getApplicationLabel(app).toString(),
                packageName = app.packageName,
                icon = pm.getApplicationIcon(app)
            )
        }.distinctBy { it.packageName }.sortedBy { it.name }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AppListContent(
    apps: List<AppItem>,
    blacklist: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = "Lista blanca de música",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "Elige qué apps mostrarán música en el widget",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(apps) { app ->
                val isSelected = !blacklist.contains(app.packageName)
                BlacklistItem(
                    app = app,
                    isBlacklisted = !isSelected, // Reutilizamos pero invertimos visualmente
                    onToggle = { checked ->
                        onToggle(app.packageName, !checked)
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp)) // Espacio al final para ergonomía
    }
}

@Composable
fun BatteryOptimizationCard(isIgnoring: Boolean, onGrantClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isIgnoring) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) 
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = shape
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isIgnoring) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (isIgnoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isIgnoring) "Optimización Desactivada" else "Optimización Activa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isIgnoring) "La app puede trabajar en segundo plano." else "Se recomienda poner en 'No restringido'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isIgnoring) {
                Button(
                    onClick = onGrantClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Ajustar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun PermissionCard(isGranted: Boolean, onGrantClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) 
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = shape
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isGranted) "Acceso Activo" else "Acceso Desactivado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isGranted) "La app ya puede leer tu música." else "Se requiere permiso para funcionar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isGranted) {
                Button(
                    onClick = onGrantClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Activar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun BlacklistItem(app: AppItem, isBlacklisted: Boolean, onToggle: (Boolean) -> Unit) {
    val isSelected = !isBlacklisted
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) 
        else 
            androidx.compose.ui.graphics.Color.Transparent,
        onClick = { onToggle(!isBlacklisted) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = app.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle(!isSelected) },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
