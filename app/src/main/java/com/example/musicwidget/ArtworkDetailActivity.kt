package arenliel.musicwidget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

class ArtworkDetailActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ArtworkDetail"
        private const val MIN_HD_RESOLUTION = 800
        private const val ALBUM_ART_RAW_FILE = "album_art_raw.webp"
        private const val ALBUM_ART_KEY_FILE = "album_art.key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val artworkUri = intent.getStringExtra("artwork_uri") ?: ""
        val artworkKey = intent.getStringExtra("artwork_key") ?: ""
        
        setContent {
            var bitmapState by remember { mutableStateOf<Bitmap?>(null) }
            val albumArtRawFile = File(filesDir, ALBUM_ART_RAW_FILE)
            val albumArtKeyFile = File(filesDir, ALBUM_ART_KEY_FILE)

            // Cargar imagen inicial desde disco (Versión RAW cuadrada)
            LaunchedEffect(artworkKey) {
                val localBitmap = withContext(Dispatchers.IO) {
                    val savedKey = if (albumArtKeyFile.exists()) albumArtKeyFile.readText().trim() else ""
                    
                    // Cargamos del disco la versión RAW si la clave coincide
                    if (albumArtRawFile.exists() && savedKey == artworkKey && artworkKey.isNotBlank()) {
                        BitmapFactory.decodeFile(albumArtRawFile.absolutePath)
                    } else {
                        null
                    }
                }
                
                if (localBitmap != null) {
                    bitmapState = localBitmap
                }

                // Si la imagen local es de baja resolución o no existe, descargar HD
                if (artworkUri.isNotBlank()) {
                    val needsUpgrade = localBitmap == null || localBitmap.width < MIN_HD_RESOLUTION

                    if (needsUpgrade) {
                        Log.d(TAG, "Iniciando descarga HD...")
                        lifecycleScope.launch(Dispatchers.IO) {
                            val hdBitmap = downloadHD(artworkUri)
                            if (hdBitmap != null && isActive) {
                                withContext(Dispatchers.Main) {
                                    bitmapState = hdBitmap
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)) // Fondo oscuro elegante
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { finish() },
                contentAlignment = Alignment.Center
            ) {
                bitmapState?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Album Art Detail",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .clickable(enabled = false) {}
                    )
                }
            }
        }
    }

    private suspend fun downloadHD(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val finalUrl = if (urlString.startsWith("content://com.spotify.mobile.android.mediaapi")) {
                val hash = urlString.substringAfterLast(":").substringBefore("?")
                "https://i.scdn.co/image/$hash"
            } else {
                urlString
            }

            if (!finalUrl.startsWith("http")) return@withContext null

            connection = URL(finalUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    if (!isActive) return@withContext null
                    BitmapFactory.decodeStream(input)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is SocketException) {
                Log.e(TAG, "Error descargando imagen HD", e)
            }
            null
        } finally {
            connection?.disconnect()
        }
    }
}
