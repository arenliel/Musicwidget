package arenliel.musicwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import arenliel.musicwidget.ui.theme.MusicWidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
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
                
                var refreshTrigger by remember { mutableStateOf(0) }
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                var isPermissionGranted by remember { mutableStateOf(PermissionUtils.isNotificationServiceEnabled(context)) }
                var isBatteryIgnoringOptimizations by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }
                
                LaunchedEffect(refreshTrigger) {
                    if (refreshTrigger > 0) {
                        var attempts = 0
                        while (attempts < 8 && !(isPermissionGranted && isBatteryIgnoringOptimizations)) {
                            isPermissionGranted = PermissionUtils.isNotificationServiceEnabled(context)
                            isBatteryIgnoringOptimizations = PermissionUtils.isBatteryOptimizationIgnored(context)
                            if (isPermissionGranted && isBatteryIgnoringOptimizations) break
                            kotlinx.coroutines.delay(500)
                            attempts++
                        }
                    }
                }

                val allPermissionsGranted = isPermissionGranted && isBatteryIgnoringOptimizations
                
                var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        installedApps = PermissionUtils.getInstalledMusicApps(context)
                    }
                }

                var showAppSheet by remember { mutableStateOf(false) }
                var showDiagnosticSheet by remember { mutableStateOf(false) }
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                val sheetState = rememberModalBottomSheetState()
                val diagnosticSheetState = rememberModalBottomSheetState()

                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.surface, // Fondo base Google (Tono 6)
                    topBar = {
                        LargeTopAppBar(
                            title = { Text(stringResource(R.string.setup_dashboard_title)) },
                            scrollBehavior = scrollBehavior,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    }
                ) { innerPadding ->
                    Column(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Si faltan permisos, mostramos un acceso directo al Setup en MainActivity
                        if (!allPermissionsGranted) {
                            Card(
                                onClick = { 
                                    val intent = Intent(context, MainActivity::class.java)
                                    context.startActivity(intent)
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(text = "Acción requerida", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(text = "Toca para completar la configuración de permisos.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        Text(
                            text = "Comportamiento",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                        )
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            SettingsItem(
                                icon = Icons.AutoMirrored.Filled.List,
                                title = stringResource(R.string.setup_whitelist_title),
                                subtitle = if (musicInfo.blacklist.isEmpty()) "Todas las apps" else "${installedApps.size - musicInfo.blacklist.size} apps seleccionadas",
                                onClick = { showAppSheet = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(64.dp))

                        TextButton(
                            onClick = { showDiagnosticSheet = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_diagnostic_button), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (showAppSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showAppSheet = false },
                            sheetState = sheetState,
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            AppListContent(apps = installedApps, blacklist = musicInfo.blacklist, onToggle = { pkg: String, checked: Boolean ->
                                scope.launch {
                                    dataStore.updateBlacklist(pkg, checked)
                                    WidgetAppearance.entries.forEach { it.updateAll(context) }
                                }
                            })
                        }
                    }

                    if (showDiagnosticSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showDiagnosticSheet = false },
                            sheetState = diagnosticSheetState,
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            DiagnosticSheetContent(context = context)
                        }
                    }
                }
            }
        }
    }
}
