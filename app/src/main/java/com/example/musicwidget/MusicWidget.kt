package arenliel.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.material3.ColorProviders
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MusicWidget : GlanceAppWidget() {

    companion object {
        private const val ALBUM_ART_FILE = "album_art.webp"
        private const val ALBUM_ART_KEY_FILE = "album_art.key"
        private const val APP_ICON_FILE = "app_icon.webp"
        private const val APP_ICON_KEY_FILE = "app_icon.key"
        private const val NO_TRACK_TITLE = "No track"
        private const val EMPTY_STATE_TEXT = "Sin reproducción reciente"
        private val WIDGET_CORNER_RADIUS = android.R.dimen.system_app_widget_background_radius
        private val WIDGET_PADDING = 17.dp
        private val ALBUM_ART_SIZE_CLASSIC = 110.dp
        private val TITLE_TEXT_SIZE = 16.sp
        private val ARTIST_TEXT_SIZE = 14.sp

        private val bitmapCache = object : LruCache<String, Bitmap>(3) {}

        fun clearMemoryCache() {
            bitmapCache.evictAll()
        }
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(180.dp, 110.dp),
            DpSize(180.dp, 180.dp)
        )
    )

    override val previewSizeMode: PreviewSizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 140.dp), // Forzamos un tamaño > 120dp para que la preview incluya textos
            DpSize(180.dp, 110.dp)
        )
    )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val dataStore = MusicDataStore(context)

        provideContent {
            val musicInfo by dataStore.musicInfoFlow.collectAsState(
                initial = MusicNotificationListener.getLatestMusicInfo() ?: MusicInfo(title = "", artist = "", packageName = "")
            )

            val currentInfo = musicInfo

            val displayedInfo = when {
                currentInfo.title.isEmpty() -> {
                    // 1. Estado de CARGA SILENCIOSA (Primer despertar del proceso)
                    currentInfo 
                }
                currentInfo.title == NO_TRACK_TITLE || currentInfo.blacklist.contains(currentInfo.packageName) -> {
                    // 2. Estado VACÍO/INVITACIÓN
                    MusicInfo(
                        title = context.getString(R.string.widget_empty_title),
                        artist = context.getString(R.string.widget_empty_subtitle),
                        packageName = "",
                        isPlaying = false,
                        isSessionActive = false
                    )
                }
                else -> {
                    currentInfo
                }
            }

            // Sincronización de Artwork
            val savedArtKey = readTextFile(File(context.filesDir, ALBUM_ART_KEY_FILE)).trim()
            val isArtworkSynchronized = displayedInfo.artworkKey.trim() == savedArtKey &&
                    displayedInfo.artworkKey.isNotBlank()

            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            Log.d("MusicWidget", "[DIAGNOSTIC] WIDGET_RENDER: title=${displayedInfo.title} | isSync=$isArtworkSynchronized | RAM_USED=${usedMemory}MB")

            val albumArtBitmap = if (isArtworkSynchronized) {
                bitmapCache.get(displayedInfo.artworkKey) ?: decodeBitmap(File(context.filesDir, ALBUM_ART_FILE))?.also {
                    bitmapCache.put(displayedInfo.artworkKey, it)
                }
            } else null

            // Sincronización de Icono de App
            val savedIconKey = readTextFile(File(context.filesDir, APP_ICON_KEY_FILE)).trim()
            val isIconSynchronized = displayedInfo.appIconKey.trim() == savedIconKey &&
                    displayedInfo.appIconKey.isNotBlank()

            val appIconBitmap = if (isIconSynchronized) {
                decodeBitmap(File(context.filesDir, APP_ICON_FILE))
            } else null

            GlanceTheme {
                MusicWidgetUI(displayedInfo, albumArtBitmap, appIconBitmap, isArtworkSynchronized)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val dataStore = MusicDataStore(context)
        
        // 1. Cargar metadatos reales con timeout estricto
        val musicInfo = withTimeoutOrNull(500) { dataStore.musicInfoFlow.firstOrNull() } ?: MusicInfo(
            title = "Song Title",
            artist = "Artist Name",
            packageName = "com.spotify.music",
            isPlaying = false,
            isSessionActive = true // Forzamos sesión activa para que el diseño siempre incluya textos
        )
        
        // 2. Preparar Bitmaps fuera de provideContent con validación de sincronización
        val density = context.resources.displayMetrics.density
        val pillWidthPx = (110 * density).toInt()
        
        val savedArtKey = readTextFile(File(context.filesDir, ALBUM_ART_KEY_FILE)).trim()
        val isArtworkSynchronized = musicInfo.artworkKey.trim() == savedArtKey &&
                musicInfo.artworkKey.isNotBlank()

        val albumArtFile = File(context.filesDir, ALBUM_ART_FILE)
        val albumArtBitmap = if (isArtworkSynchronized && albumArtFile.exists() && albumArtFile.length() > 0) {
            decodeBitmap(albumArtFile)
        } else {
            // Muestra el placeholder (píldora) si la imagen no está sincronizada o no existe
            null
        }

        val savedIconKey = readTextFile(File(context.filesDir, APP_ICON_KEY_FILE)).trim()
        val isIconSynchronized = musicInfo.appIconKey.trim() == savedIconKey &&
                musicInfo.appIconKey.isNotBlank()
                
        val appIconFile = File(context.filesDir, APP_ICON_FILE)
        val appIconBitmap = if (isIconSynchronized && appIconFile.exists() && appIconFile.length() > 0) {
            decodeBitmap(appIconFile)
        } else null
        
        // 3. Composición única
        provideContent {
            GlanceTheme {
                MusicWidgetUI(
                    info = musicInfo.copy(showLyrics = false), // Letras desactivadas en preview para simplicidad
                    albumArtBitmap = albumArtBitmap,
                    appIconBitmap = appIconBitmap,
                    isArtworkSynchronized = isArtworkSynchronized
                )
            }
        }
    }

    @Composable
    internal fun MusicWidgetUI(
        info: MusicInfo,
        albumArtBitmap: Bitmap?,
        appIconBitmap: Bitmap?,
        isArtworkSynchronized: Boolean
    ) {
        val context = LocalContext.current
        val size = LocalSize.current
        val isSmallMode = size.width.value < 120f || size.height.value < 120f
        val isLoadingState = info.title.isEmpty() && info.artist.isEmpty()

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(WIDGET_CORNER_RADIUS)
                .background(GlanceTheme.colors.widgetBackground)
        ) {
            if (isLoadingState) {
                // 1. Estado de CARGA: Solo mostramos la píldora placeholder para evitar parpadeos
                Box(modifier = GlanceModifier.fillMaxSize().padding(WIDGET_PADDING)) {
                    AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, false)
                }
            } else if (isSmallMode) {
                Box(modifier = GlanceModifier.fillMaxSize().padding(WIDGET_PADDING), contentAlignment = Alignment.Center) {
                    AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, true)
                }
            } else {
                Box(modifier = GlanceModifier.fillMaxSize().padding(WIDGET_PADDING)) {
                    AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, false)

                    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                        Column(
                            modifier = GlanceModifier.clickable(actionStartActivity(
                                context.packageManager.getLaunchIntentForPackage(info.packageName) ?: 
                                android.content.Intent(context, ArtworkDetailActivity::class.java).apply {
                                    putExtra("artwork_uri", info.artworkUri)
                                    putExtra("artwork_key", info.artworkKey)
                                }
                            ))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                appIconBitmap?.let { icon ->
                                    Image(
                                        provider = ImageProvider(icon),
                                        contentDescription = "App Icon",
                                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                                        modifier = GlanceModifier.size(16.dp)
                                    )
                                    Spacer(GlanceModifier.size(6.dp))
                                }
                                
                                Text(
                                    text = info.title,
                                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = TITLE_TEXT_SIZE, color = GlanceTheme.colors.onSurface),
                                    maxLines = 1
                                )
                            }

                            val artistText = when {
                                info.title == context.getString(R.string.widget_empty_title) -> info.artist
                                !info.isPlaying && !info.isSessionActive -> {
                                    val time = formatRelativeTime(context, info.lastUpdate)
                                    // Si no hay tiempo relativo (ej. en previews), mostramos el artista
                                    if (time.isEmpty()) info.artist else time
                                }
                                info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> {
                                    "“${info.currentLyric}”"
                                }
                                else -> info.artist
                            }

                            Text(
                                text = artistText,
                                style = TextStyle(
                                    fontSize = ARTIST_TEXT_SIZE,
                                    color = if (artistText.startsWith("“")) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                                    fontStyle = if (artistText.startsWith("“")) androidx.glance.text.FontStyle.Italic else androidx.glance.text.FontStyle.Normal
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AlbumArtWithVisualizer(
        context: Context,
        info: MusicInfo,
        albumArtBitmap: Bitmap?,
        isArtworkSynchronized: Boolean,
        mini: Boolean
    ) {
        val isPlaceholder = !isArtworkSynchronized || albumArtBitmap == null
        val size = if (mini) 80.dp else ALBUM_ART_SIZE_CLASSIC
        
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = if (mini) Alignment.Center else Alignment.TopStart
        ) {
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = GlanceModifier.clickable(actionStartActivity(
                    android.content.Intent(context, ArtworkDetailActivity::class.java).apply {
                        putExtra("artwork_uri", info.artworkUri)
                        putExtra("artwork_key", info.artworkKey)
                    }
                ))
            ) {
                if (!isPlaceholder) {
                    Image(provider = ImageProvider(albumArtBitmap!!), contentDescription = "Art")
                } else {
                    Log.d("MusicWidget", "[DIAGNOSTIC] FALLBACK_TRIGGERED: title=${info.title} | reason=${if (!isArtworkSynchronized) "Desincronización de Clave" else "Bitmap Nulo"}")
                    
                    // Sustituimos el cuadro redondeado por la PíldoraMaestra (ic_preview_pill)
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_preview_pill),
                            contentDescription = "Pill Background",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer),
                            modifier = GlanceModifier.size(size)
                        )
                        Image(
                            provider = ImageProvider(R.drawable.ic_music_note),
                            contentDescription = "No Art Icon",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                            modifier = GlanceModifier.size(if (mini) 24.dp else 32.dp)
                        )
                    }
                }

                // Selector de estado visual (Smart Visualizer) con desplazamiento vertical hacia abajo
                val visualizerSize = if (mini) 16.dp else 24.dp
                val containerSize = if (mini) 20.dp else 38.dp
                val endPadding = if (mini) 4.dp else 6.dp
                val verticalOffset = if (mini) 2.dp else 8.dp
                
                Box(
                    modifier = GlanceModifier
                        .padding(bottom = 0.dp, end = endPadding)
                        .size(containerSize),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(top = verticalOffset), // Desplazamiento real hacia abajo
                        contentAlignment = Alignment.Center
                    ) {
                        Log.d("MusicWidget", "[DIAGNOSTIC] VISUALIZER_DRAW: title=${info.title} | isPlaying=${info.isPlaying} | isSessionActive=${info.isSessionActive}")
                        when {
                            info.isPlaying -> {
                                AndroidRemoteViews(
                                    remoteViews = RemoteViews(context.packageName, R.layout.layout_visualizer),
                                    modifier = GlanceModifier.size(visualizerSize)
                                )
                            }
                            info.isSessionActive -> {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_visualizer_paused),
                                    contentDescription = "Paused",
                                    modifier = GlanceModifier.size(visualizerSize)
                                )
                            }
                            else -> {
                                Box(
                                    modifier = GlanceModifier
                                        .size(visualizerSize)
                                        .background(GlanceTheme.colors.widgetBackground)
                                        .cornerRadius(visualizerSize / 2),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        provider = ImageProvider(R.drawable.ic_music_history),
                                        contentDescription = "Recent",
                                        modifier = GlanceModifier.size(visualizerSize * 0.85f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatRelativeTime(context: Context, lastUpdate: Long): String {
        if (lastUpdate <= 0) return ""
        val diffMillis = System.currentTimeMillis() - lastUpdate
        val diffHours = diffMillis / (1000 * 60 * 60)
        
        return when {
            diffHours < 1 -> context.getString(R.string.widget_time_recently)
            diffHours == 1L -> context.getString(R.string.widget_time_one_hour)
            diffHours < 24 -> context.getString(R.string.widget_time_hours, diffHours)
            else -> context.getString(R.string.widget_time_days)
        }
    }

    private fun decodeBitmap(file: File) = if (file.isFile && file.length() > 0L) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    } else null

    private fun readTextFile(file: File) = if (file.isFile && file.length() > 0L) {
        runCatching { file.readText() }.getOrDefault("")
    } else ""
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 200)
@Composable
fun PreviewPlaying() {
    GlanceTheme { MusicWidgetUIWithMock(isPlaying = true, isSessionActive = true) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 200)
@Composable
fun PreviewPaused() {
    GlanceTheme { MusicWidgetUIWithMock(isPlaying = false, isSessionActive = true) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 200)
@Composable
fun PreviewHistory() {
    GlanceTheme { MusicWidgetUIWithMock(isPlaying = false, isSessionActive = false) }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 200)
@Composable
fun PreviewEmpty() {
    // Auditamos el nuevo estado de invitación (Idéntico a la preview del selector)
    GlanceTheme { 
        MusicWidgetUIWithMock(
            title = "¡Reproduce algo!", 
            artist = "Sin reproducción reciente",
            isSessionActive = false 
        ) 
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 200)
@Composable
fun PreviewFallback() {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Usamos ContentScale.Fit para que el resguardo no se estire y mantenga su proporción
        Image(
            provider = ImageProvider(R.drawable.widget_preview_fallback),
            contentDescription = "Fallback",
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize()
        )
    }
}

@Composable
private fun MusicWidgetUIWithMock(
    title: String = "Song Title",
    artist: String = "Artist Name",
    isPlaying: Boolean = false,
    isSessionActive: Boolean = false,
    color: Int = 0xFF6200EE.toInt()
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val pillWidthPx = (110 * density).toInt()
    
    val mockBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    val mockIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_music_note)?.toBitmap(40, 40)
    
    val pillBitmap = ImageUtils.createRotatedPillBitmap(mockBitmap, -28f, pillWidthPx)
    
    MusicWidget().MusicWidgetUI(
        info = MusicInfo(
            title = title, 
            artist = artist, 
            packageName = "arenliel.musicwidget", 
            isPlaying = isPlaying,
            isSessionActive = isSessionActive,
            showLyrics = false // Siempre desactivamos letras en las vistas de auditoría de AS
        ),
        albumArtBitmap = if (title == "No track") null else pillBitmap,
        appIconBitmap = if (title == "No track") null else mockIcon,
        isArtworkSynchronized = true
    )
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()

    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        Log.d("MusicWidget", "[DIAGNOSTIC] WIDGET_SIGNAL_RECEIVED: action=${intent.action}")
        super.onReceive(context, intent)
    }
}
