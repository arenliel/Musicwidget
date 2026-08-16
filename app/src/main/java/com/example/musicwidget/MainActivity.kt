package arenliel.musicwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.glance.appwidget.updateAll
import arenliel.musicwidget.ui.theme.MusicWidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true) {
            val appWidgetManager = getSystemService(AppWidgetManager::class.java)
            val myProvider = ComponentName(this, MusicWidgetPillReceiver::class.java)
            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(myProvider, null, null)
            }
        }

        render()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render() {
        setContent {
            MusicWidgetTheme {
                val scope = rememberCoroutineScope()
                val context = this@MainActivity
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
                val isRestrictedLikely = remember(isPermissionGranted) { PermissionUtils.isRestrictedSettingsLikely(context) }
                
                var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        installedApps = PermissionUtils.getInstalledMusicApps(context)
                    }
                }

                var showAppSheet by remember { mutableStateOf(false) }
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                val sheetState = rememberModalBottomSheetState()

                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.surface, // Fondo base Google (Tono 6)
                    topBar = {
                        LargeTopAppBar(
                            title = { 
                                Text(if (allPermissionsGranted) stringResource(R.string.setup_dashboard_title) else stringResource(R.string.setup_title)) 
                            },
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

                        if (!allPermissionsGranted) {
                            Text(
                                text = stringResource(R.string.setup_permissions_header),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                            )
                            
                            PermissionCard(
                                isGranted = isPermissionGranted,
                                onGrantClick = { PermissionUtils.openNotificationSettingsViaTrampoline(context) },
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp)) // Micro-separación M3 Expressive

                            BatteryOptimizationCard(
                                isIgnoring = isBatteryIgnoringOptimizations,
                                onToggle = { PermissionUtils.openBatterySettingsViaTrampoline(context) },
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
                            )

                            if (isRestrictedLikely) {
                                Spacer(modifier = Modifier.height(24.dp))
                                RestrictedSettingsCard(onOpenInfo = { PermissionUtils.openAppInfo(context) })
                            }
                        } else {
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
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Button(
                                onClick = { finish() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Text(stringResource(R.string.setup_finish_button), style = MaterialTheme.typography.titleMedium)
                            }
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
                }
            }
        }
    }
}
