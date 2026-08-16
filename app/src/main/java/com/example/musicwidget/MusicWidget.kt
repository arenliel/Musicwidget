package arenliel.musicwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.appwidget.AppWidgetManager
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.core.app.NotificationManagerCompat
import android.os.PowerManager
import android.provider.Settings
import android.content.ComponentName
import android.net.Uri
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DETERMINISMO ARQUITECTÓNICO: Enum inmutable para definir la identidad del widget.
 * Único punto de verdad (SSOT) para el mapeo entre apariencia y clase técnica (v1.6.3).
 */
enum class WidgetAppearance {
    SMALL,          // Portada Completa (SmallMusicWidget)
    PILL_STANDARD,  // Píldora 2x2 (StandardMusicWidget)
    PILL_CONTROL;   // Centro de Control 4x2 (LargeMusicWidget)

    suspend fun update(context: Context, glanceId: GlanceId) {
        when (this) {
            SMALL -> SmallMusicWidget().update(context, glanceId)
            PILL_STANDARD -> StandardMusicWidget().update(context, glanceId)
            PILL_CONTROL -> LargeMusicWidget().update(context, glanceId)
        }
    }

    suspend fun updateAll(context: Context) {
        when (this) {
            SMALL -> SmallMusicWidget().updateAll(context)
            PILL_STANDARD -> StandardMusicWidget().updateAll(context)
            PILL_CONTROL -> LargeMusicWidget().updateAll(context)
        }
    }
}

/**
 * Segmentación de metadatos para arquitectura Ecuador Visual.
 */
enum class TextPart { TOP, BOTTOM, ALL }

class ClearHistoryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        MusicDataStore(context).clearHistory()
        MusicWidget.updateAll(context)
    }
}

open class MusicWidget(protected val appearance: WidgetAppearance) : GlanceAppWidget() {

    companion object {
        private const val ALBUM_ART_FILE = "album_art.webp"
        private const val ALB_KEY_FILE = "album_art.key"
        private const val ALB_RAW_FILE = "album_art_raw.webp"
        private const val APP_ICON_FILE = "app_icon.webp"
        private const val APP_ICON_KEY_FILE = "app_icon.key"

        private val SIZE_2x1 = DpSize(110.dp, 40.dp)
        private val SIZE_2x2 = DpSize(110.dp, 110.dp)
        private val SIZE_3x2 = DpSize(180.dp, 110.dp)
        private val SIZE_4x2 = DpSize(250.dp, 110.dp)
        private val SIZE_4x4 = DpSize(250.dp, 250.dp)

        // CONSTANTES DEL MOTOR ELÁSTICO (Física de Interfaz)
        private const val MIN_PILL_SIZE_DP = 80f      // Umbral de supervivencia (Paracaídas)
        private const val COMFORT_PILL_SIZE_DP = 100f  // Umbral de comodidad (Reducción de líneas)
        private const val PREMIUM_PILL_SIZE_DP = 110f  // Tamaño máximo
        private const val SAFETY_GAP_DP = 10f          // Amortiguador de presión
        private const val TEXT_SPACERS_TOTAL_DP = 6f   // Suma de spacers en TextInfo (4dp + 2dp)

        val bitmapCache: LruCache<String, Bitmap> by lazy {
            val maxMemory = Runtime.getRuntime().maxMemory() / 1024
            val cacheSize = (maxMemory / 8).toInt() 
            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
            }
        }

        fun clearMemoryCache() { bitmapCache.evictAll() }

        suspend fun updateAll(context: Context) {
            InternalLogger.log(context, "UPDATE: Disparando actualización en cascada (Global).")
            // Actualización determinista basada en la enumeración de identidades (v1.6.3)
            WidgetAppearance.values().forEach { appearance ->
                runCatching { appearance.updateAll(context) }
            }
        }
    }

    override val sizeMode = SizeMode.Exact
    override val previewSizeMode: PreviewSizeMode = SizeMode.Responsive(setOf(SIZE_2x1, SIZE_2x2, SIZE_3x2, SIZE_4x2, SIZE_4x4))

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        InternalLogger.logError(context, "MusicWidget", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }

    @Composable
    private fun dimen(id: Int, fallback: Dp = 0.dp): Dp {
        val context = LocalContext.current
        return try {
            val px = context.resources.getDimension(id)
            (px / context.resources.displayMetrics.density).dp
        } catch (e: Exception) {
            fallback
        }
    }

    @Composable
    private fun spDimen(id: Int): androidx.compose.ui.unit.TextUnit {
        val context = LocalContext.current
        val px = context.resources.getDimension(id)
        return (px / context.resources.displayMetrics.scaledDensity).sp
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = MusicDataStore(context)
        
        provideContent {
            // FAST-TRACK SSOT (v2.0): Priorizamos la memoria sobre el disco
            val memInfo by MusicStateProvider.musicInfoState.collectAsState()
            val diskInfo by dataStore.musicInfoFlow.collectAsState(
                initial = MusicNotificationListener.getLatestMusicInfo() ?: MusicInfo(title = "", artist = "", packageName = "")
            )

            val musicInfo = memInfo ?: diskInfo
            
            val widgetSize = LocalSize.current
            val context = LocalContext.current
            val fontScale = context.resources.configuration.fontScale

            // 1. EVALUACIÓN DEL SENSOR (v1.5.2)
            val sensorResult = if (!widgetSize.width.value.isNaN()) {
                CollisionSensor.evaluate(
                    availableHeight = widgetSize.height.value,
                    fontScale = fontScale,
                    isPreview = false,
                    appearance = appearance
                )
            } else null

            // El asset depende estrictamente de la decisión de layout final
            val needsPillAsset = sensorResult?.layoutType == WidgetLayout.STACKED

            val notificationsEnabled = PermissionUtils.isNotificationServiceEnabled(context)
            val batteryOptimized = PermissionUtils.isBatteryOptimizationIgnored(context)
            
            // MOTOR DE PRESENTACIÓN (v2.2): Transforma el estado interno en visual.
            // Gestiona automáticamente el estado vacío y la lista negra.
            val displayedInfo = musicInfo.copy(
                notificationsEnabled = notificationsEnabled, 
                batteryOptimized = batteryOptimized
            ).toDisplayedState(context)

            val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim() && displayedInfo.artworkKey.isNotBlank()

            val albumArtBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.artworkKey, needsPillAsset, isArtworkSynchronized) {
                if (isArtworkSynchronized) {
                    val cacheKey = "${displayedInfo.artworkKey}_${if(needsPillAsset) "pill" else "raw"}"
                    bitmapCache.get(cacheKey)?.also { value = it } ?: withContext(Dispatchers.IO) {
                        val decoded = decodeBitmap(
                            File(context.filesDir, if (needsPillAsset) ALBUM_ART_FILE else ALB_RAW_FILE),
                            reqWidth = if (needsPillAsset) 400 else 800, // RAW a mayor resolución (P1)
                            reqHeight = if (needsPillAsset) 400 else 800
                        )
                        decoded?.also { bitmapCache.put(cacheKey, it); value = it }
                    }
                } else value = null
            }

            val isIconSynchronized = displayedInfo.appIconKey.trim() == readTextFile(File(context.filesDir, APP_ICON_KEY_FILE)).trim() && displayedInfo.appIconKey.isNotBlank()
            val appIconBitmap by androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, displayedInfo.packageName, isIconSynchronized) {
                if (isIconSynchronized) withContext(Dispatchers.IO) { value = decodeBitmap(File(context.filesDir, APP_ICON_FILE)) }
                else if (displayedInfo.packageName.isNotBlank()) withContext(Dispatchers.IO) {
                    runCatching { context.packageManager.getApplicationIcon(displayedInfo.packageName).toBitmap(40, 40) }.getOrNull()?.also { value = it }
                } else value = null
            }

            GlanceTheme {
                MusicWidgetUI(displayedInfo, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, forcedAppearance = appearance)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val dataStore = MusicDataStore(context)
        val storedInfo = withTimeoutOrNull(500) { dataStore.musicInfoFlow.firstOrNull() }
        val isRealData = storedInfo != null && storedInfo.title.isNotEmpty()
        val musicInfo = if (isRealData) storedInfo!!.copy(history = emptyList()) else MusicInfo(title = context.getString(R.string.widget_empty_title), artist = context.getString(R.string.widget_empty_subtitle), packageName = "", isPlaying = false, isSessionActive = false, playbackDeviceName = "", history = emptyList())
        
        val albumArtFile = File(context.filesDir, ALBUM_ART_FILE)
        val albumArtRawFile = File(context.filesDir, ALB_RAW_FILE)
        val appIconFile = File(context.filesDir, APP_ICON_FILE)
        
        val pillBitmap = if (isRealData) decodeBitmap(albumArtFile) else null
        val rawBitmap = if (isRealData) decodeBitmap(albumArtRawFile) else null
        val appIconBitmap = if (isRealData) {
            decodeBitmap(appIconFile) ?: run { runCatching { context.packageManager.getApplicationIcon(musicInfo.packageName).toBitmap(40, 40) }.getOrNull() }
        } else null
        
        provideContent {
            val widgetSize = LocalSize.current
            val fontScale = context.resources.configuration.fontScale
            
            // Sensor en Preview (v1.5.2) - SSOT para Asset y Layout
            val sensorResult = if (!widgetSize.width.value.isNaN()) {
                CollisionSensor.evaluate(widgetSize.height.value, fontScale, true, appearance)
            } else null
            
            val needsPillAsset = sensorResult?.layoutType == WidgetLayout.STACKED

            GlanceTheme {
                MusicWidgetUI(
                    info = musicInfo, 
                    albumArtBitmap = if (needsPillAsset) pillBitmap else rawBitmap, 
                    appIconBitmap = appIconBitmap, 
                    isArtworkSynchronized = isRealData, 
                    isIconSynchronized = isRealData, 
                    forcedAppearance = appearance,
                    isPreview = true 
                )
            }
        }
    }

    @Composable
    internal fun MusicWidgetUI(
        info: MusicInfo,
        albumArtBitmap: Bitmap?,
        appIconBitmap: Bitmap?,
        isArtworkSynchronized: Boolean,
        isIconSynchronized: Boolean,
        forcedAppearance: WidgetAppearance,
        isPreview: Boolean = false,
        explicitPillSize: Dp? = null
    ) {
        val size = LocalSize.current
        
        if (size.width.value.isNaN() || size.width.value <= 0f) {
            Box(modifier = GlanceModifier.fillMaxSize()) {}
            return
        }

        val context = LocalContext.current
        val fontScale = if (isPreview) 1.0f else context.resources.configuration.fontScale
        val widgetPadding = dimen(R.dimen.widget_padding)
        val widgetRadius = android.R.dimen.system_app_widget_background_radius

        // 1. DETERMINISMO DE IDENTIDAD (Identidad > Colisión)
        val isExplicitSmall = forcedAppearance == WidgetAppearance.SMALL
        
        // 2. EVALUACIÓN DEL SENSOR (Solo para variantes con píldora)
        val collisionResult = if (!isExplicitSmall) {
            CollisionSensor.evaluate(
                availableHeight = size.height.value,
                fontScale = fontScale,
                isPreview = isPreview,
                appearance = forcedAppearance
            )
        } else null
        
        val isActuallyFullBleed = isExplicitSmall || collisionResult?.layoutType == WidgetLayout.FULL_BLEED
        val isWide = if (isPreview) forcedAppearance == WidgetAppearance.PILL_CONTROL else size.width.value >= 220f

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(widgetRadius).background(GlanceTheme.colors.widgetBackground)) {
            if (!info.notificationsEnabled || !info.batteryOptimized) {
                PermissionsView(context, info)
            } else {
                if (isActuallyFullBleed) {
                    Layout2x1(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, collisionResult?.maxArtistLines ?: 1)
                } else if (isWide) {
                    val availableHeightForPillWide = size.height.value - (widgetPadding.value * 2)
                    val widePillSize = explicitPillSize ?: availableHeightForPillWide.coerceIn(80f, 110f).dp
                    Layout4x4(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, widePillSize, collisionResult?.maxArtistLines ?: 2)
                } else {
                    // LAYOUT STANDARD: Estructura de Respiro Garantizado (v1.5.3)
                    Column(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
                        // 1. Portada con tamaño elástico
                        AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, collisionResult?.pillSize ?: 80.dp)
                        
                        // 2. MARGEN DE SEGURIDAD FÍSICO (Infranqueable)
                        Spacer(GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.size(12.dp))
                        
                        // 3. Metadatos (Densidad dictada estrictamente por el sensor)
                        TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = collisionResult?.maxArtistLines ?: 1)
                    }
                }
                Box(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding), contentAlignment = Alignment.TopEnd) { RepeatAnalyticsBadge(info = info) }
            }
        }
    }

    @Composable
    private fun Layout4x4(context: Context, info: MusicInfo, albumArtBitmap: Bitmap?, appIconBitmap: Bitmap?, isArtworkSynchronized: Boolean, isIconSynchronized: Boolean, pillSize: Dp, maxArtistLines: Int) {
        val size = LocalSize.current
        val showHistory = size.height.value >= 150f
        val widgetPadding = dimen(R.dimen.widget_padding)

        Column(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
            // Refactorizado para anclaje inferior de metadatos (v1.5.3)
            Row(modifier = GlanceModifier.fillMaxWidth().height(pillSize), verticalAlignment = Alignment.Top) {
                AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, pillSize)
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 12.dp)) {
                    // 1. Badge (Anclaje superior absoluto)
                    Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { RepeatAnalyticsBadge(info = info) }
                    
                    // 2. Contenedor de Metadatos con Ecuador Visual (v1.6.0)
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        // Segmento A: TOP (Anclado al fondo del área superior)
                        Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                            TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, part = TextPart.TOP)
                        }
                        // Segmento B: BOTTOM (Anclado al tope del área inferior)
                        Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                            TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, part = TextPart.BOTTOM)
                        }
                    }
                }
            }
            if (showHistory) {
                Spacer(GlanceModifier.size(16.dp))
                // CONTENEDOR CON DESVANECIMIENTO (Fading Scrim v1.8.0)
                Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.BottomCenter) {
                    HistoryList(context, info.history, info.trackKey)
                    
                    // EL SCRIM: Desvanece sutilmente la última tarjeta para indicar scroll
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(ImageProvider(R.drawable.history_fade_scrim))
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun PlaybackStatusIndicator(info: MusicInfo, context: Context) {
        val status = getStatusText(context, info)
        val isStatic = info.isPlaying || info.isSessionActive
        Text(
            text = if (isStatic) status.uppercase() else status,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = spDimen(R.dimen.text_size_status), fontWeight = if (isStatic) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1
        )
    }

    @Composable
    private fun HistoryList(context: Context, history: List<HistoryItem>, currentTrackKey: String) {
        val historyHeaderTextSize = spDimen(R.dimen.text_size_history_header)
        val historyHeaderIconSize = dimen(R.dimen.history_header_icon_size)
        
        // FILTRO DE REDUNDANCIA VISUAL:
        // Ocultamos la canción que está sonando actualmente si ya existe en el historial.
        // Esto reduce el ruido visual sin afectar la analítica ni la lógica de persistencia.
        val filteredHistory = if (currentTrackKey.isNotBlank()) {
            history.filter { it.trackKey != currentTrackKey }
        } else history

        // SKELETON HISTÓRICO: Si no hay datos (o quedaron vacíos tras el filtro), 
        // renderizamos tarjetas vacías para showcase (v1.8.4)
        val itemsToRender = if (filteredHistory.isEmpty()) {
            List(4) { HistoryItem(title = "", artist = "", packageName = "", artworkPath = "", artworkKey = "", trackKey = "", timestamp = it.toLong()) }
        } else filteredHistory

        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = context.getString(R.string.history_header), style = TextStyle(fontSize = historyHeaderTextSize, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary), modifier = GlanceModifier.defaultWeight())
                if (history.isNotEmpty()) {
                    Image(provider = ImageProvider(R.drawable.clear_all_24px), contentDescription = context.getString(R.string.content_desc_clear_history), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.size(historyHeaderIconSize).clickable(actionRunCallback<ClearHistoryAction>()))
                }
            }
            LazyColumn(modifier = GlanceModifier.defaultWeight()) { 
                items(items = itemsToRender, itemId = { item -> item.timestamp }) { item -> HistoryItemRow(context, item) } 
            }
        }
    }

    @Composable
    private fun HistoryItemRow(context: Context, item: HistoryItem) {
        val itemTitleSize = spDimen(R.dimen.text_size_history_item_title)
        val itemArtistSize = spDimen(R.dimen.text_size_history_item_artist)
        val artWidth = dimen(R.dimen.history_item_art_width); val artHeight = dimen(R.dimen.history_item_art_height)
        val cacheKey = "hist_${item.trackKey}_${item.timestamp}"
        val bitmap = bitmapCache.get(cacheKey) ?: run {
            val file = File(item.artworkPath)
            val expectedFileName = "art_${item.trackKey.hashCode()}.webp"
            val isSynchronized = file.name == expectedFileName
            val b = if (file.exists() && isSynchronized) decodeBitmap(file, reqWidth = 120, reqHeight = 80) else null
            if (b != null) bitmapCache.put(cacheKey, b); b
        }
        val searchIntent = android.content.Intent(context, PermissionsTrampolineActivity::class.java).apply { action = "arenliel.musicwidget.ACTION_SEARCH_PLAY"; putExtra("EXTRA_TITLE", item.title); putExtra("EXTRA_ARTIST", item.artist); data = Uri.parse("musicwidget://search/${item.title}/${item.artist}/${System.currentTimeMillis()}") }
        Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.surfaceVariant).cornerRadius(16.dp).clickable(actionStartActivity(searchIntent)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = GlanceModifier.size(width = artWidth, height = artHeight)) {
                    if (bitmap != null) Image(provider = ImageProvider(bitmap), contentDescription = context.getString(R.string.content_desc_album_art), contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize().cornerRadius(8.dp))
                    else Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.primaryContainer).cornerRadius(8.dp), contentAlignment = Alignment.Center) { Image(provider = ImageProvider(R.drawable.ic_music_note), contentDescription = context.getString(R.string.content_desc_no_artwork), colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(14.dp)) }
                }
                Spacer(GlanceModifier.size(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { 
                        if (item.title.isNotEmpty()) {
                            Text(text = item.title, style = TextStyle(fontSize = itemTitleSize, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface), maxLines = 1) 
                        } else {
                            // Skeleton bar for title (v1.7.2)
                            Box(modifier = GlanceModifier.size(width = 80.dp, height = 12.dp).background(ImageProvider(R.drawable.preview_skeleton_bar))) {}
                        }
                    }
                    if (item.artist.isNotEmpty()) {
                        Text(text = item.artist, style = TextStyle(fontSize = itemArtistSize, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                    } else {
                        // Skeleton bar for artist (v1.7.2)
                        Spacer(GlanceModifier.size(4.dp))
                        Box(modifier = GlanceModifier.size(width = 50.dp, height = 10.dp).background(ImageProvider(R.drawable.preview_skeleton_bar))) {}
                    }
                }
                if (item.title.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.streakDays >= 3) DesignBadge(iconRes = R.drawable.replay_24px, label = "${item.streakDays}d", isTonal = true)
                        else if (item.playsToday >= 3) DesignBadge(iconRes = R.drawable.mode_heat_24px, label = "${item.playsToday}x", isTonal = true)
                        else if (item.isSkipped && item.skipStreak >= 1) DesignBadge(iconRes = R.drawable.skip_next_24px, label = if (item.skipStreak >= 3) "${item.skipStreak}x" else "", isTonal = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun DesignBadge(iconRes: Int, label: String, isTonal: Boolean = true) {
        Row(modifier = GlanceModifier.background(if (isTonal) GlanceTheme.colors.tertiaryContainer else GlanceTheme.colors.surfaceVariant).cornerRadius(100.dp).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(provider = ImageProvider(iconRes), contentDescription = null, colorFilter = ColorFilter.tint(if (isTonal) GlanceTheme.colors.onTertiaryContainer else GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.size(12.dp))
            if (label.isNotEmpty()) { Spacer(modifier = GlanceModifier.size(3.dp)); Text(text = label, style = TextStyle(color = if (isTonal) GlanceTheme.colors.onTertiaryContainer else GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)) }
        }
    }

    @Composable
    private fun RepeatAnalyticsBadge(info: MusicInfo) {
        // OCULTAMIENTO EN ESTADO VACÍO (v1.7.0)
        if (info.isEmpty) return
        
        val badge = when {
            info.streakDays >= 3 -> Pair(R.drawable.replay_24px, "${info.streakDays}d")
            info.playsToday >= 3 -> Pair(R.drawable.mode_heat_24px, "${info.playsToday}x")
            info.skipStreak >= 1 -> Pair(R.drawable.skip_next_24px, if (info.skipStreak >= 3) "${info.skipStreak}x" else "")
            else -> null
        } ?: return
        DesignBadge(iconRes = badge.first, label = badge.second, isTonal = true)
    }

    private fun getAudioDeviceIcon(type: Int): Int = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.drawable.ic_device_bluetooth
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> R.drawable.ic_device_wired
        android.media.AudioDeviceInfo.TYPE_HDMI, android.media.AudioDeviceInfo.TYPE_HDMI_ARC, android.media.AudioDeviceInfo.TYPE_DOCK -> R.drawable.ic_device_external
        else -> R.drawable.ic_device_phone
    }

    private fun getStatusText(context: Context, info: MusicInfo): String {
        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - info.lastUpdate
        
        // MOTOR DE CONSCIENCIA TEMPORAL (v2.1)
        // Umbral de 15 minutos para considerar una sesión de pausa como "estancada" (stale).
        val PAUSE_STALE_THRESHOLD = 15 * 60 * 1000L

        return when {
            info.isPlaying -> context.getString(R.string.status_listening)
            
            // Si la sesión está en pausa, pero ha pasado el umbral, dejamos que pase al flujo de "Hace poco"
            info.isSessionActive && timeSinceLastUpdate < PAUSE_STALE_THRESHOLD -> 
                context.getString(R.string.status_paused)
            
            else -> { 
                val time = formatRelativeTime(context, info.lastUpdate)
                if (time.isEmpty()) context.getString(R.string.status_recently) else time 
            }
        }
    }

    @Composable
    private fun Layout2x1(context: Context, info: MusicInfo, albumArtBitmap: Bitmap?, appIconBitmap: Bitmap?, isArtworkSynchronized: Boolean, isIconSynchronized: Boolean, maxArtistLines: Int) {
        val size = LocalSize.current
        val isPlaceholder = !isArtworkSynchronized || albumArtBitmap == null
        val widgetPadding = dimen(R.dimen.widget_padding); val visualizerSize = dimen(R.dimen.visualizer_size)
        val fontScale = context.resources.configuration.fontScale
        
        // Sensor de Estrés Local para Full-Bleed (Choque con DeviceIconTonal)
        val tSizeSp = 16f; val aSizeSp = 12f; val sSizeSp = 10f
        val lineHeight = 1.3f
        val deviceIconH = 24f 
        val textHeightWithLabel = ((tSizeSp + aSizeSp + sSizeSp) * fontScale * lineHeight) + 6f
        val paddingTotal = widgetPadding.value * 2
        
        val showStatusLabel = size.height.value >= (textHeightWithLabel + deviceIconH + paddingTotal)
        
        Box(modifier = GlanceModifier.fillMaxSize()) {
            if (!isPlaceholder) {
                Image(
                    provider = ImageProvider(albumArtBitmap!!), 
                    contentDescription = context.getString(R.string.content_desc_album_art), 
                    contentScale = ContentScale.Crop, 
                    modifier = GlanceModifier.fillMaxSize()
                )
                Image(
                    provider = ImageProvider(R.drawable.scrim_gradient), 
                    contentDescription = null, 
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground), 
                    modifier = GlanceModifier.fillMaxSize()
                )
            }
            Box(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { DeviceIconTonal(info = info) }
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Box(modifier = GlanceModifier.defaultWeight()) { TextInfo(context, info, appIconBitmap, showRelativeTime = !showStatusLabel, isIconSynchronized = isIconSynchronized, maxArtistLines = maxArtistLines, isStatusLabelVisible = showStatusLabel) }
                        Spacer(GlanceModifier.size(12.dp)); VisualizerSelector(context, info, visualizerSize)
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceIconTonal(info: MusicInfo) {
        // OCULTAMIENTO EN ESTADO VACÍO (v1.7.0)
        if (info.isEmpty || info.packageName.isBlank()) return
        
        Box(modifier = GlanceModifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(100.dp).padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
            Image(provider = ImageProvider(getAudioDeviceIcon(info.playbackDeviceType)), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer), modifier = GlanceModifier.size(12.dp))
        }
    }

    @Composable
    private fun VisualizerSelector(context: Context, info: MusicInfo, size: Dp) {
        when {
            info.isPlaying -> AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.layout_visualizer), modifier = GlanceModifier.size(size))
            info.isSessionActive -> Image(provider = ImageProvider(R.drawable.ic_visualizer_paused), contentDescription = context.getString(R.string.content_desc_visualizer), modifier = GlanceModifier.size(size))
            else -> Box(modifier = GlanceModifier.size(size).background(GlanceTheme.colors.widgetBackground).cornerRadius(size / 2), contentAlignment = Alignment.Center) { Image(provider = ImageProvider(R.drawable.ic_music_history), contentDescription = context.getString(R.string.content_desc_visualizer), modifier = GlanceModifier.size(size * 0.85f)) }
        }
    }

    @Composable
    private fun PermissionsView(context: Context, info: MusicInfo) {
        val onboardingIntent = android.content.Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val widgetPadding = dimen(R.dimen.widget_padding)
        val boxSize = dimen(R.dimen.permission_box_size); val iconSize = dimen(R.dimen.permission_icon_size)
        Box(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding).clickable(actionStartActivity(onboardingIntent)), contentAlignment = Alignment.BottomStart) {
            Column {
                Row {
                    if (!info.notificationsEnabled) {
                        Box(modifier = GlanceModifier.size(boxSize).cornerRadius(12.dp).background(GlanceTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
                            Image(provider = ImageProvider(R.drawable.notification_settings_24px), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(iconSize))
                        }
                    }
                    if (!info.notificationsEnabled && !info.batteryOptimized) { Spacer(GlanceModifier.size(8.dp)) }
                    if (!info.batteryOptimized) {
                        Box(modifier = GlanceModifier.size(boxSize).cornerRadius(12.dp).background(GlanceTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
                            Image(provider = ImageProvider(R.drawable.battery_profile_24px), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(iconSize))
                        }
                    }
                }
                Spacer(GlanceModifier.size(12.dp))
                Column {
                    Text(text = context.getString(R.string.widget_permission_title), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = spDimen(R.dimen.text_size_title), color = GlanceTheme.colors.onSurface))
                    Text(text = context.getString(R.string.widget_permission_subtitle), style = TextStyle(fontSize = spDimen(R.dimen.text_size_artist), color = GlanceTheme.colors.onSurfaceVariant))
                }
            }
        }
    }

    @Composable
    private fun TextInfo(context: Context, info: MusicInfo, appIconBitmap: Bitmap?, showRelativeTime: Boolean, isIconSynchronized: Boolean, maxArtistLines: Int, isStatusLabelVisible: Boolean = true, part: TextPart = TextPart.ALL) {
        val titleSize = spDimen(R.dimen.text_size_title); val artistSize = spDimen(R.dimen.text_size_artist)
        val fontScale = context.resources.configuration.fontScale; val isHugeFont = fontScale > 1.3f
        
        // Bloque de metadatos con anclaje dinámico según el segmento (v1.6.0)
        Column(
            modifier = GlanceModifier.clickable(actionStartActivity(context.packageManager.getLaunchIntentForPackage(info.packageName) ?: android.content.Intent(context, ArtworkDetailActivity::class.java).apply { putExtra("artwork_uri", info.artworkUri); putExtra("artwork_key", info.artworkKey) })),
            verticalAlignment = if (part == TextPart.BOTTOM) Alignment.Top else Alignment.Bottom
        ) {
            if (part == TextPart.ALL || part == TextPart.TOP) {
                // 1. PlaybackStatusIndicator (Supresión en estado vacío)
                if (isStatusLabelVisible && !info.isEmpty) { 
                    PlaybackStatusIndicator(info, context)
                    Spacer(GlanceModifier.size(4.dp)) 
                }
                
                // 2. Row con icono de la app y Título de la canción
                Row(verticalAlignment = Alignment.CenterVertically) {
                    appIconBitmap?.let { icon -> 
                        if (!isHugeFont && !info.isEmpty) { 
                            Image(provider = ImageProvider(icon), contentDescription = context.getString(R.string.content_desc_app_icon), colorFilter = if (isIconSynchronized) ColorFilter.tint(GlanceTheme.colors.primary) else null, modifier = GlanceModifier.size(14.dp))
                            Spacer(GlanceModifier.size(6.dp)) 
                        } 
                    }
                    Text(
                        text = info.title, 
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = titleSize, color = GlanceTheme.colors.onSurface), 
                        maxLines = 1
                    )
                }
            }
            
            if (part == TextPart.ALL) {
                Spacer(GlanceModifier.size(2.dp))
            }
            
            if (part == TextPart.ALL || part == TextPart.BOTTOM) {
                // 3. Texto de Artista/Letras/Tiempo
                val isSnapshot = showRelativeTime && !info.isSessionActive
                val artistText = when {
                    info.isEmpty -> info.artist
                    info.title == context.getString(R.string.widget_empty_title) -> info.artist
                    isSnapshot && !isStatusLabelVisible -> { val time = formatRelativeTime(context, info.lastUpdate); if (time.isEmpty()) info.artist else "${info.artist} • $time" }
                    info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> "“${info.currentLyric}”"
                    else -> info.artist
                }
                
                // INDICADOR DE FIDELIDAD DEL ARTISTA (Corazón ❤️)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isSnapshot && !artistText.startsWith("“") && info.isFrequentArtist) {
                        Image(
                            provider = ImageProvider(R.drawable.favorite_24px),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(12.dp)
                        )
                        Spacer(modifier = GlanceModifier.size(4.dp))
                    }
                    
                    Text(
                        text = artistText, 
                        style = TextStyle(
                            fontSize = artistSize, 
                            color = if (artistText.startsWith("“")) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant, 
                            fontStyle = if (artistText.startsWith("“")) androidx.glance.text.FontStyle.Italic else androidx.glance.text.FontStyle.Normal
                        ), 
                        maxLines = maxArtistLines 
                    )
                }
            }
        }
    }

    @Composable
    private fun AlbumArtWithVisualizer(context: Context, info: MusicInfo, albumArtBitmap: Bitmap?, isArtworkSynchronized: Boolean, pillSize: Dp, showVisualizer: Boolean = true) {
        val isPlaceholder = !isArtworkSynchronized || albumArtBitmap == null
        Box(modifier = GlanceModifier.size(pillSize).clickable(actionStartActivity(android.content.Intent(context, ArtworkDetailActivity::class.java).apply { putExtra("artwork_uri", info.artworkUri); putExtra("artwork_key", info.artworkKey) }))) {
            if (!isPlaceholder) Image(provider = ImageProvider(albumArtBitmap!!), contentDescription = context.getString(R.string.content_desc_album_art), modifier = GlanceModifier.fillMaxSize())
            else Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.fillMaxSize()) {
                Image(provider = ImageProvider(R.drawable.ic_preview_pill), contentDescription = context.getString(R.string.content_desc_no_artwork), colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer), modifier = GlanceModifier.fillMaxSize())
                Image(provider = ImageProvider(R.drawable.ic_music_note), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(pillSize * 0.3f))
            }
            val safetyMargin = (pillSize.value * 0.04f).dp
            Box(modifier = GlanceModifier.fillMaxSize().padding(top = safetyMargin, start = safetyMargin), contentAlignment = Alignment.TopStart) { DeviceIconTonal(info = info) }
            if (showVisualizer) {
                val visualizerSize = (pillSize.value * 0.24f).dp
                Box(modifier = GlanceModifier.fillMaxSize().padding(bottom = safetyMargin, end = safetyMargin), contentAlignment = Alignment.BottomEnd) { VisualizerSelector(context, info, visualizerSize) }
            }
        }
    }

    private fun formatRelativeTime(context: Context, lastUpdate: Long): String {
        if (lastUpdate <= 0) return ""
        val now = android.os.SystemClock.elapsedRealtime()
        val diffMillis = now - lastUpdate
        val diffHours = diffMillis / (1000 * 60 * 60)
        return when { 
            diffHours < 1 -> context.getString(R.string.widget_time_recently)
            diffHours == 1L -> context.getString(R.string.widget_time_one_hour)
            diffHours < 24 -> context.getString(R.string.widget_time_hours, diffHours)
            else -> context.getString(R.string.widget_time_days) 
        }
    }

    private fun decodeBitmap(file: File, reqWidth: Int = 400, reqHeight: Int = 400): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.absolutePath, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight); options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth; var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) { val halfHeight: Int = height / 2; val halfWidth: Int = width / 2; while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2 }
        return inSampleSize
    }

    private fun readTextFile(file: File) = if (file.isFile && file.length() > 0L) runCatching { file.readText() }.getOrDefault("") else ""
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 340, heightDp = 340)
@Composable
fun Selector_Control_Preview() {
    GlanceTheme { MusicWidgetUIWithMock(appearance = WidgetAppearance.PILL_CONTROL, title = "MONACO", artist = "Bad Bunny", isPlaying = true, width = 340, height = 340, history = listOf(HistoryItem("TIKI TIKI", "QMIIR", "com.spotify.music", "path1", "ak1", "tk1", System.currentTimeMillis(), streakDays = 5), HistoryItem("NUEVAYOL", "Bad Bunny", "com.spotify.music", "path2", "ak2", "tk2", System.currentTimeMillis() - 10000, isSkipped = true, skipStreak = 3)), playsToday = 3) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 180)
@Composable
fun Selector_Full_Preview() {
    GlanceTheme { MusicWidgetUIWithMock(appearance = WidgetAppearance.SMALL, title = "Breeze!", artist = "arenliel", isPlaying = true, height = 180, width = 180, streakDays = 2) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 180)
@Composable
fun Selector_Pill_Preview() {
    GlanceTheme { MusicWidgetUIWithMock(appearance = WidgetAppearance.PILL_STANDARD, title = "A&W", artist = "Lana Del Rey", isPlaying = true, height = 180, width = 180, playsToday = 5) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 340, heightDp = 340)
@Composable
fun XML_Preview_Large_4x4() {
    val context = LocalContext.current
    AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.widget_music_preview_4x4))
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 110)
@Composable
fun XML_Preview_Standard_Pill() {
    val context = LocalContext.current
    AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.widget_preview))
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 180)
@Composable
fun XML_Preview_Full_Cover() {
    val context = LocalContext.current
    AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.widget_preview_full))
}

@Composable
private fun MusicWidgetUIWithMock(appearance: WidgetAppearance, title: String = "Song Title", artist: String = "Artist Name", isPlaying: Boolean = false, isSessionActive: Boolean = false, notificationsEnabled: Boolean = true, batteryOptimized: Boolean = true, color: Int = 0xFF6200EE.toInt(), width: Int = 180, height: Int = 180, history: List<HistoryItem> = emptyList(), useDynamicScaling: Boolean = true, playsToday: Int = 0, streakDays: Int = 0, skipStreak: Int = 0) {
    val context = LocalContext.current; val density = context.resources.displayMetrics.density; val pillWidthPx = (110 * density).toInt()
    val mockBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    val mockIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_music_note)?.toBitmap(40, 40)
    val pillBitmap = ImageUtils.createRotatedPillBitmap(mockBitmap, -28f, pillWidthPx)
    val isSmall = appearance == WidgetAppearance.SMALL
    
    // DETECCIÓN DE ESTADO VACÍO EN MOCK: Si el título coincide con el empty string original o actual
    val isEmptyMock = title == "¡Reproduce algo!" || title == context.getString(R.string.widget_empty_title) || title.isEmpty()

    MusicWidget(appearance).MusicWidgetUI(
        info = MusicInfo(
            title = title, 
            artist = artist, 
            packageName = "arenliel.musicwidget", 
            trackKey = if (isEmptyMock) "" else "mock_key",
            isPlaying = isPlaying, 
            isSessionActive = isSessionActive, 
            showLyrics = false, 
            notificationsEnabled = notificationsEnabled, 
            batteryOptimized = batteryOptimized, 
            playbackDeviceName = "Altavoz del teléfono", 
            playbackDeviceType = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 
            history = history, 
            playsToday = playsToday, 
            streakDays = streakDays, 
            skipStreak = skipStreak
        ), 
        albumArtBitmap = if (isEmptyMock || !notificationsEnabled) null else (if (isSmall) mockBitmap else pillBitmap), 
        appIconBitmap = if (isEmptyMock || !notificationsEnabled) null else mockIcon, 
        isArtworkSynchronized = true, 
        isIconSynchronized = true, 
        forcedAppearance = appearance, 
        isPreview = true, 
        explicitPillSize = null
    )
}

// CIUDADANOS DE PRIMERA CLASE: Clases distintas para evitar colisiones de tipo en Glance (v1.6.2)
class SmallMusicWidget : MusicWidget(WidgetAppearance.SMALL)
class StandardMusicWidget : MusicWidget(WidgetAppearance.PILL_STANDARD)
class LargeMusicWidget : MusicWidget(WidgetAppearance.PILL_CONTROL)

class MusicWidgetFullReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SmallMusicWidget()
}
class MusicWidgetPillReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StandardMusicWidget()
}
class MusicWidgetControlReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LargeMusicWidget()
}
